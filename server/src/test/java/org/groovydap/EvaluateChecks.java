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
import com.sun.jdi.Field;
import com.sun.jdi.InconsistentDebugInfoException;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StackFrame;
import com.sun.jdi.StringReference;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Value;

import org.groovydap.jdi.EvaluationException;
import org.groovydap.jdi.PathExpression;
import org.groovydap.jdi.Variables;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Expression evaluation, against JDI fakes. Every fake throws if anything tries to
 * invoke a method, so a pass also shows the evaluator only ever reads.
 */
final class EvaluateChecks {

    private EvaluateChecks() {
    }

    static void run() {
        System.out.println("evaluate checks");
        grammar();
        evaluation();
    }

    // ---------------------------------------------------------------- grammar

    /** Expression -> the steps it parses to, or the error text. */
    private static String parse(String expression) {
        try {
            StringBuilder sb = new StringBuilder();
            for (PathExpression.Step s : PathExpression.parse(expression)) {
                sb.append(s);
            }
            return sb.toString();
        } catch (EvaluationException e) {
            return "ERROR " + e.getMessage();
        }
    }

    private static void grammar() {
        String no = "ERROR " + PathExpression.READ_ONLY;
        String[][] table = {
            {"a", ".a"},
            {"  a  ", ".a"},
            {"this", ".this"},
            {"this.x", ".this.x"},
            {"a.b.c", ".a.b.c"},
            {"a . b", ".a.b"},
            {"list[0]", ".list[0]"},
            {"arr[ 12 ]", ".arr[12]"},
            {"map['k']", ".map['k']"},
            {"map[\"key\"]", ".map['key']"},
            {"map['a b']", ".map['a b']"},
            {"map.key", ".map.key"},
            {"a.b[1].c['d'][2]", ".a.b[1].c['d'][2]"},
            {"$x._y", ".$x._y"},
            {"", "ERROR the expression is empty"},
            {"   ", "ERROR the expression is empty"},
            {"a+1", no},
            {"a + 1", no},
            {"foo()", no},
            {"a.foo(1)", no},
            {"a = 1", no},
            {"a == b", no},
            {"1", no},
            {"\"text\"", no},
            {"new Foo()", no},
            {"a ? b : c", no},
            {"(String) a", no},
            {"a as String", no},
            {"a.", no},
            {".a", no},
            {"a..b", no},
            {"a[", no},
            {"a[0", no},
            {"a]", no},
            {"a[]", no},
            {"a['k", no},
            {"a['k'", no},
            {"a[-1]", no},
            {"a[1+1]", no},
            {"a[b]", no},
            {"a['it\\'s']", no},
            {"a[99999999999]", no},
            {"a?.b", no},
            {"a*.b", no},
            {"a b", no},
        };
        test("evaluate: grammar accepts variable paths, rejects everything else (" + table.length
                + " cases)", () -> {
            for (String[] row : table) {
                eq(row[1], parse(row[0]), "parse(" + row[0] + ")");
            }
        });
    }

    // ------------------------------------------------------------------ fakes

    private static ReferenceType refType(String name, Map<String, Field> fields) {
        return of(ReferenceType.class, name, methods(
                "name", name,
                "fieldByName", (Function<Object[], Object>) a -> fields.get((String) a[0]),
                "allFields", new ArrayList<>(fields.values())));
    }

    private static Field field(String name, String typeName) {
        return of(Field.class, name, methods("name", name, "typeName", typeName, "isStatic", false));
    }

    private static long ids = 100;

    /** An object whose fields read from {@code values}, in order. */
    private static ObjectReference obj(String typeName, Map<String, Value> values) {
        Map<String, Field> fields = new LinkedHashMap<>();
        values.keySet().forEach(n -> fields.put(n, field(n, "java.lang.Object")));
        ReferenceType rt = refType(typeName, fields);
        return of(ObjectReference.class, typeName, methods(
                "referenceType", rt,
                "type", type(typeName),
                "uniqueID", ids++,
                "getValue", (Function<Object[], Object>) a -> values.get(((Field) a[0]).name()),
                "getValues", (Function<Object[], Object>) a -> {
                    Map<Field, Value> out = new LinkedHashMap<>();
                    for (Object f : (List<?>) a[0]) {
                        out.put((Field) f, values.get(((Field) f).name()));
                    }
                    return out;
                }));
    }

    private static Map<String, Value> fields(Object... kv) {
        Map<String, Value> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], (Value) kv[i + 1]);
        }
        return m;
    }

    private static StringReference str(String text) {
        ReferenceType rt = refType("java.lang.String", new LinkedHashMap<>());
        return of(StringReference.class, "\"" + text + "\"", methods(
                "value", text, "referenceType", rt, "type", type("java.lang.String")));
    }

    private static ArrayReference array(String typeName, Value... elements) {
        ReferenceType rt = refType(typeName, new LinkedHashMap<>());
        return of(ArrayReference.class, typeName, methods(
                "length", elements.length,
                "getValue", (Function<Object[], Object>) a -> elements[(Integer) a[0]],
                "getValues", Arrays.asList(elements),
                "referenceType", rt,
                "type", type(typeName)));
    }

    private static ObjectReference arrayList(Value... elements) {
        return obj("java.util.ArrayList", fields(
                "elementData", array("java.lang.Object[]", elements),
                "size", intValue(elements.length)));
    }

    /** A HashMap in its real shape: a table of nodes with key / value / next. */
    private static ObjectReference hashMap(Object... keyValues) {
        List<Value> nodes = new ArrayList<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            nodes.add(obj("java.util.HashMap$Node", fields(
                    "key", (Value) keyValues[i], "value", (Value) keyValues[i + 1], "next", null)));
        }
        return obj("java.util.HashMap", fields(
                "table", array("java.util.HashMap$Node[]", nodes.toArray(new Value[0])),
                "size", intValue(nodes.size())));
    }

    private static ThreadReference thread(ObjectReference self, Map<String, Object> locals) {
        Map<com.sun.jdi.LocalVariable, Object> byVar = new LinkedHashMap<>();
        locals.forEach((n, v) -> byVar.put(local(n, "x"), v));
        StackFrame frame = of(StackFrame.class, "frame", methods(
                "thisObject", self,
                "visibleVariables", new ArrayList<>(byVar.keySet()),
                "getValue", (Function<Object[], Object>) a -> {
                    Object v = byVar.get(a[0]);
                    if (v instanceof RuntimeException) {
                        throw (RuntimeException) v;
                    }
                    return v;
                }));
        return of(ThreadReference.class, "thread", methods("frame", frame, "uniqueID", 1L));
    }

    private static Map<String, Object> locals(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static Map<String, Object> eval(ThreadReference t, String expression) {
        return new Variables().evaluate(t, 0, null, expression);
    }

    private static String fail(ThreadReference t, Supplier<ObjectReference> scope, String expression) {
        try {
            Map<String, Object> out = new Variables().evaluate(t, 0, scope, expression);
            return "NO ERROR: " + out;
        } catch (EvaluationException e) {
            return e.getMessage();
        }
    }

    private static String fail(ThreadReference t, String expression) {
        return fail(t, null, expression);
    }

    /** Throws a checked exception through a Function, as a real JDI call would. */
    @SuppressWarnings("unchecked")
    private static <E extends Throwable> Object sneaky(Throwable t) throws E {
        throw (E) t;
    }

    // ------------------------------------------------------------- evaluation

    private static void evaluation() {
        test("evaluate: a primitive local, a string, shaped like the variables pane", () -> {
            ThreadReference t = thread(null, locals("a", intValue(100), "s", str("hi")));
            Map<String, Object> a = eval(t, "a");
            eq("100", a.get("result"), "result");
            eq("int", a.get("type"), "type");
            eq(0, a.get("variablesReference"), "a primitive has nothing to expand");
            Map<String, Object> s = eval(t, "s");
            eq("\"hi\"", s.get("result"), "strings are quoted like the pane");
            eq("java.lang.String", s.get("type"), "string type");
            eq(0, s.get("variablesReference"), "a string is a leaf");
        });

        test("evaluate: object field paths, and the result expands in the pane", () -> {
            ObjectReference c = obj("demo.C", fields("n", intValue(7), "tag", str("t")));
            ObjectReference b = obj("demo.B", fields("c", c));
            ObjectReference a = obj("demo.A", fields("b", b));
            ThreadReference t = thread(null, locals("a", a));
            eq("7", eval(t, "a.b.c.n").get("result"), "a.b.c.n");
            eq("\"t\"", eval(t, "a . b . c . tag").get("result"), "spaces allowed");
            Map<String, Object> deep = eval(t, "a.b");
            eq("demo.B", deep.get("type"), "type of an object");
            that(((Number) deep.get("variablesReference")).intValue() > 0,
                    "an object result must be expandable: " + deep);
            that(deep.get("result").toString().startsWith("demo.B (id="), "object summary: " + deep);
        });

        test("evaluate: this, and this-fields when no local has the name; a local wins", () -> {
            ObjectReference self = obj("demo.Svc", fields("count", intValue(3), "x", intValue(1)));
            ThreadReference t = thread(self, locals("x", intValue(99)));
            eq("99", eval(t, "x").get("result"), "local shadows the field");
            eq("3", eval(t, "count").get("result"), "field of this");
            eq("3", eval(t, "this.count").get("result"), "explicit this");
            eq("demo.Svc", eval(t, "this").get("type"), "this itself");
            eq("1", eval(t, "this.x").get("result"), "this.x reaches the field the local hides");
        });

        test("evaluate: arrays, ArrayList and maps by index and key", () -> {
            ObjectReference list = arrayList(str("a"), str("b"));
            ArrayReference arr = array("int[]", intValue(10), intValue(20), intValue(30));
            ObjectReference names = hashMap(str("k"), str("v"), str("other"), intValue(2));
            ObjectReference byInt = hashMap(intValue(5), str("five"));
            ThreadReference t = thread(null, locals("list", list, "arr", arr, "names", names,
                    "byInt", byInt));
            eq("20", eval(t, "arr[1]").get("result"), "arr[1]");
            eq("\"b\"", eval(t, "list[1]").get("result"), "list[1]");
            eq("\"v\"", eval(t, "names['k']").get("result"), "names['k']");
            eq("\"v\"", eval(t, "names[\"k\"]").get("result"), "names[\"k\"]");
            eq("\"v\"", eval(t, "names.k").get("result"), "names.k");
            eq("2", eval(t, "names.other").get("result"), "second entry");
            eq("\"five\"", eval(t, "byInt[5]").get("result"), "int key");
            eq("java.util.HashMap (2 entries)", eval(t, "names").get("result"), "map summary");
            eq("int[][3]", eval(t, "arr").get("result"), "array summary");
        });

        test("evaluate: groovy.lang.Reference is unwrapped, as in the pane", () -> {
            ObjectReference box = obj("groovy.lang.Reference", fields("value", intValue(7)));
            ThreadReference t = thread(null, locals("sum", box));
            Map<String, Object> out = eval(t, "sum");
            eq("7", out.get("result"), "unwrapped");
            eq("int", out.get("type"), "unwrapped type");
            ObjectReference boxedList = obj("groovy.lang.Reference", fields("value",
                    arrayList(intValue(1), intValue(2))));
            eq("2", eval(thread(null, locals("xs", boxedList)), "xs[1]").get("result"),
                    "a path continues through an unwrapped Reference");
        });

        test("evaluate: the Grails scope is last, and only asked for when needed", () -> {
            ObjectReference params = hashMap(str("id"), str("42"));
            ObjectReference web = obj("GrailsWebRequest", fields("params", params,
                    "request", obj("Req", fields()), "response", obj("Resp", fields()),
                    "session", null));
            AtomicInteger asked = new AtomicInteger();
            Supplier<ObjectReference> scope = () -> {
                asked.incrementAndGet();
                return web;
            };
            ThreadReference t = thread(null, locals("a", intValue(1)));
            eq("1", new Variables().evaluate(t, 0, scope, "a").get("result"), "local");
            eq(0, asked.get(), "the web scope must not be looked up for a local");
            eq("\"42\"", new Variables().evaluate(t, 0, scope, "params.id").get("result"),
                    "params.id");
            eq("null", new Variables().evaluate(t, 0, scope, "session").get("result"),
                    "a null session is shown, not an error");
            eq("'nope' is not defined in this frame", fail(t, scope, "nope"), "unknown name");
            eq("'nope' is not defined in this frame", fail(t, () -> null, "nope"),
                    "no web request on this thread");
            // a name that looks like a scope name but the thread is not in a request
            eq("'params' is not defined in this frame", fail(t, () -> null, "params"),
                    "params outside a request");
        });

        test("evaluate: error messages", () -> {
            ObjectReference self = obj("demo.Svc", fields("count", intValue(3)));
            ThreadReference t = thread(self, locals("a", intValue(1), "s", str("x"),
                    "nothing", null, "o", obj("demo.O", fields("f", intValue(1))),
                    "arr", array("int[]", intValue(1))));
            eq("'zzz' is not defined in this frame", fail(t, "zzz"), "undefined");
            eq(PathExpression.READ_ONLY, fail(t, "a + 1"), "operator");
            eq(PathExpression.READ_ONLY, fail(t, "foo()"), "call");
            eq("the expression is empty", fail(t, ""), "empty");
            eq("cannot read '.b' of 'nothing': it is null", fail(t, "nothing.b"), "null deref");
            eq("cannot read '.b' of 'a': a int has no members to show", fail(t, "a.b"), "primitive");
            eq("cannot read '.b' of 's': a java.lang.String has no members to show",
                    fail(t, "s.b"), "string");
            eq("'o' has no member 'g'", fail(t, "o.g"), "missing field");
            eq("'arr' has no element [5]", fail(t, "arr[5]"), "past the end");
        });

        test("evaluate: a local the VM cannot read yet says so, and is not 'not defined'", () -> {
            ThreadReference t = thread(null, locals(
                    "i", new InconsistentDebugInfoException(), "ok", intValue(2)));
            eq("'i' is not readable at this point (it is initialised later in this method)",
                    fail(t, "i"), "unreadable");
            eq("2", eval(t, "ok").get("result"), "its neighbour still reads");
            eq("'j' is not defined in this frame", fail(t, "j"), "a different name");
        });

        test("evaluate: the 200 cap is the pane's, and says so", () -> {
            Value[] many = new Value[250];
            Arrays.fill(many, intValue(0));
            ThreadReference t = thread(null, locals("big", array("int[]", many)));
            eq("0", eval(t, "big[199]").get("result"), "last listed element");
            String message = fail(t, "big[200]");
            that(message.startsWith("'big' has no element [200]") && message.contains("at most 200"),
                    message);
        });

        test("evaluate: a stale frame is an error, not an exception", () -> {
            ThreadReference t = of(ThreadReference.class, "thread", methods("frame",
                    (Function<Object[], Object>) a -> {
                        return EvaluateChecks.<RuntimeException>sneaky(
                                new com.sun.jdi.IncompatibleThreadStateException("not suspended"));
                    }));
            String message = fail(t, "a");
            that(message.startsWith("cannot read this frame: "), message);
        });

        test("evaluate: read-only -- no fake saw an invoke*/newInstance call", () -> {
            eq(0, Fakes.INVOCATIONS.size(), "invocations: " + Fakes.INVOCATIONS);
        });
    }
}
