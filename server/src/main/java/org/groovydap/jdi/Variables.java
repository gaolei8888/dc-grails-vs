package org.groovydap.jdi;

import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.ArrayReference;
import com.sun.jdi.ClassNotLoadedException;
import com.sun.jdi.Field;
import com.sun.jdi.InconsistentDebugInfoException;
import com.sun.jdi.InvalidTypeException;
import com.sun.jdi.LocalVariable;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.StackFrame;
import com.sun.jdi.StringReference;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Value;
import com.sun.jdi.VirtualMachine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

        /** The place a child called {@code name} is stored, if it can be changed at all. */
        Slot slot(String name) throws Exception {
            throw new EvaluationException("'" + name + "' cannot be changed here "
                    + "(map entries and the Grails scope are read-only)");
        }
    }

    /** Where one variable lives in the target: how to read it and how to write it. */
    private abstract static class Slot {
        final String name;
        final String type;
        final VirtualMachine vm;

        Slot(String name, String type, VirtualMachine vm) {
            this.name = name;
            this.type = type;
            this.vm = vm;
        }

        abstract Value read() throws Exception;

        abstract void store(Value value) throws Exception;
    }

    private final class FrameLocals extends Node {
        final ThreadReference thread;
        final int frameIndex;
        /** Locals the last children() call could not read yet; see below. */
        final Set<String> unreadable = new HashSet<>();

        FrameLocals(ThreadReference thread, int frameIndex) {
            this.thread = thread;
            this.frameIndex = frameIndex;
        }

        @Override
        Slot slot(String name) throws Exception {
            if (name.equals("this") || isSynthetic(name)) {
                throw new EvaluationException("'" + name + "' cannot be changed");
            }
            for (LocalVariable local : thread.frame(frameIndex).visibleVariables()) {
                if (local.name().equals(name)) {
                    // A fresh StackFrame for every call: one fetched before the VM
                    // last resumed is invalid, and this way the read after a write
                    // is a real read too.
                    return new Slot(name, local.typeName(), thread.virtualMachine()) {
                        Value read() throws Exception {
                            return thread.frame(frameIndex).getValue(local);
                        }

                        void store(Value value) throws Exception {
                            thread.frame(frameIndex).setValue(local, value);
                        }
                    };
                }
            }
            throw new EvaluationException("'" + name + "' is not a variable of this frame");
        }

        @Override
        List<Map<String, Object>> children() {
            List<Map<String, Object>> out = new ArrayList<>();
            unreadable.clear();
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
                for (LocalVariable local : locals) {
                    if (isSynthetic(local.name())) {
                        continue;
                    }
                    try {
                        out.add(describe(local.name(), frame.getValue(local), local.typeName()));
                    } catch (InconsistentDebugInfoException e) {
                        // Groovy starts the table entry of a boxed parameter's
                        // Reference at bci 1, but the astore that fills the slot
                        // is at bci 9, so on entry to the closure the slot is
                        // "live" yet empty and the VM refuses to read it. The
                        // parameter in its old slot is out of range for JDI too,
                        // so there is nothing to show until the box exists.
                        unreadable.add(local.name());
                    }
                }
            } catch (Exception e) {
                out.add(error(e));
            }
            return out;
        }
    }

    private final class ObjectFields extends Node {
        final ObjectReference object;

        ObjectFields(ObjectReference object) {
            this.object = object;
        }

        /**
         * Instance fields only, and one entry per name. allFields() walks the whole
         * hierarchy, so it also returns the class's constants -- expanding an
         * Integer listed MIN_VALUE, MAX_VALUE, TYPE, digits, SIZE, BYTES and
         * serialVersionUID twice, once for Integer and once for Number -- none of
         * which says anything about this object.
         */
        private List<Field> fields() {
            List<Field> fields = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (Field field : object.referenceType().allFields()) {
                if (!field.isStatic() && !isSynthetic(field.name()) && seen.add(field.name())) {
                    fields.add(field);
                }
            }
            return fields;
        }

        @Override
        Slot slot(String name) {
            String owner = object.referenceType().name();
            for (Field field : fields()) {
                if (!field.name().equals(name)) {
                    continue;
                }
                // Never reachable from the pane, but these are the shared instances
                // (the Integer cache, interned strings) whose mutation would reach
                // the whole JVM, so say no here too.
                if (field.isFinal() || BOXES.contains(owner) || owner.equals("java.lang.String")) {
                    throw new EvaluationException("'" + name + "' is final");
                }
                return new Slot(name, field.typeName(), object.virtualMachine()) {
                    Value read() {
                        return object.getValue(field);
                    }

                    void store(Value value) throws Exception {
                        object.setValue(field, value);
                    }
                };
            }
            throw new EvaluationException("'" + name + "' is not a field the debugger shows");
        }

        @Override
        List<Map<String, Object>> children() {
            List<Map<String, Object>> out = new ArrayList<>();
            try {
                List<Field> fields = fields();
                Map<Field, Value> values = object.getValues(fields);
                for (Field field : fields) {
                    out.add(describe(field.name(), values.get(field), field.typeName()));
                }
            } catch (Exception e) {
                out.add(error(e));
            }
            return out;
        }
    }

    private final class ArrayElements extends Node {
        final ArrayReference array;

        ArrayElements(ArrayReference array) {
            this.array = array;
        }

        @Override
        Slot slot(String name) {
            Matcher m = ELEMENT.matcher(name);
            if (!m.matches() || Integer.parseInt(m.group(1)) >= Math.min(array.length(), MAX_ARRAY_ELEMENTS)) {
                throw new EvaluationException("'" + name + "' is not an element the debugger shows");
            }
            int index = Integer.parseInt(m.group(1));
            String arrayType = array.referenceType().name();
            return new Slot(name, arrayType.substring(0, arrayType.length() - 2), array.virtualMachine()) {
                Value read() {
                    return array.getValue(index);
                }

                void store(Value value) throws Exception {
                    array.setValue(index, value);
                }
            };
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
    private static final String REFERENCE_CLASS = "groovy.lang.Reference";
    private static final Pattern ELEMENT = Pattern.compile("\\[(\\d{1,9})\\]");

    /** package_with_underscores_TraitName__field, how Groovy names a trait field. */
    private static final java.util.regex.Pattern TRAIT_FIELD =
            java.util.regex.Pattern.compile("[a-z][A-Za-z0-9_]*_[A-Z][A-Za-z0-9]*__.+");

    private static final Set<String> BOXES = new HashSet<>(Arrays.asList(
            "java.lang.Integer", "java.lang.Long", "java.lang.Short", "java.lang.Byte",
            "java.lang.Double", "java.lang.Float", "java.lang.Boolean",
            "java.lang.Character"));

    private final Map<Integer, Node> handles = new LinkedHashMap<>();
    private final AtomicInteger nextHandle = new AtomicInteger(1);

    /** Handles are only valid while the VM stays suspended at one stop. */
    public synchronized void reset() {
        handles.clear();
    }

    public synchronized int localsHandle(ThreadReference thread, int frameIndex) {
        return register(new FrameLocals(thread, frameIndex));
    }

    /** A handle over just these fields of this object, in this order. */
    public synchronized int curatedHandle(ObjectReference object, List<String> fieldNames) {
        return register(new CuratedFields(object, fieldNames));
    }

    public synchronized List<Map<String, Object>> children(int handle) {
        Node node = handles.get(handle);
        return node == null ? new ArrayList<>() : node.children();
    }

    /**
     * Resolves a variable path such as {@code order.items[0].name} against a frame,
     * by walking the same tree the variables pane shows.
     *
     * <p>Nothing runs in the target: every hop is a field read through
     * {@link #children}, so the naming, the {@code groovy.lang.Reference} unwrap
     * and the 200-element caps are exactly the pane's, and a path the pane can
     * display is a path this can resolve. A call would have to resume the VM.
     *
     * <p>The first name is looked up in the frame's locals, then in the fields of
     * {@code this}, then in the Grails request scope (params, request, ...).
     *
     * @param webScope only asked for when the name is found nowhere else
     * @return the value as a variables entry: name, value, type, variablesReference
     * @throws EvaluationException with the reason, for anything that cannot be shown
     */
    public synchronized Map<String, Object> evaluate(ThreadReference thread, int frameIndex,
            Supplier<ObjectReference> webScope, String expression) {
        List<PathExpression.Step> steps = PathExpression.parse(expression);
        Map<String, Object> current = lookupFirst(thread, frameIndex, webScope, steps.get(0).text).variable;
        for (int i = 1; i < steps.size(); i++) {
            current = step(current, steps, i);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("result", current.get("value"));
        result.put("type", current.get("type"));
        result.put("variablesReference", current.get("variablesReference"));
        return result;
    }

    /** The variable a path starts at, and the node holding it. */
    private static final class Hit {
        final Map<String, Object> variable;
        final Node container;

        Hit(Map<String, Object> variable, Node container) {
            this.variable = variable;
            this.container = container;
        }
    }

    private Hit lookupFirst(ThreadReference thread, int frameIndex,
            Supplier<ObjectReference> webScope, String first) {
        FrameLocals frameLocals = new FrameLocals(thread, frameIndex);
        List<Map<String, Object>> locals = frameLocals.children();
        Map<String, Object> found = named(locals, first);
        Node container = frameLocals;
        if (found == null && frameLocals.unreadable.contains(first)) {
            throw new EvaluationException("'" + first + "' is not readable at this point "
                    + "(it is initialised later in this method)");
        }
        Map<String, Object> self = named(locals, "this");
        if (found == null && self != null) {
            container = handles.get(refOf(self));
            found = named(children(refOf(self)), first);
        }
        if (found == null && webScope != null
                && GrailsWebScope.interestingFields().contains(first)) {
            ObjectReference request = webScope.get();
            if (request != null) {
                int scope = curatedHandle(request, GrailsWebScope.interestingFields());
                container = handles.get(scope);
                found = named(children(scope), first);
            }
        }
        if (found == null) {
            Map<String, Object> failure = named(locals, "<error>");
            throw new EvaluationException(failure != null
                    ? "cannot read this frame: " + failure.get("value")
                    : "'" + first + "' is not defined in this frame");
        }
        return new Hit(found, container);
    }

    /**
     * DAP setVariable: change one child of a variables handle.
     *
     * @return what the pane shows for it now: value, type, variablesReference
     * @throws EvaluationException with the reason, for anything that cannot be changed
     */
    public synchronized Map<String, Object> setVariable(int handle, String name, String text) {
        Node node = handles.get(handle);
        if (node == null) {
            throw new EvaluationException("no such variable list "
                    + "(the program has resumed since it was shown)");
        }
        return change(node, name, text);
    }

    /**
     * DAP setExpression: change what a variable path names, found by the same walk
     * as {@link #evaluate}.
     */
    public synchronized Map<String, Object> setExpression(ThreadReference thread, int frameIndex,
            Supplier<ObjectReference> webScope, String expression, String text) {
        List<PathExpression.Step> steps = PathExpression.parse(expression);
        Hit hit = lookupFirst(thread, frameIndex, webScope, steps.get(0).text);
        Map<String, Object> current = hit.variable;
        Node container = hit.container;
        for (int i = 1; i < steps.size(); i++) {
            Map<String, Object> next = step(current, steps, i);
            container = handles.get(refOf(current));
            current = next;
        }
        return change(container, (String) current.get("name"), text);
    }

    /**
     * The one write routine: parse the literal, store it, read it back.
     *
     * <p>Nothing here runs code in the target. A String is made with mirrorOf, a
     * primitive is stored as itself, and anything that would need a box or a
     * constructor is refused by {@link ValueParser} before JDI is asked.
     */
    private Map<String, Object> change(Node container, String name, String text) {
        try {
            Slot slot = container.slot(name);
            Value current = slot.read();
            if (isCaptured(current)) {
                throw new EvaluationException("'" + name + "' is captured by a closure ("
                        + REFERENCE_CLASS + "); " + ValueParser.NEEDS_OBJECT);
            }
            String held = current == null ? slot.type : current.type().name();
            try {
                slot.store(ValueParser.parse(slot.vm, name, slot.type, held, text));
            } catch (InvalidTypeException e) {
                throw new EvaluationException("cannot assign " + text + " to '" + name
                        + "' (a " + slot.type + ")");
            } catch (ClassNotLoadedException e) {
                throw new EvaluationException("the type of '" + name + "' (" + e.className()
                        + ") is not loaded in the application yet");
            }
            Map<String, Object> shown = describe(name, slot.read(), slot.type);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("value", shown.get("value"));
            result.put("type", shown.get("type"));
            result.put("variablesReference", shown.get("variablesReference"));
            return result;
        } catch (InconsistentDebugInfoException e) {
            throw new EvaluationException("'" + name + "' is not writable at this point "
                    + "(it is initialised later in this method)");
        } catch (EvaluationException e) {
            throw e;
        } catch (Exception e) {
            throw new EvaluationException("cannot change '" + name + "': "
                    + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        }
    }

    private static boolean isCaptured(Value value) {
        return value instanceof ObjectReference
                && REFERENCE_CLASS.equals(((ObjectReference) value).referenceType().name());
    }

    /** One hop of {@link #evaluate}: the member or element {@code steps[i]} of {@code from}. */
    private Map<String, Object> step(Map<String, Object> from, List<PathExpression.Step> steps, int i) {
        PathExpression.Step wanted = steps.get(i);
        String parent = display(steps, i);
        int handle = refOf(from);
        if (handle == 0) {
            throw new EvaluationException("cannot read '" + wanted + "' of '" + parent + "': "
                    + ("null".equals(from.get("value")) ? "it is null"
                    : "a " + from.get("type") + " has no members to show"));
        }
        List<Map<String, Object>> kids = children(handle);
        Map<String, Object> found;
        if (wanted.isMember()) {
            found = named(kids, wanted.text);
        } else {
            found = named(kids, "[" + wanted.index + "]");         // an array
            if (found == null) {
                found = listElement(kids, wanted.index);           // an ArrayList
            }
            if (found == null) {
                found = named(kids, String.valueOf(wanted.index)); // a map with an int key
            }
        }
        if (found == null) {
            throw new EvaluationException("'" + parent + "' has no " + (wanted.isMember()
                    ? "member '" + wanted.text + "'" : "element " + wanted)
                    + (named(kids, "...") != null
                    ? " among the entries the debugger lists (it shows at most "
                            + MAX_ARRAY_ELEMENTS + ")" : ""));
        }
        return found;
    }

    /** {@code list[n]} for an ArrayList, read out of its backing array like the pane would. */
    private Map<String, Object> listElement(List<Map<String, Object>> fields, int index) {
        Map<String, Object> backing = named(fields, "elementData");
        Map<String, Object> size = named(fields, "size");
        if (backing == null || size == null || refOf(backing) == 0
                || !String.valueOf(size.get("value")).matches("\\d+")
                || index >= Integer.parseInt(String.valueOf(size.get("value")))) {
            return null;
        }
        return named(children(refOf(backing)), "[" + index + "]");
    }

    private static String display(List<PathExpression.Step> steps, int upTo) {
        StringBuilder sb = new StringBuilder(steps.get(0).text);
        for (int i = 1; i < upTo; i++) {
            sb.append(steps.get(i));
        }
        return sb.toString();
    }

    private static Map<String, Object> named(List<Map<String, Object>> variables, String name) {
        for (Map<String, Object> variable : variables) {
            if (name.equals(variable.get("name"))) {
                return variable;
            }
        }
        return null;
    }

    private static int refOf(Map<String, Object> variable) {
        return ((Number) variable.get("variablesReference")).intValue();
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
        if (!(value instanceof ObjectReference)) {
            return value;
        }
        ObjectReference object = (ObjectReference) value;
        if (!REFERENCE_CLASS.equals(object.referenceType().name())) {
            return value;
        }
        Field field = object.referenceType().fieldByName("value");
        return field == null ? value : object.getValue(field);
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
        if (!BOXES.contains(type)) {
            return null;
        }
        Field field = object.referenceType().fieldByName("value");
        if (field == null) {
            return null;
        }
        Value inner = object.getValue(field);
        if (inner instanceof ArrayReference) {
            return null; // BigInteger keeps an int[]; not worth decoding
        }
        return String.valueOf(inner);
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
            if (BOXES.contains(object.referenceType().name())) {
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
