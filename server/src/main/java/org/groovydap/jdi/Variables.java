package org.groovydap.jdi;

import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.ArrayReference;
import com.sun.jdi.ArrayType;
import com.sun.jdi.Field;
import com.sun.jdi.IntegerValue;
import com.sun.jdi.LocalVariable;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.StackFrame;
import com.sun.jdi.StringReference;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Type;
import com.sun.jdi.Value;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Renders JDI values for the variables pane, and hands out the integer handles
 * DAP uses to ask for the next level down.
 *
 * <p>Two Groovy-specific jobs beyond the obvious:
 *
 * <ul>
 *   <li><b>Unwrap {@code groovy.lang.Reference}.</b> A local captured by a
 *       closure is boxed, so the pane would otherwise show
 *       {@code doubled = instance of groovy.lang.Reference(id=1867)} instead of
 *       the list the user wrote. Measured in the T0 spike; design doc §7.0.</li>
 *   <li><b>Hide the compiler's own locals.</b> {@code $callSiteArray},
 *       {@code __$stMC}, {@code this$0} and friends are noise from code
 *       generation, not from the program being debugged.</li>
 * </ul>
 */
public final class Variables {

    /** What a variables handle refers to. */
    private abstract static class Node {
        abstract List<Map<String, Object>> children();

        /**
         * Registers this name as a watchable field and returns its token, or null.
         *
         * <p>Only a field can be watched: the JVM can report a write to one and
         * has nothing to say about a local, an array element or a map entry. The
         * pane offers "break on value change" only where this answers.
         */
        String watchable(String name, DataPoints points) {
            return null;
        }

        /**
         * Writes one of this node's slots.
         *
         * <p>Refusing is the default: a change that quietly went nowhere would be
         * worse than one that was never offered.
         */
        Map<String, Object> set(String name, String text, ThreadReference on)
                throws PathEvaluator.Unsupported {
            throw new PathEvaluator.Unsupported(name + " cannot be changed from here");
        }
    }

    private final class FrameLocals extends Node {
        final ThreadReference thread;
        final int frameIndex;

        FrameLocals(ThreadReference thread, int frameIndex) {
            this.thread = thread;
            this.frameIndex = frameIndex;
        }

        @Override
        List<Map<String, Object>> children() {
            List<Map<String, Object>> out = new ArrayList<>();
            try {
                StackFrame frame = thread.frame(frameIndex);
                ObjectReference self = frame.thisObject();
                if (self != null) {
                    out.add(describe("this", self));
                }
                List<LocalVariable> locals;
                try {
                    locals = frame.visibleVariables();
                } catch (AbsentInformationException e) {
                    return out;
                }
                Map<LocalVariable, Value> values = frame.getValues(locals);
                for (LocalVariable local : locals) {
                    if (isSynthetic(local.name())) {
                        continue;
                    }
                    out.add(describe(local.name(), values.get(local), local.typeName()));
                }
            } catch (Exception e) {
                out.add(error(e));
            }
            return out;
        }

        @Override
        Map<String, Object> set(String name, String text, ThreadReference on)
                throws PathEvaluator.Unsupported {
            try {
                if (name.equals("this")) {
                    throw new PathEvaluator.Unsupported("this cannot be reassigned");
                }
                StackFrame frame = thread.frame(frameIndex);
                LocalVariable local = frame.visibleVariableByName(name);
                if (local == null) {
                    throw new PathEvaluator.Unsupported("no local named " + name + " here");
                }
                // A local a closure captured lives in a groovy.lang.Reference. The
                // slot holds the box, so the value goes inside it: assigning the
                // slot would replace the box and leave the closure holding the old
                // one, which is a change the program would not see.
                Value current = frame.getValue(local);
                if (current instanceof ObjectReference
                        && Values.unwrapReference(current) != current) {
                    return setField((ObjectReference) current, "value", text, on);
                }
                Value value = ValueFactory.of(text, local.type(), on);
                // Making a boxed number calls valueOf in the target, and an
                // invocation resumes the thread to run it -- which invalidates
                // every StackFrame taken before it. The frame has to be fetched
                // again; measured as "Thread has been resumed" on setting a local
                // typed java.lang.Integer, which in Groovy is most of them. The
                // LocalVariable survives: it belongs to the method, not the frame.
                thread.frame(frameIndex).setValue(local, value);
                return describe(name, value, local.typeName());
            } catch (PathEvaluator.Unsupported e) {
                throw e;
            } catch (Exception e) {
                throw new PathEvaluator.Unsupported("could not set " + name + ": " + e);
            }
        }
    }

    private final class ObjectFields extends Node {
        final ObjectReference object;

        ObjectFields(ObjectReference object) {
            this.object = object;
        }

        @Override
        List<Map<String, Object>> children() {
            List<Map<String, Object>> out = new ArrayList<>();
            try {
                // Instance fields only, and one entry per name. allFields() walks
                // the whole hierarchy, so it also returns the class's constants --
                // expanding an Integer listed MIN_VALUE, MAX_VALUE, TYPE, digits,
                // SIZE, BYTES and serialVersionUID twice, once for Integer and once
                // for Number -- none of which says anything about this object.
                List<Field> fields = new ArrayList<>();
                Set<String> seen = new HashSet<>();
                for (Field field : object.referenceType().allFields()) {
                    if (field.isStatic() || isSynthetic(field.name())) {
                        continue;
                    }
                    if (seen.add(field.name())) {
                        fields.add(field);
                    }
                }
                Map<Field, Value> values = object.getValues(fields);
                for (Field field : fields) {
                    out.add(describe(field.name(), values.get(field), field.typeName()));
                }
            } catch (Exception e) {
                out.add(error(e));
            }
            return out;
        }

        @Override
        Map<String, Object> set(String name, String text, ThreadReference on)
                throws PathEvaluator.Unsupported {
            return setField(object, name, text, on);
        }

        @Override
        String watchable(String name, DataPoints points) {
            return registerWatch(object, name, points);
        }
    }

    private final class ArrayElements extends Node {
        final ArrayReference array;

        ArrayElements(ArrayReference array) {
            this.array = array;
        }

        @Override
        List<Map<String, Object>> children() {
            List<Map<String, Object>> out = new ArrayList<>();
            try {
                int length = array.length();
                int shown = Math.min(length, MAX_ARRAY_ELEMENTS);
                for (int i = 0; i < shown; i++) {
                    out.add(describe("[" + i + "]", array.getValue(i)));
                }
                if (shown < length) {
                    out.add(literal("...", (length - shown) + " more elements"));
                }
            } catch (Exception e) {
                out.add(error(e));
            }
            return out;
        }

        @Override
        Map<String, Object> set(String name, String text, ThreadReference on)
                throws PathEvaluator.Unsupported {
            String index = name.startsWith("[") && name.endsWith("]")
                    ? name.substring(1, name.length() - 1) : name;
            try {
                Type component = array.referenceType() instanceof ArrayType
                        ? ((ArrayType) array.referenceType()).componentType() : null;
                Value value = ValueFactory.of(text, component, on);
                array.setValue(Integer.parseInt(index.trim()), value);
                return describe(name, value, null);
            } catch (NumberFormatException e) {
                throw new PathEvaluator.Unsupported(name + " is not an index");
            } catch (PathEvaluator.Unsupported e) {
                throw e;
            } catch (Exception e) {
                throw new PathEvaluator.Unsupported("could not set " + name + ": " + e);
            }
        }
    }

    /**
     * A chosen set of an object's fields, in a chosen order.
     *
     * <p>For the Grails scope: a GrailsWebRequest has twenty-odd fields and four of
     * them are what anyone stopped in a controller wants. A null one is still
     * listed -- a null session says nothing has touched the session yet, which is
     * worth knowing.
     */
    private final class CuratedFields extends Node {
        final ObjectReference object;
        final List<String> names;

        CuratedFields(ObjectReference object, List<String> names) {
            this.object = object;
            this.names = names;
        }

        @Override
        List<Map<String, Object>> children() {
            List<Map<String, Object>> out = new ArrayList<>();
            try {
                for (String name : names) {
                    Field field = object.referenceType().fieldByName(name);
                    if (field == null) {
                        continue;
                    }
                    out.add(describe(name, object.getValue(field), field.typeName()));
                }
            } catch (Exception e) {
                out.add(error(e));
            }
            return out;
        }

        @Override
        Map<String, Object> set(String name, String text, ThreadReference on)
                throws PathEvaluator.Unsupported {
            return setField(object, name, text, on);
        }

        @Override
        String watchable(String name, DataPoints points) {
            return registerWatch(object, name, points);
        }
    }

    /**
     * A map shown as its entries.
     *
     * <p>Without this, {@code params} in a Grails controller is three levels of
     * plumbing: a GrailsParameterMap wrapping a LinkedHashMap whose fields are a
     * table, a size and a modCount. What the user asked to see is which parameters
     * came in.
     *
     * <p>Walked, not invoked. A HashMap keeps its entries in a table of nodes with
     * a next chain, and reading it is a handful of field reads -- where calling
     * entrySet() would mean running code in the application to describe it.
     * ConcurrentHashMap uses the same shape with the value field named val.
     */
    private final class MapEntries extends Node {
        final ObjectReference map;

        MapEntries(ObjectReference map) {
            this.map = map;
        }

        @Override
        List<Map<String, Object>> children() {
            List<Map<String, Object>> out = new ArrayList<>();
            try {
                ArrayReference table = tableOf(map);
                if (table == null) {
                    return out;
                }
                for (Value slot : table.getValues()) {
                    ObjectReference node = slot instanceof ObjectReference
                            ? (ObjectReference) slot : null;
                    int guard = 0;
                    while (node != null && guard++ < 64 && out.size() < MAX_MAP_ENTRIES) {
                        Field keyField = node.referenceType().fieldByName("key");
                        Field valueField = valueFieldOf(node);
                        if (keyField == null || valueField == null) {
                            break;
                        }
                        out.add(describe(label(node.getValue(keyField)),
                                node.getValue(valueField), null));
                        Field nextField = node.referenceType().fieldByName("next");
                        Value next = nextField == null ? null : node.getValue(nextField);
                        node = next instanceof ObjectReference ? (ObjectReference) next : null;
                    }
                }
                if (out.size() >= MAX_MAP_ENTRIES) {
                    out.add(literal("...", "more entries not shown"));
                }
            } catch (Exception e) {
                out.add(error(e));
            }
            return out;
        }

        private String label(Value key) {
            return key instanceof StringReference
                    ? ((StringReference) key).value() : String.valueOf(render(key));
        }

        /**
         * Changes what a key maps to, by writing the entry node's value field.
         *
         * <p>The same write put would do for a key that is already there: no
         * rehashing, no resize, no new entry, so it needs no call into the
         * application. A key that is not there cannot be added this way and says
         * so, rather than appearing to work.
         */
        @Override
        Map<String, Object> set(String name, String text, ThreadReference on)
                throws PathEvaluator.Unsupported {
            try {
                ArrayReference table = tableOf(map);
                if (table == null) {
                    throw new PathEvaluator.Unsupported("this is not a map any more");
                }
                for (Value slot : table.getValues()) {
                    ObjectReference node = slot instanceof ObjectReference
                            ? (ObjectReference) slot : null;
                    int guard = 0;
                    while (node != null && guard++ < 64) {
                        Field keyField = node.referenceType().fieldByName("key");
                        Field valueField = valueFieldOf(node);
                        if (keyField == null || valueField == null) {
                            break;
                        }
                        if (label(node.getValue(keyField)).equals(name)) {
                            Value value = ValueFactory.of(text, valueField.type(), on);
                            node.setValue(valueField, value);
                            return describe(name, value, null);
                        }
                        Field nextField = node.referenceType().fieldByName("next");
                        Value next = nextField == null ? null : node.getValue(nextField);
                        node = next instanceof ObjectReference ? (ObjectReference) next : null;
                    }
                }
                throw new PathEvaluator.Unsupported("no entry " + name
                        + "; a key that is not there cannot be added by setting it");
            } catch (PathEvaluator.Unsupported e) {
                throw e;
            } catch (Exception e) {
                throw new PathEvaluator.Unsupported("could not set " + name + ": " + e);
            }
        }
    }

    private static final int MAX_MAP_ENTRIES = 200;

    /** The entry table of a HashMap-shaped map, or null if this is not one. */
    private static ArrayReference tableOf(ObjectReference object) {
        Field table = object.referenceType().fieldByName("table");
        if (table == null) {
            return null;
        }
        Value value = object.getValue(table);
        return value instanceof ArrayReference ? (ArrayReference) value : null;
    }

    /** HashMap calls it value; ConcurrentHashMap calls it val. */
    private static Field valueFieldOf(ObjectReference node) {
        Field field = node.referenceType().fieldByName("value");
        return field != null ? field : node.referenceType().fieldByName("val");
    }

    /**
     * The map a value really is, when it is wrapping one.
     *
     * <p>A GrailsParameterMap is not a HashMap; it holds one in {@code wrappedMap}
     * alongside a request and a date cache. Showing the wrapper's fields answers a
     * question nobody asked.
     */
    private static ObjectReference unwrapMap(ObjectReference object) {
        if (tableOf(object) != null) {
            return object;
        }
        Field wrapped = object.referenceType().fieldByName("wrappedMap");
        if (wrapped == null) {
            return null;
        }
        Value value = object.getValue(wrapped);
        if (value instanceof ObjectReference && tableOf((ObjectReference) value) != null) {
            return (ObjectReference) value;
        }
        return null;
    }

    private static final int MAX_ARRAY_ELEMENTS = 200;
    /** How much of a collection goes into a log line, and how deep. */
    private static final int MAX_INLINE_ELEMENTS = 8;
    private static final int MAX_INLINE_DEPTH = 2;

    /** package_with_underscores_TraitName__field, how Groovy names a trait field. */
    private static final java.util.regex.Pattern TRAIT_FIELD =
            java.util.regex.Pattern.compile("[a-z][A-Za-z0-9_]*_[A-Z][A-Za-z0-9]*__.+");


    private final Map<Integer, Node> handles = new LinkedHashMap<>();
    private final AtomicInteger nextHandle = new AtomicInteger(1);

    /** Handles are only valid while the VM stays suspended at one stop. */
    public synchronized void reset() {
        handles.clear();
    }

    public synchronized int localsHandle(ThreadReference thread, int frameIndex) {
        return register(new FrameLocals(thread, frameIndex));
    }

    /**
     * One value, described the way an evaluate response wants it.
     *
     * <p>Same rendering as the pane, so a hover and the variables view never
     * disagree about what something is.
     */
    public synchronized Map<String, Object> describeResult(Value value) {
        Map<String, Object> described = describe("", value, null);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("result", described.get("value"));
        body.put("type", described.get("type"));
        body.put("variablesReference", described.get("variablesReference"));
        return body;
    }

    /**
     * One value as a line of text, for a logpoint.
     *
     * <p>Same rendering as the pane except for strings, which lose their quotes:
     * {@code "saving Widget 3"} is a log line, {@code saving "Widget" 3} is a
     * transcription of the debugger's own notation.
     */
    public synchronized String plain(Value value) {
        return plain(value, 0);
    }

    private String plain(Value value, int depth) {
        Value effective = unwrapReference(value);
        if (effective instanceof StringReference) {
            return ((StringReference) effective).value();
        }
        String collection = depth < MAX_INLINE_DEPTH ? plainCollection(effective, depth) : null;
        return collection != null ? collection : render(effective);
    }

    /**
     * A list or a map written out, for a log line.
     *
     * <p>The pane can afford to say {@code ArrayList (id=14513)} and let the user
     * expand it; a line of text cannot -- an object id in a log is nothing at all.
     * So the contents go in, walked out of the same fields the pane walks and
     * capped, because a log line is not a place to print a thousand elements.
     */
    private String plainCollection(Value value, int depth) {
        if (!(value instanceof ObjectReference)) {
            return null;
        }
        ObjectReference object = (ObjectReference) value;
        List<String> parts = new ArrayList<>();
        boolean more = false;

        ObjectReference asMap = unwrapMap(object);
        if (asMap != null) {
            ArrayReference table = tableOf(asMap);
            for (Value slot : table.getValues()) {
                ObjectReference node = slot instanceof ObjectReference
                        ? (ObjectReference) slot : null;
                int guard = 0;
                while (node != null && guard++ < 64) {
                    Field keyField = node.referenceType().fieldByName("key");
                    Field valueField = valueFieldOf(node);
                    if (keyField == null || valueField == null) {
                        break;
                    }
                    if (parts.size() >= MAX_INLINE_ELEMENTS) {
                        more = true;
                        break;
                    }
                    parts.add(plain(node.getValue(keyField), depth + 1) + ":"
                            + plain(node.getValue(valueField), depth + 1));
                    Field nextField = node.referenceType().fieldByName("next");
                    Value next = nextField == null ? null : node.getValue(nextField);
                    node = next instanceof ObjectReference ? (ObjectReference) next : null;
                }
            }
            return "[" + String.join(", ", parts) + (more ? ", ..." : "") + "]";
        }

        Field data = object.referenceType().fieldByName("elementData");
        Field sizeField = object.referenceType().fieldByName("size");
        if (data == null || sizeField == null) {
            return null;
        }
        Value array = object.getValue(data);
        Value count = object.getValue(sizeField);
        if (!(array instanceof ArrayReference) || !(count instanceof IntegerValue)) {
            return null;
        }
        int size = Math.min(((IntegerValue) count).value(), ((ArrayReference) array).length());
        int shown = Math.min(size, MAX_INLINE_ELEMENTS);
        for (int i = 0; i < shown; i++) {
            parts.add(plain(((ArrayReference) array).getValue(i), depth + 1));
        }
        return "[" + String.join(", ", parts)
                + (shown < size ? ", ... " + (size - shown) + " more" : "") + "]";
    }

    /** A handle over just these fields of this object, in this order. */
    public synchronized int curatedHandle(ObjectReference object, List<String> fieldNames) {
        return register(new CuratedFields(object, fieldNames));
    }

    /**
     * Writes a slot of whatever this handle refers to.
     *
     * @throws PathEvaluator.Unsupported with the reason, which the client shows
     */
    public synchronized Map<String, Object> set(int handle, String name, String text,
                                               ThreadReference on)
            throws PathEvaluator.Unsupported {
        Node node = handles.get(handle);
        if (node == null) {
            throw new PathEvaluator.Unsupported(
                    "that variable belongs to an earlier stop and is gone");
        }
        if (on == null) {
            throw new PathEvaluator.Unsupported("nothing is stopped");
        }
        return node.set(name, text, on);
    }

    /** The token for watching this name of this handle, or null if it is not a field. */
    public synchronized String watchable(int handle, String name, DataPoints points) {
        Node node = handles.get(handle);
        return node == null ? null : node.watchable(name, points);
    }

    /**
     * Registers a field of an object as watchable.
     *
     * <p>A synthetic field is refused for the same reason it is not shown: it is
     * the compiler's, not the programmer's. A final one is refused because it is
     * never written again, and a breakpoint that cannot fire is worse than one
     * that was not offered.
     */
    private String registerWatch(ObjectReference object, String name, DataPoints points) {
        if (isSynthetic(name)) {
            return null;
        }
        Field field = object.referenceType().fieldByName(name);
        if (field == null || field.isFinal()) {
            return null;
        }
        return points.register(field, field.isStatic() ? null : object,
                object.referenceType().name() + "." + name);
    }

    /** One field of one object, with a Reference unwrapped on the way in. */
    private Map<String, Object> setField(ObjectReference object, String name, String text,
                                         ThreadReference on)
            throws PathEvaluator.Unsupported {
        Field field = object.referenceType().fieldByName(name);
        if (field == null) {
            throw new PathEvaluator.Unsupported(
                    object.referenceType().name() + " has no field " + name);
        }
        try {
            Value current = object.getValue(field);
            if (current instanceof ObjectReference
                    && Values.unwrapReference(current) != current) {
                return setField((ObjectReference) current, "value", text, on);
            }
            Value value = ValueFactory.of(text, field.type(), on);
            object.setValue(field, value);
            return describe(name, value, field.typeName());
        } catch (PathEvaluator.Unsupported e) {
            throw e;
        } catch (Exception e) {
            // A final field is the usual one: the JVM refuses the write.
            throw new PathEvaluator.Unsupported("could not set " + name + ": " + e);
        }
    }

    public synchronized List<Map<String, Object>> children(int handle) {
        Node node = handles.get(handle);
        return node == null ? new ArrayList<>() : node.children();
    }

    private int register(Node node) {
        int handle = nextHandle.getAndIncrement();
        handles.put(handle, node);
        return handle;
    }

    /**
     * A field or local the compiler introduced rather than the programmer.
     *
     * <p>Three kinds. Groovy's own -- {@code this$0}, {@code $callSiteArray},
     * {@code __$stMC} -- all carry a dollar sign. {@code metaClass} is named
     * outright. And a trait's fields are compiled into every implementing class
     * under a mangled name, {@code package_with_underscores_TraitName__field},
     * which carries no dollar sign at all.
     *
     * <p>That third kind is most of what a GORM domain object shows. A domain
     * class with one property of its own arrives carrying
     * {@code org_grails_datastore_gorm_GormValidateable__errors},
     * {@code org_grails_datastore_gorm_GormValidateable__skipValidate} and
     * {@code org_grails_datastore_mapping_dirty_checking_DirtyCheckable__$changedProperties},
     * none of which is the object as its author wrote it. Read off a compiled
     * Grails 7.2.3 domain class.
     */
    static boolean isSynthetic(String name) {
        return name.indexOf('$') >= 0
                || name.equals("metaClass")
                || name.equals("__timeStamp")
                || TRAIT_FIELD.matcher(name).matches();
    }

    private Map<String, Object> describe(String name, Value value) {
        return describe(name, value, null);
    }

    /**
     * @param declaredType what the source says it is, used when the value is null.
     *     A null has no runtime type to report, and "null : null" says less than
     *     the declaration does -- a domain object's unsaved id reads as
     *     {@code java.lang.Long} rather than as nothing at all.
     */
    private Map<String, Object> describe(String name, Value value, String declaredType) {
        Value effective = unwrapReference(value);
        Map<String, Object> variable = new LinkedHashMap<>();
        variable.put("name", name);
        variable.put("value", render(effective));
        variable.put("type", effective != null ? effective.type().name()
                : declaredType != null ? declaredType : "null");
        variable.put("variablesReference", handleFor(effective));
        return variable;
    }

    /**
     * {@code groovy.lang.Reference} is the box Groovy puts around a local that a
     * closure captures. Unwrap one level so the pane shows the value the source
     * talks about.
     */
    private Value unwrapReference(Value value) {
        return Values.unwrapReference(value);
    }

    /**
     * Boxed primitives read as their value, not as an object id.
     *
     * <p>Groovy boxes far more than Java does -- every value that crosses a
     * metaclass call site arrives as an {@code Integer}, and unwrapping a
     * {@code groovy.lang.Reference} usually lands on one -- so leaving these as
     * {@code java.lang.Integer (id=14450)} would hide most of what the user came
     * to look at. Reading the {@code value} field is a plain field read; it runs
     * no code in the target VM.
     */
    private String renderBoxed(ObjectReference object, String type) {
        Value inner = Values.boxedValue(object);
        return inner == null ? null : String.valueOf(inner);
    }

    private int handleFor(Value value) {
        if (value instanceof ArrayReference) {
            return register(new ArrayElements((ArrayReference) value));
        }
        if (value instanceof StringReference) {
            return 0; // a string is a leaf, however it is represented in the VM
        }
        if (value instanceof ObjectReference) {
            ObjectReference object = (ObjectReference) value;
            if (Values.isBox(object.referenceType().name())) {
                return 0; // already shown as its value; there is nothing inside
            }
            ObjectReference asMap = unwrapMap(object);
            if (asMap != null) {
                return register(new MapEntries(asMap));
            }
            return register(new ObjectFields(object));
        }
        return 0;
    }

    private String render(Value value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof StringReference) {
            return '"' + ((StringReference) value).value() + '"';
        }
        if (value instanceof ArrayReference) {
            ArrayReference array = (ArrayReference) value;
            return array.referenceType().name() + "[" + array.length() + "]";
        }
        if (value instanceof ObjectReference) {
            ObjectReference object = (ObjectReference) value;
            String type = object.referenceType().name();
            ObjectReference asMap = unwrapMap(object);
            if (asMap != null) {
                Field size = asMap.referenceType().fieldByName("size");
                Value count = size == null ? null : asMap.getValue(size);
                return type + (count == null ? "" : " (" + count + " entries)");
            }
            String boxed = renderBoxed(object, type);
            if (boxed != null) {
                return boxed;
            }
            // No toString() call: invoking a method in the target VM has to resume
            // threads to do it, and a debugger that runs user code just to paint a
            // pane can deadlock or change what it is observing.
            return type + " (id=" + object.uniqueID() + ")";
        }
        return String.valueOf(value);
    }

    private static Map<String, Object> literal(String name, String text) {
        Map<String, Object> variable = new LinkedHashMap<>();
        variable.put("name", name);
        variable.put("value", text);
        variable.put("variablesReference", 0);
        return variable;
    }

    private static Map<String, Object> error(Exception e) {
        return literal("<error>", e.getClass().getSimpleName()
                + (e.getMessage() == null ? "" : ": " + e.getMessage()));
    }

}
