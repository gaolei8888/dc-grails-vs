package org.groovydap;

import static org.groovydap.Check.eq;
import static org.groovydap.Check.test;
import static org.groovydap.Check.that;
import static org.groovydap.Fakes.methods;
import static org.groovydap.Fakes.of;

import com.sun.jdi.ArrayReference;
import com.sun.jdi.Field;
import com.sun.jdi.InconsistentDebugInfoException;
import com.sun.jdi.IntegerValue;
import com.sun.jdi.LocalVariable;
import com.sun.jdi.Location;
import com.sun.jdi.Method;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StackFrame;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Type;
import com.sun.jdi.Value;

import org.groovydap.dap.DapTransport;
import org.groovydap.dap.Json;
import org.groovydap.jdi.SourceRef;
import org.groovydap.jdi.StopDeduper;
import org.groovydap.jdi.Variables;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Checks that need no JVM to attach to. */
final class UnitChecks {

    private UnitChecks() {
    }

    static void run() {
        json();
        transport();
        stepExcludes();
        sourceRef();
        stopDeduper();
        variables();
    }

    // ------------------------------------------------------------------ Json

    private static void json() {
        test("Json: hostile strings round-trip and never emit a raw newline", () -> {
            String nasty = "line1\nline2\r\n\ttab \"quote\" back\\slash \u0001\u001f   "
                    + "磊宁 é 😀 end";
            Map<String, Object> in = new LinkedHashMap<>();
            in.put("s", nasty);
            in.put(nasty, "key needs escaping too");
            String wire = Json.write(in);
            that(wire.indexOf('\n') < 0 && wire.indexOf('\r') < 0,
                    "serialized message contains a raw line break: " + wire);
            for (char c : wire.toCharArray()) {
                that(c >= 0x20, "raw control char U+" + Integer.toHexString(c) + " in " + wire);
            }
            Map<String, Object> out = Json.parseObject(wire);
            eq(nasty, out.get("s"), "string value");
            eq("key needs escaping too", out.get(nasty), "escaped key");
        });
        test("Json: nested structure, numbers, booleans, null", () -> {
            Map<String, Object> in = new LinkedHashMap<>();
            in.put("n", 42);
            in.put("big", 1L << 40);
            in.put("d", 1.5);
            in.put("t", Boolean.TRUE);
            in.put("nil", null);
            in.put("list", Arrays.asList(1, "two", Arrays.asList(3), new LinkedHashMap<>()));
            Map<String, Object> out = Json.parseObject(Json.write(in));
            eq(42L, ((Number) out.get("n")).longValue(), "n");
            eq(1L << 40, ((Number) out.get("big")).longValue(), "big");
            eq(1.5, ((Number) out.get("d")).doubleValue(), "d");
            eq(Boolean.TRUE, out.get("t"), "t");
            that(out.containsKey("nil") && out.get("nil") == null, "null survives");
            eq(4, ((List<?>) out.get("list")).size(), "list size");
        });
        test("Json: parses \\u escapes and rejects garbage", () -> {
            eq("Aé\n", Json.parseObject("{\"k\":\"\\u0041\\u00e9\\n\"}").get("k"), "escapes");
            boolean threw = false;
            try {
                Json.parseObject("{\"k\": nope}");
            } catch (RuntimeException e) {
                threw = true;
            }
            that(threw, "invalid JSON must throw");
        });
    }

    // ------------------------------------------------------------- transport

    private static void transport() {
        test("DapTransport: Content-Length counts bytes, not chars", () -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            DapTransport t = new DapTransport(new ByteArrayInputStream(new byte[0]), out);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("text", "磊宁 😀");
            t.sendEvent("output", body);
            byte[] bytes = out.toByteArray();
            String all = new String(bytes, StandardCharsets.UTF_8);
            int split = all.indexOf("\r\n\r\n");
            that(split > 0, "no header terminator");
            String header = all.substring(0, split);
            that(header.startsWith("Content-Length: "), "header is " + header);
            int declared = Integer.parseInt(header.substring("Content-Length: ".length()));
            int actualBytes = bytes.length - (split + 4);
            eq(actualBytes, declared, "declared vs actual body bytes");
            that(declared > all.length() - split - 4, "test must use multi-byte chars");
        });
        test("DapTransport: round trip, back-to-back messages, clean EOF", () -> {
            ByteArrayOutputStream wire = new ByteArrayOutputStream();
            DapTransport writer = new DapTransport(new ByteArrayInputStream(new byte[0]), wire);
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("command", "first\n磊");
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("command", "second");
            writer.send(a);
            writer.send(b);
            DapTransport reader = new DapTransport(
                    new ByteArrayInputStream(wire.toByteArray()), new ByteArrayOutputStream());
            Map<String, Object> m1 = reader.receive();
            Map<String, Object> m2 = reader.receive();
            eq("first\n磊", m1.get("command"), "first command");
            eq("second", m2.get("command"), "second command");
            eq(1L, ((Number) m1.get("seq")).longValue(), "seq 1");
            eq(2L, ((Number) m2.get("seq")).longValue(), "seq 2");
            that(reader.receive() == null, "EOF between messages must give null");
        });
        test("DapTransport: tolerates other headers and lower-case name", () -> {
            String json = "{\"command\":\"x\"}";
            String raw = "Content-Type: application/json\r\ncontent-length: " + json.length()
                    + "\r\n\r\n" + json;
            DapTransport t = new DapTransport(new ByteArrayInputStream(
                    raw.getBytes(StandardCharsets.UTF_8)), new ByteArrayOutputStream());
            eq("x", t.receive().get("command"), "command");
        });
        test("DapTransport: no Content-Length / truncated body are errors", () -> {
            DapTransport none = new DapTransport(new ByteArrayInputStream(
                    "X-Foo: 1\r\n\r\n{}".getBytes(StandardCharsets.UTF_8)), new ByteArrayOutputStream());
            boolean threw = false;
            try {
                none.receive();
            } catch (IOException e) {
                threw = true;
            }
            that(threw, "missing Content-Length must throw IOException");
            DapTransport cut = new DapTransport(new ByteArrayInputStream(
                    "Content-Length: 50\r\n\r\n{}".getBytes(StandardCharsets.UTF_8)),
                    new ByteArrayOutputStream());
            threw = false;
            try {
                cut.receive();
            } catch (EOFException e) {
                threw = true;
            }
            that(threw, "truncated body must throw EOFException");
        });
        test("DapChannel defaults: response / error / event shapes", () -> {
            List<Map<String, Object>> sent = new ArrayList<>();
            org.groovydap.dap.DapChannel ch = new org.groovydap.dap.DapChannel() {
                @Override
                public Map<String, Object> receive() {
                    return null;
                }

                @Override
                public void send(Map<String, Object> message) {
                    sent.add(message);
                }
            };
            Map<String, Object> req = new LinkedHashMap<>();
            req.put("seq", 7);
            req.put("command", "threads");
            ch.sendResponse(req, null);
            ch.sendError(req, "boom");
            ch.sendEvent("initialized", null);
            eq("response", sent.get(0).get("type"), "type0");
            eq(7, sent.get(0).get("request_seq"), "request_seq");
            eq(Boolean.TRUE, sent.get(0).get("success"), "success");
            that(!sent.get(0).containsKey("body"), "null body omitted");
            eq(Boolean.FALSE, sent.get(1).get("success"), "error success");
            eq("boom", sent.get(1).get("message"), "error message");
            eq("event", sent.get(2).get("type"), "event type");
            eq("initialized", sent.get(2).get("event"), "event name");
        });
    }

    // ---------------------------------------------------------- step excludes

    private static void stepExcludes() {
        test("DEFAULT_STEP_EXCLUDES keeps org.apache.juli.* (cold-JVM step-out regression)", () -> {
            that(DebugSession.DEFAULT_STEP_EXCLUDES.contains("org.apache.juli.*"),
                    "missing org.apache.juli.*: " + DebugSession.DEFAULT_STEP_EXCLUDES);
            // Its neighbours in the same list, so a bad merge cannot silently drop them.
            that(DebugSession.DEFAULT_STEP_EXCLUDES.contains("org.apache.catalina.*"), "catalina");
            that(DebugSession.DEFAULT_STEP_EXCLUDES.contains("groovy.*"), "groovy");
        });
    }

    // ----------------------------------------------------------------- source

    private static void sourceRef() {
        test("SourceRef: prefixes, closure ownership, snap past a signature line", () -> {
            Path dir = Files.createTempDirectory("srcref");
            Path file = dir.resolve("FooService.groovy");
            Files.write(file, String.join("\n",
                    "package com.example",            // 1
                    "// class Commented {}",          // 2
                    "class FooService {",             // 3
                    "    def save(int x) {",          // 4
                    "        int y = x + 1",          // 5
                    "        y",                      // 6
                    "    }",                          // 7
                    "}",                              // 8
                    "class Helper {}").getBytes(StandardCharsets.UTF_8));
            try {
                SourceRef ref = SourceRef.of(file.toString());
                eq("FooService.groovy", ref.fileName(), "fileName");
                eq(Arrays.asList("com.example.FooService", "com.example.Helper"), ref.prefixes(),
                        "prefixes (commented-out class ignored, base name first)");
                that(ref.mayOwn("com.example.FooService"), "exact");
                that(ref.mayOwn("com.example.FooService$_save_closure1"), "closure class");
                that(!ref.mayOwn("com.example.FooServiceImpl"), "name that merely starts with it");
                eq(Arrays.asList("com.example.FooService*", "com.example.Helper*"),
                        ref.classPrepareFilters(), "class-prepare filters");
                eq(5, ref.firstExecutableLineAtOrAfter(4), "signature line slides down");
                eq(5, ref.firstExecutableLineAtOrAfter(5), "executable line stays");
                eq("    def save(int x) {", ref.lineText(4), "lineText");
                eq("", ref.lineText(999), "lineText past the end");
            } finally {
                Files.deleteIfExists(file);
                Files.deleteIfExists(dir);
            }
        });
    }

    // ------------------------------------------------------------ StopDeduper

    private static Location location(String type, String method, int line, long bci) {
        ReferenceType declaring = of(ReferenceType.class, type, methods("name", type));
        Method m = of(Method.class, method, methods("name", method));
        return of(Location.class, type + "." + method + ":" + line,
                methods("declaringType", declaring, "method", m,
                        "lineNumber", line, "codeIndex", bci));
    }

    private static ThreadReference thread(long id) {
        return of(ThreadReference.class, "thread-" + id, methods("uniqueID", id));
    }

    private static void stopDeduper() {
        test("StopDeduper: second bci of the same line is suppressed, a fresh call is not", () -> {
            StopDeduper d = new StopDeduper();
            ThreadReference t = thread(1);
            that(d.shouldReport(t, location("A", "m", 20, 20), 3, 20), "first stop reports");
            that(!d.shouldReport(t, location("A", "m", 20, 79), 3, 20), "same line, other bci: dup");
            that(d.shouldReport(t, location("A", "m", 21, 90), 3, 21), "next line reports");
            that(d.shouldReport(t, location("A", "m", 20, 20), 3, 20), "loop back to line 20 reports");
        });
        test("StopDeduper: depth, method, thread and forget/clear make stops distinct", () -> {
            StopDeduper d = new StopDeduper();
            ThreadReference t1 = thread(1);
            ThreadReference t2 = thread(2);
            that(d.shouldReport(t1, location("A", "m", 5, 10), 3, 5), "t1");
            that(d.shouldReport(t1, location("A", "m", 5, 40), 4, 5), "different depth reports");
            that(d.shouldReport(t1, location("A", "n", 5, 40), 4, 5), "different method reports");
            that(d.shouldReport(t2, location("A", "n", 5, 99), 4, 5), "other thread reports");
            d.forget(t1);
            that(d.shouldReport(t1, location("A", "n", 5, 7), 4, 5), "forgotten thread reports");
            that(!d.shouldReport(t2, location("A", "n", 5, 3), 4, 5), "t2 still deduped");
            d.clear();
            that(d.shouldReport(t2, location("A", "n", 5, 3), 4, 5), "cleared reports");
        });
    }

    // -------------------------------------------------------------- Variables

    static Type type(String name) {
        return of(Type.class, name, methods("name", name));
    }

    static IntegerValue intValue(int v) {
        return of(IntegerValue.class, String.valueOf(v), methods("type", type("int")));
    }

    static LocalVariable local(String name, String typeName) {
        return of(LocalVariable.class, name, methods("name", name, "typeName", typeName));
    }

    private static ThreadReference threadWithFrame(List<LocalVariable> locals,
            java.util.function.Function<LocalVariable, Value> values) {
        StackFrame frame = of(StackFrame.class, "frame", methods(
                "thisObject", (Object) null,
                "visibleVariables", locals,
                "getValue", (java.util.function.Function<Object[], Object>)
                        a -> values.apply((LocalVariable) a[0])));
        return of(ThreadReference.class, "thread", methods(
                "frame", frame, "uniqueID", 1L));
    }

    private static Map<String, Map<String, Object>> byName(List<Map<String, Object>> vars) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Map<String, Object> v : vars) {
            out.put((String) v.get("name"), v);
        }
        return out;
    }

    private static void variables() {
        test("Variables: an InconsistentDebugInfoException local is skipped, the rest render", () -> {
            LocalVariable boxed = local("boxed", "groovy.lang.Reference");
            LocalVariable fine = local("fine", "int");
            ThreadReference t = threadWithFrame(Arrays.asList(boxed, fine), lv -> {
                if (lv == boxed) {
                    throw new InconsistentDebugInfoException();
                }
                return intValue(5);
            });
            Variables vars = new Variables();
            List<Map<String, Object>> out = vars.children(vars.localsHandle(t, 0));
            Map<String, Map<String, Object>> by = byName(out);
            that(!by.containsKey("<error>"), "an <error> entry leaked: " + out);
            that(!by.containsKey("boxed"), "the unreadable local must be omitted: " + out);
            eq("5", by.get("fine").get("value"), "the readable local");
            eq(1, out.size(), "exactly one variable");
        });
        test("Variables: synthetic compiler locals are hidden", () -> {
            List<LocalVariable> locals = Arrays.asList(local("$callSiteArray", "x"),
                    local("__$stMC", "x"), local("metaClass", "x"), local("real", "int"));
            ThreadReference t = threadWithFrame(locals, lv -> intValue(1));
            Variables vars = new Variables();
            List<Map<String, Object>> out = vars.children(vars.localsHandle(t, 0));
            eq(1, out.size(), "only the real local: " + out);
            eq("real", out.get(0).get("name"), "name");
        });
        test("Variables: array children capped at 200 plus a remainder marker", () -> {
            ReferenceType arrType = of(ReferenceType.class, "int[]", methods("name", "int[]"));
            ArrayReference arr = of(ArrayReference.class, "arr", methods(
                    "length", 500,
                    "getValue", (java.util.function.Function<Object[], Object>)
                            a -> intValue((Integer) a[0]),
                    "referenceType", arrType,
                    "type", type("int[]")));
            LocalVariable big = local("big", "int[]");
            ThreadReference t = threadWithFrame(Arrays.asList(big), lv -> arr);
            Variables vars = new Variables();
            Map<String, Object> v = vars.children(vars.localsHandle(t, 0)).get(0);
            eq("int[][500]", v.get("value"), "array summary");
            int ref = ((Number) v.get("variablesReference")).intValue();
            that(ref > 0, "array must be expandable");
            List<Map<String, Object>> kids = vars.children(ref);
            eq(201, kids.size(), "200 elements + marker");
            eq("[0]", kids.get(0).get("name"), "first element");
            eq("[199]", kids.get(199).get("name"), "last shown element");
            eq("300 more elements", kids.get(200).get("value"), "remainder marker");
        });
        test("Variables: groovy.lang.Reference is unwrapped one level", () -> {
            Field valueField = of(Field.class, "value", methods());
            ReferenceType refType = of(ReferenceType.class, "groovy.lang.Reference", methods(
                    "name", "groovy.lang.Reference", "fieldByName", valueField));
            ObjectReference ref = of(ObjectReference.class, "ref", methods(
                    "referenceType", refType,
                    "getValue", intValue(7)));
            ThreadReference t = threadWithFrame(Arrays.asList(local("captured", "groovy.lang.Reference")),
                    lv -> ref);
            Variables vars = new Variables();
            Map<String, Object> v = vars.children(vars.localsHandle(t, 0)).get(0);
            eq("7", v.get("value"), "unwrapped value");
            eq("int", v.get("type"), "unwrapped type");
        });
    }
}
