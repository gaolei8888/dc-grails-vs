package org.groovydap;

import static org.groovydap.Check.eq;
import static org.groovydap.Check.test;
import static org.groovydap.Check.that;
import static org.groovydap.Fakes.methods;
import static org.groovydap.Fakes.of;
import static org.groovydap.UnitChecks.intValue;
import static org.groovydap.UnitChecks.type;

import com.sun.jdi.ArrayReference;
import com.sun.jdi.ArrayType;
import com.sun.jdi.BooleanValue;
import com.sun.jdi.ByteValue;
import com.sun.jdi.CharValue;
import com.sun.jdi.ClassType;
import com.sun.jdi.DoubleValue;
import com.sun.jdi.Field;
import com.sun.jdi.FloatValue;
import com.sun.jdi.InconsistentDebugInfoException;
import com.sun.jdi.IntegerValue;
import com.sun.jdi.LocalVariable;
import com.sun.jdi.LongValue;
import com.sun.jdi.Method;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.ShortValue;
import com.sun.jdi.StackFrame;
import com.sun.jdi.StringReference;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Value;
import com.sun.jdi.VirtualMachine;

import org.groovydap.jdi.PathEvaluator;
import org.groovydap.jdi.ValueFactory;
import org.groovydap.jdi.Variables;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Variable modification (ValueFactory + Variables.set), against JDI fakes that keep
 * what is written to them.
 *
 * <p>Boxing a number needs {@code Integer.valueOf} run in the target, so unlike the
 * read-only checks these fakes <em>do</em> accept an invocation -- but only the one
 * the design allows, and every call is recorded and pinned: valueOf, on one of the
 * eight JDK box classes, one primitive argument, single-threaded. Anything else
 * (another class, another method, newInstance) still throws, as in every fake.
 */
final class WriteChecks {

    private WriteChecks() {
    }

    static void run() {
        System.out.println("write checks");
        factory();
        boxInvocations();
        writing();
    }

    // ------------------------------------------------------------ fake world

    /** mirrorOf: what the VM would hand back for a literal, as a typed fake. */
    private static Value mirror(Object o) {
        if (o instanceof String) {
            return of(StringReference.class, "\"" + o + "\"", methods(
                    "value", o, "type", type("java.lang.String"),
                    "referenceType", of(ReferenceType.class, "java.lang.String",
                            methods("name", "java.lang.String"))));
        }
        Class<? extends Value> iface;
        String kind;
        if (o instanceof Byte) { iface = ByteValue.class; kind = "byte"; }
        else if (o instanceof Short) { iface = ShortValue.class; kind = "short"; }
        else if (o instanceof Integer) { iface = IntegerValue.class; kind = "int"; }
        else if (o instanceof Long) { iface = LongValue.class; kind = "long"; }
        else if (o instanceof Float) { iface = FloatValue.class; kind = "float"; }
        else if (o instanceof Double) { iface = DoubleValue.class; kind = "double"; }
        else if (o instanceof Boolean) { iface = BooleanValue.class; kind = "boolean"; }
        else { iface = CharValue.class; kind = "char"; }
        return of(iface, String.valueOf(o), methods("type", type(kind)));
    }

    /** One invokeMethod the fake VM saw. */
    private static final class Invocation {
        final String type;
        final String method;
        final String signature;
        final List<?> args;
        final int options;

        Invocation(String type, String method, String signature, List<?> args, int options) {
            this.type = type;
            this.method = method;
            this.signature = signature;
            this.args = args;
            this.options = options;
        }

        @Override
        public String toString() {
            return type + "." + method + signature + " " + args + " options=" + options;
        }
    }

    private static final List<Invocation> INVOCATIONS =
            Collections.synchronizedList(new ArrayList<>());
    private static final Set<String> NEWINSTANCES = new HashSet<>();

    /** What Variables.set / ValueFactory.of did in the target: the boxed value's text. */
    private static ObjectReference boxed(String type, Object primitive) {
        Field value = of(Field.class, "value", methods("name", "value"));
        return of(ObjectReference.class, type + "(" + primitive + ")", methods(
                "type", type(type), "uniqueID", 99L, "getValue", primitive,
                "referenceType", of(ReferenceType.class, type, methods(
                        "name", type, "fieldByName", value))));
    }

    private static ClassType boxClass(String name) {
        return of(ClassType.class, name, methods(
                "name", name,
                "concreteMethodByName", (Function<Object[], Object>) a -> of(Method.class,
                        a[0] + String.valueOf(a[1]), methods("name", a[0], "signature", a[1])),
                "invokeMethod", (Function<Object[], Object>) a -> {
                    Method m = (Method) a[1];
                    List<?> args = (List<?>) a[2];
                    INVOCATIONS.add(new Invocation(name, m.name(), m.signature(), args,
                            (Integer) a[3]));
                    return boxed(name, args.get(0));
                }));
    }

    private static final VirtualMachine VM = of(VirtualMachine.class, "vm", methods(
            "mirrorOf", (Function<Object[], Object>) a -> mirror(a[0]),
            "classesByName", (Function<Object[], Object>) a ->
                    ((String) a[0]).startsWith("java.lang.")
                            ? Collections.singletonList(boxClass((String) a[0]))
                            : Collections.emptyList()));

    private static final ThreadReference THREAD = of(ThreadReference.class, "thread",
            methods("virtualMachine", VM, "uniqueID", 1L));

    private static String show(Value v) {
        return v == null ? "null" : v instanceof StringReference
                ? "String:" + ((StringReference) v).value() : v.type().name() + ":" + v;
    }

    private static StringReference str(String text) {
        return (StringReference) mirror(text);
    }

    // ------------------------------------------------------- ValueFactory.of

    private static String make(String declared, String text) {
        try {
            return show(ValueFactory.of(text, type(declared), THREAD));
        } catch (PathEvaluator.Unsupported e) {
            return "ERR " + e.getMessage();
        }
    }

    private static void factory() {
        String[][] table = {
            {"int", "100", "int:100"},
            {"int", "-5", "int:-5"},
            {"int", "  42  ", "int:42"},
            {"int", "2147483647", "int:2147483647"},
            {"int", "-2147483648", "int:-2147483648"},
            {"int", "2147483648", "ERR 2147483648 does not fit"},
            {"int", "3.5", "ERR 3.5 is not a whole number"},
            {"int", "abc", "ERR abc is not a whole number"},
            {"int", "y + 1", "ERR y + 1 is not a whole number"},
            {"byte", "127", "byte:127"},
            {"byte", "300", "ERR 300 does not fit"},
            {"short", "-32768", "short:-32768"},
            {"short", "40000", "ERR 40000 does not fit"},
            {"long", "9223372036854775807", "long:9223372036854775807"},
            {"long", "1.5", "ERR 1.5 is not a whole number"},
            {"float", "1.5", "float:1.5"},
            {"float", "2", "float:2.0"},
            {"float", "x", "ERR x is not a number"},
            {"double", "3", "double:3.0"},
            {"double", "-2.5e-3", "double:-0.0025"},
            {"boolean", "true", "boolean:true"},
            {"boolean", "false", "boolean:false"},
            {"boolean", "1", "ERR 1 is not true or false"},
            {"char", "'x'", "char:x"},
            {"char", "x", "char:x"},
            {"char", "'ab'", "ERR 'ab' is not a single character"},
            {"java.lang.String", "\"hi\"", "String:hi"},
            {"java.lang.String", "'hi'", "String:hi"},
            {"java.lang.String", "null", "null"},
            {"java.lang.Integer", "null", "null"},
            {"java.lang.Object", "\"str\"", "String:str"},
            {"java.util.List", "[1]", "ERR cannot make a java.util.List out of [1]: this sets numbers, "
                    + "strings, booleans and null, not objects -- those would have to be constructed "
                    + "in the application"},
            {"java.util.List", "null", "null"},
        };
        test("write: ValueFactory literal table (" + table.length + " cases: ok, range, garbage)", () -> {
            INVOCATIONS.clear();
            for (String[] row : table) {
                eq(row[2], make(row[0], row[1]), row[0] + " <- " + row[1]);
            }
            eq(0, INVOCATIONS.size(), "none of those needed the target: " + INVOCATIONS);
        });
    }

    // --------------------------------------------- what may run in the target

    private static final Set<String> BOX_CLASSES = new HashSet<>(Arrays.asList(
            "java.lang.Integer", "java.lang.Long", "java.lang.Short", "java.lang.Byte",
            "java.lang.Character", "java.lang.Float", "java.lang.Double", "java.lang.Boolean"));

    private static void boxInvocations() {
        test("write: the only thing ever run in the target is valueOf on the 8 JDK box classes", () -> {
            INVOCATIONS.clear();
            String[][] cases = {
                // a box-typed slot, and an untyped one that is read the way Groovy would
                {"java.lang.Integer", "5"}, {"java.lang.Long", "5"}, {"java.lang.Short", "5"},
                {"java.lang.Byte", "5"}, {"java.lang.Character", "'x'"}, {"java.lang.Float", "1.5"},
                {"java.lang.Double", "1.5"}, {"java.lang.Boolean", "true"},
                {"java.lang.Object", "7"}, {"java.lang.Object", "5000000000"},
                {"java.lang.Object", "1.5"}, {"java.lang.Object", "false"},
                // text that must not reach the target at all
                {"java.lang.Object", "foo()"}, {"java.lang.Object", "new Foo()"},
                {"java.lang.Object", "System.exit(1)"}, {"java.lang.Integer", "a + 1"},
                {"java.lang.Integer", "99999999999"}, {"java.lang.Object", "[1, 2]"},
                {"demo.Widget", "5"}, {"demo.Widget", "\"x\""}, {"java.util.List", "7"},
                {"java.math.BigDecimal", "5"}, {"java.lang.Number", "5"},
            };
            for (String[] c : cases) {
                make(c[0], c[1]);
            }
            Set<String> boxed = new HashSet<>();
            for (Invocation i : new ArrayList<>(INVOCATIONS)) {
                that(BOX_CLASSES.contains(i.type), "not a box class: " + i);
                eq("valueOf", i.method, "method of " + i);
                that(i.signature.matches("\\([IJSBCFDZ]\\)L" + i.type.replace('.', '/') + ";"),
                        "valueOf(primitive) of its own class only: " + i);
                eq(1, i.args.size(), "one argument: " + i);
                eq(com.sun.jdi.ObjectReference.INVOKE_SINGLE_THREADED, i.options,
                        "single-threaded: " + i);
                boxed.add(i.type);
            }
            eq(BOX_CLASSES, boxed, "each of the eight was exercised");
            // 8 box-typed + 4 Object-typed, plus 4 more: a slot of any other non-primitive,
            // non-String type (demo.Widget, List, BigDecimal, Number) is read as untyped, so
            // a number written to one is boxed first and the JVM then refuses the store.
            // Wasteful, and still only valueOf on a box class, which is what is pinned here.
            eq(8 + 4 + 4, INVOCATIONS.size(), "invocations: " + INVOCATIONS);
            eq(0, Fakes.INVOCATIONS.size(), "no other fake saw an invoke*/newInstance: "
                    + Fakes.INVOCATIONS);
        });
    }

    // ------------------------------------------------------------ the writes

    /** The fakes a write needs: a frame whose slots keep what is stored in them. */
    private static final class Fx {
        final Map<String, Object> cells = new LinkedHashMap<>();   // Value, or a RuntimeException to throw on read
        final List<LocalVariable> locals = new ArrayList<>();
        ObjectReference self;

        Fx local(String name, String typeName, Object value) {
            locals.add(of(LocalVariable.class, name, methods("name", name,
                    "typeName", typeName, "type", type(typeName))));
            cells.put(name, value);
            return this;
        }

        ThreadReference thread() {
            StackFrame frame = of(StackFrame.class, "frame", methods(
                    "thisObject", self,
                    "visibleVariables", locals,
                    "visibleVariableByName", (Function<Object[], Object>) a -> {
                        for (LocalVariable lv : locals) {
                            if (lv.name().equals(a[0])) {
                                return lv;
                            }
                        }
                        return null;
                    },
                    "getValue", (Function<Object[], Object>) a -> {
                        Object v = cells.get(((LocalVariable) a[0]).name());
                        if (v instanceof RuntimeException) {
                            throw (RuntimeException) v;
                        }
                        return v;
                    },
                    "setValue", (Function<Object[], Object>) a -> {
                        cells.put(((LocalVariable) a[0]).name(), a[1]);
                        return null;
                    }));
            return of(ThreadReference.class, "thread", methods(
                    "uniqueID", 1L, "virtualMachine", VM, "frame", frame));
        }

        /** An object whose fields live in this fx's cells under "owner.field". */
        ObjectReference object(String owner, Object... nameTypeValue) {
            Map<String, Field> fields = new LinkedHashMap<>();
            for (int i = 0; i < nameTypeValue.length; i += 3) {
                String n = (String) nameTypeValue[i];
                fields.put(n, of(Field.class, n, methods("name", n,
                        "typeName", nameTypeValue[i + 1], "type", type((String) nameTypeValue[i + 1]),
                        "isFinal", false, "isStatic", false)));
                cells.put(owner + "." + n, nameTypeValue[i + 2]);
            }
            ReferenceType rt = of(ReferenceType.class, owner, methods(
                    "name", owner,
                    "allFields", new ArrayList<>(fields.values()),
                    "fieldByName", (Function<Object[], Object>) a -> fields.get((String) a[0])));
            return of(ObjectReference.class, owner, methods(
                    "referenceType", rt, "type", type(owner), "uniqueID", 7L,
                    "virtualMachine", VM,
                    "getValue", (Function<Object[], Object>) a ->
                            cells.get(owner + "." + ((Field) a[0]).name()),
                    "getValues", (Function<Object[], Object>) a -> {
                        Map<Field, Value> out = new LinkedHashMap<>();
                        for (Object f : (List<?>) a[0]) {
                            out.put((Field) f, (Value) cells.get(owner + "." + ((Field) f).name()));
                        }
                        return out;
                    },
                    "setValue", (Function<Object[], Object>) a -> {
                        cells.put(owner + "." + ((Field) a[0]).name(), a[1]);
                        return null;
                    }));
        }

        ArrayReference array(String componentType, Value... elements) {
            String key = "array:" + componentType;
            for (int i = 0; i < elements.length; i++) {
                cells.put(key + i, elements[i]);
            }
            ArrayType rt = of(ArrayType.class, componentType + "[]", methods(
                    "name", componentType + "[]", "componentType", type(componentType)));
            return of(ArrayReference.class, componentType + "[]", methods(
                    "length", elements.length, "referenceType", rt,
                    "type", type(componentType + "[]"), "virtualMachine", VM,
                    "getValue", (Function<Object[], Object>) a -> cells.get(key + a[0]),
                    "setValue", (Function<Object[], Object>) a -> {
                        cells.put(key + a[0], a[1]);
                        return null;
                    }));
        }
    }

    private static String set(Variables vars, ThreadReference t, int handle, String name, String text) {
        try {
            Map<String, Object> out = vars.set(handle, name, text, t);
            return out.get("value") + " : " + out.get("type");
        } catch (PathEvaluator.Unsupported e) {
            return "ERR " + e.getMessage();
        }
    }

    private static void writing() {
        test("write: locals -- int, boolean, double (int widens), char, String, null", () -> {
            Fx fx = new Fx()
                    .local("a", "int", intValue(100))
                    .local("b", "boolean", mirror(false))
                    .local("d", "double", mirror(1.5))
                    .local("c", "char", mirror('a'))
                    .local("s", "java.lang.String", str("old"))
                    .local("n", "java.lang.String", str("not null"));
            ThreadReference t = fx.thread();
            Variables vars = new Variables();
            int h = vars.localsHandle(t, 0);
            eq("7 : int", set(vars, t, h, "a", "7"), "int");
            eq("true : boolean", set(vars, t, h, "b", "true"), "boolean");
            eq("3.0 : double", set(vars, t, h, "d", "3"), "double <- int literal");
            eq("z : char", set(vars, t, h, "c", "'z'"), "char");
            eq("\"new value\" : java.lang.String", set(vars, t, h, "s", "\"new value\""), "String");
            eq("null : java.lang.String", set(vars, t, h, "n", "null"), "null on a String");
            List<Map<String, Object>> pane = vars.children(h);
            eq("7", pane.get(0).get("value"), "pane shows the new int");
            eq("3.0", pane.get(2).get("value"), "pane shows the new double");
            eq("\"new value\"", pane.get(4).get("value"), "pane shows the new String");
            eq("null", pane.get(5).get("value"), "pane shows null");
        });

        test("write: refusals leave the old value (narrowing, range, garbage, non-object types)", () -> {
            Fx fx = new Fx()
                    .local("a", "int", intValue(100))
                    .local("by", "byte", mirror((byte) 1))
                    .local("s", "java.lang.String", str("keep"))
                    .local("w", "demo.Widget", null);
            ThreadReference t = fx.thread();
            Variables vars = new Variables();
            int h = vars.localsHandle(t, 0);
            eq("ERR 3.5 is not a whole number", set(vars, t, h, "a", "3.5"), "no silent narrowing");
            eq("ERR a + 1 is not a whole number", set(vars, t, h, "a", "a + 1"), "an expression");
            eq("ERR 300 does not fit", set(vars, t, h, "by", "300"), "range");
            that(set(vars, t, h, "w", "foo").startsWith("ERR cannot make a demo.Widget out of foo"),
                    "an object type is never constructed");
            eq("100", String.valueOf(fx.cells.get("a")), "a untouched");
            eq("1", String.valueOf(fx.cells.get("by")), "by untouched");
            eq("keep", ((StringReference) fx.cells.get("s")).value(), "s untouched");
        });

        test("write: a closure-captured local is changed inside its Reference box", () -> {
            Fx fx = new Fx();
            ObjectReference box = fx.object("groovy.lang.Reference", "value", "java.lang.Object", intValue(7));
            fx.local("sum", "groovy.lang.Reference", box);
            ThreadReference t = fx.thread();
            Variables vars = new Variables();
            int h = vars.localsHandle(t, 0);
            INVOCATIONS.clear();
            eq("7", vars.children(h).get(0).get("value"), "the pane shows it unwrapped");
            String result = set(vars, t, h, "sum", "8");
            that(result.contains("8"), "set through the box: " + result);
            that(String.valueOf(fx.cells.get("groovy.lang.Reference.value")).contains("Integer(8)"),
                    "the box content was replaced: " + fx.cells.get("groovy.lang.Reference.value"));
            that(fx.cells.get("sum") == box, "the slot still holds the same box");
        });

        test("write: a local not initialised yet is 'not writable at this point'", () -> {
            Fx fx = new Fx().local("i", "int", new InconsistentDebugInfoException())
                    .local("ok", "int", intValue(2));
            ThreadReference t = fx.thread();
            Variables vars = new Variables();
            int h = vars.localsHandle(t, 0);
            eq("ERR 'i' is not writable at this point (it is initialised later in this method)",
                    set(vars, t, h, "i", "1"), "unreadable");
            eq("9 : int", set(vars, t, h, "ok", "9"), "its neighbour is writable");
        });

        test("write: not writable -- this, unknown names, stale handles, no stopped thread", () -> {
            Fx fx = new Fx().local("a", "int", intValue(1));
            fx.self = fx.object("demo.Svc", "count", "int", intValue(3));
            ThreadReference t = fx.thread();
            Variables vars = new Variables();
            int h = vars.localsHandle(t, 0);
            eq("ERR this cannot be reassigned", set(vars, t, h, "this", "null"), "this");
            eq("ERR no local named nope here", set(vars, t, h, "nope", "2"), "unknown");
            eq("ERR nothing is stopped", set(vars, null, h, "a", "2"), "no thread");
            eq("ERR that variable belongs to an earlier stop and is gone",
                    set(vars, t, 9999, "a", "2"), "a handle that never existed");
            vars.reset();
            eq("ERR that variable belongs to an earlier stop and is gone",
                    set(vars, t, h, "a", "2"), "after reset()");
            eq("1", String.valueOf(fx.cells.get("a")), "a untouched");
        });

        test("write: instance fields; unknown fields are refused", () -> {
            Fx fx = new Fx();
            ObjectReference o = fx.object("demo.Order",
                    "n", "int", intValue(1),
                    "label", "java.lang.String", str("x"),
                    "ratio", "double", mirror(0.5));
            fx.local("o", "demo.Order", o);
            ThreadReference t = fx.thread();
            Variables vars = new Variables();
            Map<String, Object> ov = vars.children(vars.localsHandle(t, 0)).get(0);
            int oh = ((Number) ov.get("variablesReference")).intValue();
            eq("5 : int", set(vars, t, oh, "n", "5"), "int field");
            eq("\"y\" : java.lang.String", set(vars, t, oh, "label", "\"y\""), "String field");
            eq("2.0 : double", set(vars, t, oh, "ratio", "2"), "double field <- int");
            eq("null : java.lang.String", set(vars, t, oh, "label", "null"), "null field");
            eq("ERR demo.Order has no field zz", set(vars, t, oh, "zz", "1"), "unknown");
            eq("5", vars.children(oh).get(0).get("value"), "the pane re-reads the field");
        });

        test("write: array elements; a non-index is refused", () -> {
            Fx fx = new Fx();
            ArrayReference ints = fx.array("int", intValue(10), intValue(20), intValue(30));
            ArrayReference strs = fx.array("java.lang.String", str("a"), str("b"));
            fx.local("arr", "int[]", ints).local("names", "java.lang.String[]", strs);
            ThreadReference t = fx.thread();
            Variables vars = new Variables();
            List<Map<String, Object>> locals = vars.children(vars.localsHandle(t, 0));
            int ah = ((Number) locals.get(0).get("variablesReference")).intValue();
            int sh = ((Number) locals.get(1).get("variablesReference")).intValue();
            eq("99 : null", set(vars, t, ah, "[1]", "99").replace(" : int", " : null"), "int element");
            eq("\"z\"", set(vars, t, sh, "[0]", "\"z\"").split(" : ")[0], "String element");
            eq("null", set(vars, t, sh, "[1]", "null").split(" : ")[0], "null element");
            eq("ERR 3.5 is not a whole number", set(vars, t, ah, "[0]", "3.5"), "no narrowing");
            eq("ERR x is not an index", set(vars, t, ah, "x", "1"), "not an index");
            eq("99", String.valueOf(fx.cells.get("array:int1")), "cell written");
            eq("10", String.valueOf(fx.cells.get("array:int0")), "neighbour untouched");
            eq("99", vars.children(ah).get(1).get("value"), "the pane re-reads the element");
        });

        test("write: nothing but valueOf on a box class ran in the target", () -> {
            for (Invocation i : new ArrayList<>(INVOCATIONS)) {
                that(BOX_CLASSES.contains(i.type) && i.method.equals("valueOf"), "unexpected: " + i);
            }
            eq(0, Fakes.INVOCATIONS.size(), "invocations: " + Fakes.INVOCATIONS);
        });
    }
}
