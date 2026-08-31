package org.groovydap.jdi;

import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.ArrayReference;
import com.sun.jdi.Field;
import com.sun.jdi.LocalVariable;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StackFrame;
import com.sun.jdi.StringReference;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Value;

import java.util.ArrayList;
import java.util.Arrays;
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
                    out.add(describe(local.name(), values.get(local)));
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

        @Override
        List<Map<String, Object>> children() {
            List<Map<String, Object>> out = new ArrayList<>();
            try {
                ReferenceType type = object.referenceType();
                List<Field> fields = type.allFields();
                Map<Field, Value> values = object.getValues(fields);
                for (Field field : fields) {
                    if (isSynthetic(field.name())) {
                        continue;
                    }
                    out.add(describe(field.name(), values.get(field)));
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

    private static final int MAX_ARRAY_ELEMENTS = 200;
    private static final String REFERENCE_CLASS = "groovy.lang.Reference";

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
     * A Groovy local that the compiler introduced rather than the programmer.
     * {@code this$0} and {@code $callSiteArray} carry a dollar sign; the rest are
     * named outright.
     */
    static boolean isSynthetic(String name) {
        return name.indexOf('$') >= 0
                || name.equals("metaClass")
                || name.equals("__timeStamp");
    }

    private Map<String, Object> describe(String name, Value value) {
        Value effective = unwrapReference(value);
        Map<String, Object> variable = new LinkedHashMap<>();
        variable.put("name", name);
        variable.put("value", render(effective));
        variable.put("type", effective == null ? "null" : effective.type().name());
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
            return register(new ObjectFields((ObjectReference) value));
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
