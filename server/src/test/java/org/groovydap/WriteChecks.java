package org.groovydap;

import static org.groovydap.Check.eq;
import static org.groovydap.Check.test;
import static org.groovydap.Check.that;
import static org.groovydap.Fakes.methods;
import static org.groovydap.Fakes.of;
import static org.groovydap.UnitChecks.intValue;
import static org.groovydap.UnitChecks.local;
import static org.groovydap.UnitChecks.type;

import com.sun.jdi.ArrayReference;
import com.sun.jdi.BooleanValue;
import com.sun.jdi.ByteValue;
import com.sun.jdi.CharValue;
import com.sun.jdi.DoubleValue;
import com.sun.jdi.FloatValue;
import com.sun.jdi.IntegerValue;
import com.sun.jdi.LongValue;
import com.sun.jdi.ShortValue;
import com.sun.jdi.ClassNotLoadedException;
import com.sun.jdi.Field;
import com.sun.jdi.IncompatibleThreadStateException;
import com.sun.jdi.InconsistentDebugInfoException;
import com.sun.jdi.InvalidTypeException;
import com.sun.jdi.LocalVariable;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StackFrame;
import com.sun.jdi.StringReference;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Value;
import com.sun.jdi.VirtualMachine;

import org.groovydap.jdi.EvaluationException;
import org.groovydap.jdi.PathExpression;
import org.groovydap.jdi.ValueParser;
import org.groovydap.jdi.Variables;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Variable modification, against JDI fakes that keep what is written to them.
 * Every fake throws if anything tries to invoke a method or construct an object
 * (Fakes), so a pass also shows a write never runs code in the target.
 */
final class WriteChecks {

    private WriteChecks() {
    }

    static void run() {
        System.out.println("write checks");
        literals();
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
        // mirrorOf(int) must hand back an IntegerValue and so on: the proxy has to
        // implement the interface the real method returns.
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

    private static final VirtualMachine VM = of(VirtualMachine.class, "vm",
            methods("mirrorOf", (Function<Object[], Object>) a -> mirror(a[0])));

    private static String show(Value v) {
        return v == null ? "null" : v instanceof StringReference
                ? "String:" + ((StringReference) v).value() : v.type().name() + ":" + v;
    }

    private static StringReference str(String text) {
        return (StringReference) mirror(text);
    }

    /** Something a fake's setValue can be told to refuse with, keyed by variable name. */
    private static final class Fx {
        final Map<String, Object> cells = new LinkedHashMap<>();   // Value, or a Throwable to throw on read
        final Map<String, Throwable> refuseStore = new LinkedHashMap<>();
        final List<LocalVariable> locals = new ArrayList<>();
        ObjectReference self;
        boolean stale;

        Fx local(String name, String type, Object value) {
            locals.add(UnitChecks.local(name, type));
            cells.put(name, value);
            return this;
        }

        private LocalVariable find(Object lv) {
            return (LocalVariable) lv;
        }

        ThreadReference thread() {
            StackFrame frame = of(StackFrame.class, "frame", methods(
                    "thisObject", self,
                    "visibleVariables", locals,
                    "getValue", (Function<Object[], Object>) a -> {
                        Object v = cells.get(find(a[0]).name());
                        if (v instanceof RuntimeException) {
                            throw (RuntimeException) v;
                        }
                        return v;
                    },
                    "setValue", (Function<Object[], Object>) a -> {
                        store(find(a[0]).name(), a[1]);
                        return null;
                    }));
            return of(ThreadReference.class, "thread", methods(
                    "uniqueID", 1L,
                    "virtualMachine", VM,
                    "frame", (Function<Object[], Object>) a -> {
                        if (stale) {
                            return Sneaky.<RuntimeException>sneaky(
                                    new IncompatibleThreadStateException("not suspended"));
                        }
                        return frame;
                    }));
        }

        void store(String name, Object value) {
            Throwable refuse = refuseStore.get(name);
            if (refuse != null) {
                Sneaky.<RuntimeException>sneaky(refuse);
            }
            cells.put(name, value);
        }

        /** An object whose fields live in this fx's cells under "owner.field". */
        ObjectReference object(String owner, Object... nameTypeFinalValue) {
            Map<String, Field> fields = new LinkedHashMap<>();
            for (int i = 0; i < nameTypeFinalValue.length; i += 4) {
                String n = (String) nameTypeFinalValue[i];
                fields.put(n, of(Field.class, n, methods("name", n,
                        "typeName", nameTypeFinalValue[i + 1],
                        "isFinal", nameTypeFinalValue[i + 2], "isStatic", false)));
                cells.put(owner + "." + n, nameTypeFinalValue[i + 3]);
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
                        store(owner + "." + ((Field) a[0]).name(), a[1]);
                        return null;
                    }));
        }

        ArrayReference array(String typeName, Value... elements) {
            String key = "array:" + typeName;
            for (int i = 0; i < elements.length; i++) {
                cells.put(key + i, elements[i]);
            }
            ReferenceType rt = of(ReferenceType.class, typeName, methods("name", typeName));
            return of(ArrayReference.class, typeName, methods(
                    "length", elements.length, "referenceType", rt, "type", type(typeName),
                    "virtualMachine", VM,
                    "getValue", (Function<Object[], Object>) a -> cells.get(key + a[0]),
                    "setValue", (Function<Object[], Object>) a -> {
                        store(key + a[0], a[1]);
                        return null;
                    }));
        }
    }

    /** Throws a checked exception through a Function, as a real JDI call would. */
    private static final class Sneaky {
        @SuppressWarnings("unchecked")
        static <E extends Throwable> Object sneaky(Throwable t) throws E {
            throw (E) t;
        }
    }

    // ------------------------------------------------------------ the parser

    private static void literals() {
        String only = "ERR " + ValueParser.LITERAL_ONLY;
        String[][] table = {
            {"int", "100", "int:100"},
            {"int", "-5", "int:-5"},
            {"int", "+7", "int:7"},
            {"int", "  42  ", "int:42"},
            {"int", "0x1F", "int:31"},
            {"int", "-0x10", "int:-16"},
            {"int", "2147483647", "int:2147483647"},
            {"int", "-2147483648", "int:-2147483648"},
            {"int", "2147483648", "ERR 2147483648 does not fit in int"},
            {"int", "-2147483649", "ERR -2147483649 does not fit in int"},
            {"int", "0x1FFFFFFFF", "ERR 0x1FFFFFFFF does not fit in int"},
            {"int", "3.5", "ERR 3.5 is not an int literal"},
            {"int", "1e3", "ERR 1e3 is not an int literal"},
            {"int", "3L", "ERR 3L is not an int literal"},
            {"int", "true", "ERR true is not an int literal"},
            {"int", "'a'", "ERR 'a' is not an int literal"},
            {"int", "\"7\"", "ERR \"7\" is not an int literal"},
            {"int", "null", "ERR cannot assign null to int"},
            {"int", "abc", only},
            {"int", "y + 1", only},
            {"int", "foo()", only},
            {"int", "new Foo()", only},
            {"int", "--5", only},
            {"int", "", "ERR the value is empty"},
            {"int", "   ", "ERR the value is empty"},
            {"byte", "127", "byte:127"},
            {"byte", "-128", "byte:-128"},
            {"byte", "300", "ERR 300 does not fit in byte"},
            {"byte", "128", "ERR 128 does not fit in byte"},
            {"byte", "-129", "ERR -129 does not fit in byte"},
            {"short", "32767", "short:32767"},
            {"short", "-32768", "short:-32768"},
            {"short", "40000", "ERR 40000 does not fit in short"},
            {"long", "5", "long:5"},
            {"long", "5L", "long:5"},
            {"long", "5l", "long:5"},
            {"long", "-9223372036854775808L", "long:-9223372036854775808"},
            {"long", "9223372036854775807", "long:9223372036854775807"},
            {"long", "9223372036854775808L", "ERR 9223372036854775808L does not fit in long"},
            {"long", "0xFFL", "long:255"},
            {"long", "1.5", "ERR 1.5 is not a long literal"},
            {"float", "1.5", "float:1.5"},
            {"float", "1.5f", "float:1.5"},
            {"float", "1.5F", "float:1.5"},
            {"float", "2", "float:2.0"},
            {"float", "-2e2", "float:-200.0"},
            {"float", ".5", "float:0.5"},
            {"float", "16777217", "ERR 16777217 does not fit exactly in float"},
            {"float", "1.5d", "ERR 1.5d is not a float literal"},
            {"float", "1e50", "ERR 1e50 does not fit in float"},
            {"float", "1e-60", "ERR 1e-60 is too small for float"},
            {"float", "true", "ERR true is not a float literal"},
            {"float", "x", only},
            {"double", "3", "double:3.0"},
            {"double", "3.5", "double:3.5"},
            {"double", "1.5d", "double:1.5"},
            {"double", "-2.5e-3", "double:-0.0025"},
            {"double", "5.", "double:5.0"},
            {"double", "0x10", "double:16.0"},
            {"double", "1.5f", "ERR 1.5f is not a double literal"},
            {"double", "1e999", "ERR 1e999 does not fit in double"},
            {"double", "1e-999", "ERR 1e-999 is too small for double"},
            {"double", "NaN", only},
            {"double", "1,5", only},
            {"boolean", "true", "boolean:true"},
            {"boolean", "false", "boolean:false"},
            {"boolean", "1", "ERR 1 is not a boolean literal"},
            {"boolean", "True", only},
            {"boolean", "null", "ERR cannot assign null to boolean"},
            {"char", "'x'", "char:x"},
            {"char", "'\\n'", "char:\n"},
            {"char", "'\\''", "char:'"},
            {"char", "'\\u0041'", "char:A"},
            {"char", "7", "char:7"},
            {"char", "#", "char:#"},
            {"char", "'ab'", "ERR 'ab' is not a char literal (one character, like 'x')"},
            {"char", "''", "ERR '' is not a char literal (one character, like 'x')"},
            {"char", "65", "ERR 65 is not a char literal"},
            {"char", "y", only},
            {"char", "\"x\"", "ERR \"x\" is not a char literal"},
            {"char", "null", "ERR cannot assign null to char"},
            {"java.lang.String", "\"hi\"", "String:hi"},
            {"java.lang.String", "'hi'", "String:hi"},
            {"java.lang.String", "\"\"", "String:"},
            {"java.lang.String", "\"a\\\"b\\\\c\\nd\\te\"", "String:a\"b\\c\nd\te"},
            {"java.lang.String", "'it\\'s'", "String:it's"},
            {"java.lang.String", "\"\\u00e9\"", "String:\u00e9"},
            {"java.lang.String", "\"has 'single' inside\"", "String:has 'single' inside"},
            {"java.lang.String", "null", "null"},
            {"java.lang.String", "\"abc", "ERR unterminated literal, or an unescaped quote inside it: \"abc"},
            {"java.lang.String", "\"a\" + \"b\"",
                    "ERR unterminated literal, or an unescaped quote inside it: \"a\" + \"b\""},
            {"java.lang.String", "\"bad\\q\"", "ERR unknown escape \\q in \"bad\\q\""},
            {"java.lang.String", "\"trailing\\", "ERR unterminated literal, or an unescaped quote inside it: \"trailing\\"},
            {"java.lang.String", "7", "ERR 's' is a java.lang.String; write the value in quotes, e.g. \"7\""},
            {"java.lang.String", "true", "ERR 's' is a java.lang.String; write the value in quotes, e.g. \"true\""},
            {"java.lang.String", "y + 1", only},
            {"java.lang.String", "other", only},
            {"java.lang.Object", "\"str\"", "String:str"},
            {"java.lang.Integer", "null", "null"},
            {"java.lang.Integer", "5", "ERR 's' holds a java.lang.Integer; " + ValueParser.NEEDS_OBJECT},
            {"java.lang.Object", "7", "ERR 's' holds a java.lang.Object; " + ValueParser.NEEDS_OBJECT},
            {"java.lang.Object", "true", "ERR 's' holds a java.lang.Object; " + ValueParser.NEEDS_OBJECT},
            {"java.util.List", "[1]", only},
        };
        test("write: literal parser table (" + table.length + " cases: ok, range, narrowing, garbage)", () -> {
            for (String[] row : table) {
                String got;
                try {
                    got = show(ValueParser.parse(VM, "s", row[0], row[0], row[1]));
                } catch (EvaluationException e) {
                    got = "ERR " + e.getMessage();
                }
                eq(row[2], got, row[0] + " <- " + row[1]);
            }
        });
    }

    // ------------------------------------------------------------ the writes

    private static String set(Variables vars, int handle, String name, String text) {
        try {
            Map<String, Object> out = vars.setVariable(handle, name, text);
            return out.get("value") + " : " + out.get("type") + " : " + out.get("variablesReference");
        } catch (EvaluationException e) {
            return "ERR " + e.getMessage();
        }
    }

    private static String setPath(Variables vars, ThreadReference t, String path, String text) {
        try {
            Map<String, Object> out = vars.setExpression(t, 0, null, path, text);
            return out.get("value") + " : " + out.get("type") + " : " + out.get("variablesReference");
        } catch (EvaluationException e) {
            return "ERR " + e.getMessage();
        }
    }

    private static void writing() {
        String needs = "changing it would need to create an object in the application, "
                + "which the debugger does not do";

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
            eq("7 : int : 0", set(vars, h, "a", "7"), "int");
            eq("true : boolean : 0", set(vars, h, "b", "true"), "boolean");
            eq("3.0 : double : 0", set(vars, h, "d", "3"), "double <- int literal");
            eq("z : char : 0", set(vars, h, "c", "'z'"), "char");
            eq("\"new\tvalue\" : java.lang.String : 0",
                    set(vars, h, "s", "\"new\\tvalue\""), "String");
            eq("null : java.lang.String : 0", set(vars, h, "n", "null"), "null on a String");
            // read back through the pane, not just the response
            List<Map<String, Object>> pane = vars.children(h);
            eq("7", pane.get(0).get("value"), "pane shows the new int");
            eq("3.0", pane.get(2).get("value"), "pane shows the new double");
            eq("\"new\tvalue\"", pane.get(4).get("value"), "pane shows the new String");
            eq("null", pane.get(5).get("value"), "pane shows null");
        });

        test("write: refusals leave the old value (narrowing, range, boxing, garbage, null on primitive)", () -> {
            ObjectReference boxed = new Fx().object("java.lang.Integer", "value", "int", false, intValue(5));
            Fx fx = new Fx()
                    .local("a", "int", intValue(100))
                    .local("by", "byte", mirror((byte) 1))
                    .local("i", "java.lang.Integer", boxed)
                    .local("s", "java.lang.String", str("keep"));
            ThreadReference t = fx.thread();
            Variables vars = new Variables();
            int h = vars.localsHandle(t, 0);
            eq("ERR 3.5 is not an int literal", set(vars, h, "a", "3.5"), "no silent narrowing");
            eq("ERR cannot assign null to int", set(vars, h, "a", "null"), "null on int");
            eq("ERR " + ValueParser.LITERAL_ONLY, set(vars, h, "a", "a + 1"), "an expression");
            eq("ERR 300 does not fit in byte", set(vars, h, "by", "300"), "range");
            eq("ERR 'i' holds a java.lang.Integer; " + needs, set(vars, h, "i", "5"), "Integer <- 5");
            eq("ERR 's' is a java.lang.String; write the value in quotes, e.g. \"7\"",
                    set(vars, h, "s", "7"), "String <- 7");
            eq("100", String.valueOf(fx.cells.get("a")), "a untouched");
            eq("1", String.valueOf(fx.cells.get("by")), "by untouched");
            eq("keep", ((StringReference) fx.cells.get("s")).value(), "s untouched");
            that(fx.cells.get("i") == boxed, "the Integer object is untouched");
        });

        test("write: a closure-captured local (groovy.lang.Reference) is refused, never mutated", () -> {
            Fx fx = new Fx();
            ObjectReference box = fx.object("groovy.lang.Reference", "value", "java.lang.Object", false, intValue(7));
            fx.local("sum", "groovy.lang.Reference", box);
            Variables vars = new Variables();
            ThreadReference t = fx.thread();
            int h = vars.localsHandle(t, 0);
            eq("7", vars.children(h).get(0).get("value"), "the pane shows it unwrapped");
            String refused = "ERR 'sum' is captured by a closure (groovy.lang.Reference); " + needs;
            eq(refused, set(vars, h, "sum", "8"), "setVariable");
            eq(refused, set(vars, h, "sum", "null"), "even null");
            eq(refused, setPath(vars, t, "sum", "8"), "setExpression");
            eq("7", String.valueOf(fx.cells.get("groovy.lang.Reference.value")), "box content untouched");
        });

        test("write: a local not initialised yet is 'not writable at this point'", () -> {
            Fx fx = new Fx().local("i", "int", new InconsistentDebugInfoException())
                    .local("ok", "int", intValue(2));
            Variables vars = new Variables();
            int h = vars.localsHandle(fx.thread(), 0);
            eq("ERR 'i' is not writable at this point (it is initialised later in this method)",
                    set(vars, h, "i", "1"), "unreadable");
            eq("9 : int : 0", set(vars, h, "ok", "9"), "its neighbour is writable");
        });

        test("write: not writable -- this, synthetic names, unknown names, stale frames, stale handles", () -> {
            Fx fx = new Fx().local("a", "int", intValue(1)).local("$callSiteArray", "int", intValue(1));
            fx.self = fx.object("demo.Svc", "count", "int", false, intValue(3));
            ThreadReference t = fx.thread();
            Variables vars = new Variables();
            int h = vars.localsHandle(t, 0);
            eq("ERR 'this' cannot be changed", set(vars, h, "this", "null"), "this");
            eq("ERR '$callSiteArray' cannot be changed", set(vars, h, "$callSiteArray", "2"), "synthetic");
            eq("ERR 'nope' is not a variable of this frame", set(vars, h, "nope", "2"), "unknown");
            eq("ERR no such variable list (the program has resumed since it was shown)",
                    set(vars, 9999, "a", "2"), "a handle that was reset");
            vars.reset();
            eq("ERR no such variable list (the program has resumed since it was shown)",
                    set(vars, h, "a", "2"), "after reset()");
            int h2 = vars.localsHandle(t, 0);
            fx.stale = true;
            String stale = set(vars, h2, "a", "2");
            that(stale.startsWith("ERR cannot change 'a': IncompatibleThreadStateException"), stale);
            eq("1", String.valueOf(fx.cells.get("a")), "a untouched");
        });

        test("write: instance fields; final is refused; synthetic and unknown fields are not shown", () -> {
            Fx fx = new Fx();
            ObjectReference o = fx.object("demo.Order",
                    "n", "int", false, intValue(1),
                    "label", "java.lang.String", false, str("x"),
                    "ratio", "double", false, mirror(0.5),
                    "k", "int", true, intValue(9),
                    "$hidden", "int", false, intValue(0));
            fx.local("o", "demo.Order", o);
            ThreadReference t = fx.thread();
            Variables vars = new Variables();
            Map<String, Object> ov = vars.children(vars.localsHandle(t, 0)).get(0);
            int oh = ((Number) ov.get("variablesReference")).intValue();
            eq("5 : int : 0", set(vars, oh, "n", "5"), "int field");
            eq("\"y\" : java.lang.String : 0", set(vars, oh, "label", "\"y\""), "String field");
            eq("2.0 : double : 0", set(vars, oh, "ratio", "2"), "double field <- int");
            eq("null : java.lang.String : 0", set(vars, oh, "label", "null"), "null field");
            eq("ERR 'k' is final", set(vars, oh, "k", "1"), "final");
            eq("ERR 'k' is final", setPath(vars, t, "o.k", "1"), "final via a path");
            eq("ERR '$hidden' is not a field the debugger shows", set(vars, oh, "$hidden", "1"), "synthetic");
            eq("ERR 'zz' is not a field the debugger shows", set(vars, oh, "zz", "1"), "unknown");
            eq("9", String.valueOf(fx.cells.get("demo.Order.k")), "final untouched");
            eq("6 : int : 0", setPath(vars, t, "o.n", "6"), "setExpression o.n");
            eq("6", vars.children(oh).get(0).get("value"), "the pane re-reads the field");
        });

        test("write: array elements; past the shown 200 and non-elements are refused", () -> {
            Fx fx = new Fx();
            ArrayReference ints = fx.array("int[]", intValue(10), intValue(20), intValue(30));
            ArrayReference strs = fx.array("java.lang.String[]", str("a"), str("b"));
            ArrayReference objs = fx.array("java.lang.Object[]", str("a"), str("b"));
            fx.local("arr", "int[]", ints).local("names", "java.lang.String[]", strs)
                    .local("objs", "java.lang.Object[]", objs);
            ThreadReference t = fx.thread();
            Variables vars = new Variables();
            List<Map<String, Object>> locals = vars.children(vars.localsHandle(t, 0));
            int ah = ((Number) locals.get(0).get("variablesReference")).intValue();
            int sh = ((Number) locals.get(1).get("variablesReference")).intValue();
            int oh = ((Number) locals.get(2).get("variablesReference")).intValue();
            eq("99 : int : 0", set(vars, ah, "[1]", "99"), "int element");
            eq("\"z\" : java.lang.String : 0", set(vars, sh, "[0]", "\"z\""), "String element");
            eq("\"o\" : java.lang.String : 0", set(vars, oh, "[0]", "\"o\""), "Object[] <- String");
            eq("null : java.lang.String : 0", set(vars, sh, "[1]", "null"), "null element");
            eq("ERR '[1]' holds a java.lang.String; " + needs, set(vars, oh, "[1]", "5"),
                    "Object[] <- 5 would need a box");
            eq("ERR 3.5 is not an int literal", set(vars, ah, "[0]", "3.5"), "no narrowing");
            eq("ERR '[3]' is not an element the debugger shows", set(vars, ah, "[3]", "1"), "past the end");
            eq("ERR 'x' is not an element the debugger shows", set(vars, ah, "x", "1"), "not an index");
            eq("ERR '[99999999999]' is not an element the debugger shows",
                    set(vars, ah, "[99999999999]", "1"), "absurd index");
            eq("99", String.valueOf(fx.cells.get("array:int[]1")), "cell written");
            eq("10", String.valueOf(fx.cells.get("array:int[]0")), "neighbour untouched");
            eq("77 : int : 0", setPath(vars, t, "arr[2]", "77"), "setExpression arr[2]");
            eq("77", vars.children(ah).get(2).get("value"), "the pane re-reads the element");
        });

        test("write: setExpression resolves like evaluate, and refuses what is not a path or not storable", () -> {
            Fx fx = new Fx().local("a", "int", intValue(1)).local("m", "java.util.HashMap", null);
            fx.self = fx.object("demo.Svc", "count", "int", false, intValue(3));
            ThreadReference t = fx.thread();
            Variables vars = new Variables();
            eq("5 : int : 0", setPath(vars, t, "a", "5"), "a local");
            eq("4 : int : 0", setPath(vars, t, "count", "4"), "a field of this, by bare name");
            eq("8 : int : 0", setPath(vars, t, "this.count", "8"), "this.count");
            eq("ERR " + PathExpression.READ_ONLY, setPath(vars, t, "a + 1", "5"), "an expression");
            eq("ERR " + PathExpression.READ_ONLY, setPath(vars, t, "foo()", "5"), "a call");
            eq("ERR 'nope' is not defined in this frame", setPath(vars, t, "nope", "5"), "undefined");
            eq("ERR cannot read '.x' of 'm': it is null", setPath(vars, t, "m.x", "5"), "null deref");
            eq("ERR " + ValueParser.LITERAL_ONLY, setPath(vars, t, "a", "a + 1"), "an expression as the value");
            eq("ERR the value is empty", setPath(vars, t, "a", ""), "empty value");
            eq("5", String.valueOf(fx.cells.get("a")), "a is what the last good write left");
        });

        test("write: JDI's own refusals become messages (type not assignable, class not loaded)", () -> {
            Fx fx = new Fx().local("n", "java.lang.Integer", null)
                    .local("w", "demo.Widget", null);
            fx.refuseStore.put("n", new InvalidTypeException("not assignable"));
            fx.refuseStore.put("w", new ClassNotLoadedException("demo.Widget"));
            Variables vars = new Variables();
            int h = vars.localsHandle(fx.thread(), 0);
            eq("ERR cannot assign \"x\" to 'n' (a java.lang.Integer)", set(vars, h, "n", "\"x\""),
                    "InvalidTypeException");
            eq("ERR the type of 'w' (demo.Widget) is not loaded in the application yet",
                    set(vars, h, "w", "\"x\""), "ClassNotLoadedException");
        });

        test("write: nothing ran in the target -- no fake saw invoke*/newInstance", () -> {
            eq(0, Fakes.INVOCATIONS.size(), "invocations: " + Fakes.INVOCATIONS);
        });
    }
}
