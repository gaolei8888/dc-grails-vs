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
import com.sun.jdi.LocalVariable;
import com.sun.jdi.Location;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StackFrame;
import com.sun.jdi.StringReference;
import com.sun.jdi.Value;

import org.groovydap.jdi.PathEvaluator;
import org.groovydap.jdi.Variables;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Path evaluation (PathEvaluator + Variables.describeResult), against JDI fakes.
 * Every fake throws if anything tries to invoke a method, so a pass also shows
 * the evaluator only ever reads.
 */
final class EvaluateChecks {

    private EvaluateChecks() {
    }

    static void run() {
        System.out.println("evaluate checks");
        evaluation();
        rejection();
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

    /** A frame whose locals are {@code locals}: a Value, or a RuntimeException to throw on read. */
    private static StackFrame frame(ObjectReference self, Map<String, Object> locals) {
        Map<String, LocalVariable> byName = new LinkedHashMap<>();
        locals.keySet().forEach(n -> byName.put(n, local(n, "x")));
        ReferenceType declaring = of(ReferenceType.class, "demo.Frame", methods(
                "name", "demo.Frame", "fieldByName", (Function<Object[], Object>) a -> null));
        Location location = of(Location.class, "loc", methods("declaringType", declaring));
        return of(StackFrame.class, "frame", methods(
                "thisObject", self,
                "location", location,
                "visibleVariableByName", (Function<Object[], Object>) a -> byName.get((String) a[0]),
                "getValue", (Function<Object[], Object>) a -> {
                    Object v = locals.get(((LocalVariable) a[0]).name());
                    if (v instanceof RuntimeException) {
                        throw (RuntimeException) v;
                    }
                    return v;
                }));
    }

    private static Map<String, Object> locals(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static Value value(StackFrame f, ObjectReference web, String expression)
            throws PathEvaluator.Unsupported {
        return PathEvaluator.evaluate(expression, f, web);
    }

    private static Map<String, Object> eval(StackFrame f, String expression) throws Exception {
        return new Variables().describeResult(value(f, null, expression));
    }

    /** The refusal text, or "NO ERROR". */
    private static String fail(StackFrame f, ObjectReference web, String expression) {
        try {
            value(f, web, expression);
            return "NO ERROR";
        } catch (PathEvaluator.Unsupported e) {
            return e.getMessage();
        }
    }

    // ------------------------------------------------------------- evaluation

    private static void evaluation() {
        test("evaluate: a primitive local and a string, shaped like the variables pane", () -> {
            StackFrame f = frame(null, locals("a", intValue(100), "s", str("hi")));
            Map<String, Object> a = eval(f, "a");
            eq("100", a.get("result"), "result");
            eq("int", a.get("type"), "type");
            eq(0, a.get("variablesReference"), "a primitive has nothing to expand");
            Map<String, Object> s = eval(f, "s");
            eq("\"hi\"", s.get("result"), "strings are quoted like the pane");
            eq("java.lang.String", s.get("type"), "string type");
        });

        test("evaluate: object field paths, and the result expands in the pane", () -> {
            ObjectReference c = obj("demo.C", fields("n", intValue(7), "tag", str("t")));
            ObjectReference b = obj("demo.B", fields("c", c));
            ObjectReference a = obj("demo.A", fields("b", b));
            StackFrame f = frame(null, locals("a", a));
            eq("7", eval(f, "a.b.c.n").get("result"), "a.b.c.n");
            eq("\"t\"", eval(f, "a . b . c . tag").get("result"), "spaces allowed");
            Map<String, Object> deep = eval(f, "a.b");
            eq("demo.B", deep.get("type"), "type of an object");
            that(((Number) deep.get("variablesReference")).intValue() > 0,
                    "an object result must be expandable: " + deep);
        });

        test("evaluate: this, and this-fields when no local has the name; a local wins", () -> {
            ObjectReference self = obj("demo.Svc", fields("count", intValue(3), "x", intValue(1)));
            StackFrame f = frame(self, locals("x", intValue(99)));
            eq("99", eval(f, "x").get("result"), "local shadows the field");
            eq("3", eval(f, "count").get("result"), "field of this");
            eq("3", eval(f, "this.count").get("result"), "explicit this");
            eq("demo.Svc", eval(f, "this").get("type"), "this itself");
            eq("1", eval(f, "this.x").get("result"), "this.x reaches the field the local hides");
        });

        test("evaluate: arrays, ArrayList and maps by index and key", () -> {
            ObjectReference list = arrayList(str("a"), str("b"));
            ArrayReference arr = array("int[]", intValue(10), intValue(20), intValue(30));
            ObjectReference names = hashMap(str("k"), str("v"), str("other"), intValue(2));
            StackFrame f = frame(null, locals("list", list, "arr", arr, "names", names));
            eq("20", eval(f, "arr[1]").get("result"), "arr[1]");
            eq("\"b\"", eval(f, "list[1]").get("result"), "list[1]");
            eq("\"v\"", eval(f, "names['k']").get("result"), "names['k']");
            eq("\"v\"", eval(f, "names[\"k\"]").get("result"), "names[\"k\"]");
            eq("\"v\"", eval(f, "names.k").get("result"), "names.k");
            eq("2", eval(f, "names.other").get("result"), "second entry");
            that(fail(f, null, "names.nope").contains("no entry nope"), "a missing key says so");
        });

        test("evaluate: groovy.lang.Reference is unwrapped, as in the pane", () -> {
            ObjectReference box = obj("groovy.lang.Reference", fields("value", intValue(7)));
            Map<String, Object> out = eval(frame(null, locals("sum", box)), "sum");
            eq("7", out.get("result"), "unwrapped");
            eq("int", out.get("type"), "unwrapped type");
            ObjectReference boxedList = obj("groovy.lang.Reference", fields("value",
                    arrayList(intValue(1), intValue(2))));
            eq("2", eval(frame(null, locals("xs", boxedList)), "xs[1]").get("result"),
                    "a path continues through an unwrapped Reference");
        });

        test("evaluate: the Grails request scope is consulted last", () -> {
            ObjectReference params = hashMap(str("id"), str("42"));
            ObjectReference web = obj("GrailsWebRequest", fields("params", params));
            StackFrame f = frame(null, locals("a", intValue(1)));
            eq("1", new Variables().describeResult(value(f, web, "a")).get("result"), "local");
            eq("\"42\"", new Variables().describeResult(value(f, web, "params.id")).get("result"),
                    "params.id");
            eq("no local, field or request attribute named nope", fail(f, web, "nope"), "unknown");
            eq("no local, field or request attribute named params", fail(f, null, "params"),
                    "params outside a request");
        });

        test("evaluate: a local the VM cannot read yet says so, not 'no local named'", () -> {
            StackFrame f = frame(null, locals(
                    "i", new InconsistentDebugInfoException(), "ok", intValue(2)));
            eq("'i' is not readable at this point (it is initialised later in this method)",
                    fail(f, null, "i"), "unreadable");
            eq("2", eval(f, "ok").get("result"), "its neighbour still reads");
            eq("no local, field or request attribute named j", fail(f, null, "j"),
                    "a different name");
        });

        test("evaluate: error messages for null, primitives and missing members", () -> {
            ObjectReference o = obj("demo.O", fields("f", intValue(1)));
            StackFrame f = frame(null, locals("a", intValue(1), "nothing", null, "o", o));
            eq("nothing is null", fail(f, null, "nothing.b"), "null deref");
            that(fail(f, null, "a.b").contains("has no field b"), fail(f, null, "a.b"));
            that(fail(f, null, "o.g").contains("has no field g"), fail(f, null, "o.g"));
        });

        test("evaluate: read-only -- no fake saw an invoke*/newInstance call", () -> {
            eq(0, Fakes.INVOCATIONS.size(), "invocations: " + Fakes.INVOCATIONS);
        });
    }

    // -------------------------------------------------------------- rejection

    private static void rejection() {
        test("evaluate: calls, operators and closures are refused by name", () -> {
            StackFrame f = frame(null, locals("a", intValue(1), "b", intValue(2)));
            String[][] table = {
                {"foo()", "method calls are not supported"},
                {"a.foo(1)", "method calls are not supported"},
                {"new Foo()", "method calls are not supported"},
                {"a+1", "operators are not supported"},
                {"a + 1", "operators are not supported"},
                {"a == b", "operators are not supported"},
                {"a ? b : c", "operators are not supported"},
                {"a?.b", "operators are not supported"},
                {"a.b{", "closures are not supported"},
                {"", "empty expression"},
            };
            for (String[] row : table) {
                String got = fail(f, null, row[0]);
                that(got.startsWith(row[1]), "'" + row[0] + "' -> " + got);
            }
        });

        test("evaluate: operator characters inside a quoted key are part of the key", () -> {
            ObjectReference params = hashMap(str("user-id"), str("7"), str("a/b"), str("slash"),
                    str("x?y"), str("q"), str("a+b"), str("plus"), str("a(b"), str("paren"),
                    str("a]b"), str("bracket"), str("k=v"), str("eq"));
            StackFrame f = frame(null, locals("params", params, "map", params));
            String[][] ok = {
                {"params['user-id']", "\"7\""},
                {"map['a/b']", "\"slash\""},
                {"map[\"x?y\"]", "\"q\""},
                {"map['a+b']", "\"plus\""},
                {"map['a(b']", "\"paren\""},
                {"map['a]b']", "\"bracket\""},
                {"map['k=v']", "\"eq\""},
            };
            for (String[] row : ok) {
                eq(row[1], String.valueOf(eval(f, row[0]).get("result")), row[0]);
            }
            // The same characters outside quotes are still refused.
            that(fail(f, null, "params['user-id'] + 1").startsWith("operators are not supported"),
                    "an operator after a quoted key");
            that(fail(f, null, "params.user-id").startsWith("operators are not supported"),
                    "a bare dash");
            that(fail(f, null, "foo('x')").startsWith("method calls are not supported"),
                    "a call whose argument is quoted");
            that(fail(f, null, "map['a(b'](").startsWith("method calls are not supported"),
                    "a call after a quoted key");
        });
    }
}
