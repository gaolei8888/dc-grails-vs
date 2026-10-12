package org.groovydap;

import static org.groovydap.Check.eq;
import static org.groovydap.Check.test;
import static org.groovydap.Check.that;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Drives a real DebugSession against a real JVM over a loopback channel. The target
 * is a few lines of plain Java compiled at test time; no Grails, and an ephemeral
 * JDWP port so nothing can collide with a bootRun on 5005.
 */
final class EndToEndChecks {

    private static final String[] TARGET = {
        "import java.util.*;",                                          // 1
        "public class Target {",                                        // 2
        "    public static void main(String[] args) throws Exception {" // 3
            + " System.out.println(\"target ready\"); System.in.read();",
        "        int x = 40;",                                          // 4
        "        String s = \"hi\\nthere\";",                           // 5
        "        int[] big = new int[500];",                            // 6
        "        Map<Integer, Integer> m = new HashMap<>();",           // 7
        "        for (int i = 0; i < 300; i++) { m.put(i, i); }",       // 8
        "        int[] arr = {10, 20, 30};",                            // 9
        "        Map<String, String> names = new HashMap<>();"          // 10
            + " names.put(\"k\", \"v\");",
        "        List<String> list = new ArrayList<>(List.of(\"a\", \"b\"));", // 11
        "        Item item = new Item(\"box\", new Item(\"inner\", null));", // 12
        "        boolean flag = false; double ratio = 1.5; char ch = 'a';" // 13
            + " byte tiny = 1; Integer boxed = 5;",
        "        String label = \"old\"; String gone = \"here\"; int[] nums = {1, 2, 3};" // 14
            + " Holder hold = new Holder();",
        "        int y = x + 2;",                                       // 15 BREAKPOINT
        "        y = y + 1;",                                           // 16 after next
        "        System.out.println(\"target done \" + y);",            // 17
        "        System.out.println(\"after x=\" + x + \" flag=\" + flag + \" ratio=\" + ratio" // 18
            + " + \" ch=\" + ch + \" tiny=\" + tiny + \" boxed=\" + boxed + \" label=\" + label"
            + " + \" gone=\" + gone + \" nums=\" + nums[0] + \",\" + nums[1] + \",\" + nums[2]"
            + " + \" count=\" + hold.count + \" fixed=\" + hold.fixed);",
        "    }",
        "    static class Holder { int count = 3; final int fixed; Holder() { fixed = 9; } }",
        "    static class Item {"
            + " String name; Item next;"
            + " Item(String name, Item next) { this.name = name; this.next = next; } }",
        "}",
    };
    private static final int BREAKPOINT_LINE = 15;

    private static final Pattern LISTENING =
            Pattern.compile("Listening for transport dt_socket at address: (?:[^:\\s]+:)?(\\d+)");

    private EndToEndChecks() {
    }

    /** A spawned target JVM waiting for a debugger, and what it printed. */
    private static final class Target implements AutoCloseable {
        final Process process;
        final int port;
        final List<String> output = Collections.synchronizedList(new ArrayList<>());

        Target(Path dir) throws Exception {
            String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            process = new ProcessBuilder(java,
                    "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:0",
                    "-cp", dir.toString(), "Target")
                    .redirectErrorStream(true).start();
            Thread reader = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(
                        process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        output.add(line);
                    }
                } catch (IOException ignored) {
                    // process died; nothing more to read
                }
            }, "target-output");
            reader.setDaemon(true);
            reader.start();

            long deadline = System.currentTimeMillis() + 20_000;
            int found = -1;
            while (found < 0 && System.currentTimeMillis() < deadline) {
                synchronized (output) {
                    for (String line : output) {
                        Matcher m = LISTENING.matcher(line);
                        if (m.find()) {
                            found = Integer.parseInt(m.group(1));
                        }
                    }
                }
                if (found < 0) {
                    Thread.sleep(50);
                }
            }
            if (found <= 0) {
                process.destroyForcibly();
                throw new AssertionError("target never announced a JDWP port: " + output);
            }
            port = found;
        }

        void awaitOutput(String line) throws Exception {
            long deadline = System.currentTimeMillis() + 20_000;
            while (System.currentTimeMillis() < deadline) {
                if (output.contains(line)) {
                    return;
                }
                Thread.sleep(50);
            }
            throw new AssertionError("target never printed '" + line + "': " + output);
        }

        /** Lets the parked main() carry on. */
        void release() throws IOException {
            process.getOutputStream().write('\n');
            process.getOutputStream().flush();
        }

        @Override
        public void close() {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static Path compileTarget() throws Exception {
        Path dir = Files.createTempDirectory("groovydap-e2e");
        Path src = dir.resolve("Target.java");
        Files.write(src, String.join("\n", TARGET).getBytes(StandardCharsets.UTF_8));
        String javac = Path.of(System.getProperty("java.home"), "bin", "javac").toString();
        Process p = new ProcessBuilder(javac, "-g", "-d", dir.toString(), src.toString())
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0) {
            throw new AssertionError("javac failed: " + out);
        }
        return dir;
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> body(Map<String, Object> response) {
        that(Boolean.TRUE.equals(response.get("success")), "request failed: " + response);
        return (Map<String, Object>) response.get("body");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> body, String key) {
        return (List<Map<String, Object>>) body.get(key);
    }

    private static Map<String, Object> named(List<Map<String, Object>> vars, String name) {
        for (Map<String, Object> v : vars) {
            if (name.equals(v.get("name"))) {
                return v;
            }
        }
        throw new AssertionError("no variable " + name + " in " + vars);
    }

    private static int intOf(Object o) {
        return ((Number) o).intValue();
    }

    /** One session on its own thread, plus the client end of the loopback. */
    private static final class Session {
        final LoopbackChannel channel = new LoopbackChannel();
        final DebugSession session = new DebugSession(channel);
        volatile Throwable runFailure;
        final Thread thread = new Thread(() -> {
            try {
                session.run();
            } catch (Throwable t) {
                runFailure = t;
            }
        }, "dap-session");

        Session() {
            thread.setDaemon(true);
            thread.start();
        }

        /** Handshake up to the first breakpoint hit. Returns the stopped thread id. */
        int attachAndHitBreakpoint(Target target, Path dir) throws Exception {
            body(channel.request("initialize", map("adapterID", "groovy")));
            // The adapter resumes the VM's start-up suspension as soon as it attaches,
            // so main() runs on its own. The target announces itself and then waits on
            // stdin, which is what makes "Target is loaded, main is parked" certain.
            body(channel.request("attach", map("hostName", "127.0.0.1", "port", target.port,
                    "sourcePaths", List.of(dir.toString()), "timeout", 15000)));
            channel.awaitEvent("initialized", 15_000);
            target.awaitOutput("target ready");
            Map<String, Object> bps = body(channel.request("setBreakpoints", map(
                    "source", map("path", dir.resolve("Target.java").toString()),
                    "breakpoints", List.of(map("line", BREAKPOINT_LINE)))));
            Map<String, Object> bp = list(bps, "breakpoints").get(0);
            eq(Boolean.TRUE, bp.get("verified"), "breakpoint verified: " + bp);
            eq(BREAKPOINT_LINE, intOf(bp.get("line")), "line");
            body(channel.request("configurationDone", null));
            target.release();
            Map<String, Object> stopped = channel.awaitEvent("stopped", 20_000);
            @SuppressWarnings("unchecked")
            Map<String, Object> sb = (Map<String, Object>) stopped.get("body");
            eq("breakpoint", sb.get("reason"), "stop reason");
            return intOf(sb.get("threadId"));
        }

        Map<String, Object> topFrame(int threadId) throws Exception {
            Map<String, Object> st = body(channel.request("stackTrace", map("threadId", threadId)));
            return list(st, "stackFrames").get(0);
        }

        List<Map<String, Object>> locals(Map<String, Object> frame) throws Exception {
            Map<String, Object> scopes = body(channel.request("scopes",
                    map("frameId", frame.get("id"))));
            Map<String, Object> localsScope = list(scopes, "scopes").get(0);
            eq("Locals", localsScope.get("name"), "first scope");
            return list(body(channel.request("variables",
                    map("variablesReference", localsScope.get("variablesReference")))), "variables");
        }

        List<Map<String, Object>> children(Map<String, Object> variable) throws Exception {
            return list(body(channel.request("variables",
                    map("variablesReference", variable.get("variablesReference")))), "variables");
        }

        /** The raw evaluate response for an expression in this frame. */
        Map<String, Object> evaluate(Map<String, Object> frame, String expression, String context)
                throws Exception {
            return channel.request("evaluate", map("expression", expression,
                    "frameId", frame.get("id"), "context", context));
        }

        /** The raw setVariable response. */
        Map<String, Object> setVariable(Object variablesReference, String name, String value)
                throws Exception {
            return channel.request("setVariable", map("variablesReference", variablesReference,
                    "name", name, "value", value));
        }

        /** The raw setExpression response. */
        Map<String, Object> setExpression(Map<String, Object> frame, String expression, String value)
                throws Exception {
            return channel.request("setExpression", map("expression", expression,
                    "value", value, "frameId", frame.get("id")));
        }

        /** The Locals scope handle of a frame, for setVariable. */
        Object localsRef(Map<String, Object> frame) throws Exception {
            Map<String, Object> scopes = body(channel.request("scopes", map("frameId", frame.get("id"))));
            return list(scopes, "scopes").get(0).get("variablesReference");
        }

        void joinCleanly() throws Exception {
            thread.join(20_000);
            that(!thread.isAlive(), "session thread did not end after disconnect");
            that(runFailure == null, "run() threw: " + runFailure);
        }
    }

    private static void assertAllThreadsContinued(Session s, int threadId) {
        @SuppressWarnings("unchecked")
        Map<String, Object> b = (Map<String, Object>) s.channel.lastEvent("continued").get("body");
        eq(Boolean.TRUE, b.get("allThreadsContinued"), "allThreadsContinued");
        eq(threadId, intOf(b.get("threadId")), "continued threadId");
    }

    /** Evaluate at the breakpoint: shapes, paths, and the read-only refusals. */
    private static void checkEvaluate(Session s, Map<String, Object> frame) throws Exception {
        Map<String, Object> x = body(s.evaluate(frame, "x", "watch"));
        eq("40", x.get("result"), "x");
        eq("int", x.get("type"), "x type");
        eq(0, intOf(x.get("variablesReference")), "x is a leaf");

        eq("\"hi\nthere\"", body(s.evaluate(frame, "s", "repl")).get("result"), "string local");
        eq("20", body(s.evaluate(frame, "arr[1]", "watch")).get("result"), "array element");
        eq("\"v\"", body(s.evaluate(frame, "names['k']", "watch")).get("result"), "map key");
        eq("\"v\"", body(s.evaluate(frame, "names.k", "hover")).get("result"), "map .key");
        eq("7", body(s.evaluate(frame, "m[7]", "watch")).get("result"), "int-keyed map");
        eq("\"b\"", body(s.evaluate(frame, "list[1]", "watch")).get("result"), "list element");
        eq("\"box\"", body(s.evaluate(frame, "item.name", "watch")).get("result"), "field");
        eq("\"inner\"", body(s.evaluate(frame, "item.next.name", "watch")).get("result"),
                "field path");
        eq("null", body(s.evaluate(frame, "item.next.next", "watch")).get("result"), "null field");

        Map<String, Object> item = body(s.evaluate(frame, "item", "watch"));
        eq("Target$Item", item.get("type"), "object type");
        List<Map<String, Object>> kids = s.children(item);
        eq("name", kids.get(0).get("name"), "the result expands like the pane's variable");

        String readOnly = "only variable paths can be evaluated here "
                + "(the debugger does not run code in the application)";
        for (String bad : new String[] {"y + 1", "x + 1", "arr.length()", "item.toString()", "x = 3"}) {
            Map<String, Object> r = s.evaluate(frame, bad, "watch");
            eq(Boolean.FALSE, r.get("success"), bad + " must fail");
            eq(readOnly, r.get("message"), bad);
        }
        Map<String, Object> missing = s.evaluate(frame, "nope", "watch");
        eq(Boolean.FALSE, missing.get("success"), "undefined name fails");
        eq("'nope' is not defined in this frame", missing.get("message"), "undefined message");
        eq("'this' is not defined in this frame",
                s.evaluate(frame, "this", "watch").get("message"), "static main has no this");
        // y is declared on the breakpoint line itself, so it is not in scope yet.
        eq("'y' is not defined in this frame",
                s.evaluate(frame, "y", "watch").get("message"), "y is not in scope before its line runs");

        Map<String, Object> stale = s.channel.request("evaluate", map("expression", "x",
                "frameId", 999 << 16));
        eq(Boolean.FALSE, stale.get("success"), "unknown frame fails, does not throw");
    }

    static void run() {
        Path[] dirHolder = new Path[1];
        try {
            dirHolder[0] = compileTarget();
        } catch (Exception e) {
            test("compile the e2e target", () -> {
                throw e;
            });
            return;
        }
        Path dir = dirHolder[0];
        try {
            test("e2e: breakpoint, stack, variables, next, continue, disconnect, target exits 0", () -> {
                try (Target target = new Target(dir)) {
                    that(target.port != 5005, "must not use 5005");
                    Session s = new Session();
                    try {
                        int threadId = s.attachAndHitBreakpoint(target, dir);

                        Map<String, Object> frame = s.topFrame(threadId);
                        eq("Target.main", frame.get("name"), "frame name");
                        eq(BREAKPOINT_LINE, intOf(frame.get("line")), "frame line");
                        @SuppressWarnings("unchecked")
                        Map<String, Object> source = (Map<String, Object>) frame.get("source");
                        that(String.valueOf(source.get("path")).endsWith("Target.java"),
                                "source path: " + source);

                        List<Map<String, Object>> locals = s.locals(frame);
                        eq("40", named(locals, "x").get("value"), "x");
                        eq("\"hi\nthere\"", named(locals, "s").get("value"), "s keeps its newline");
                        that(locals.stream().noneMatch(v -> "<error>".equals(v.get("name"))),
                                "an <error> variable appeared: " + locals);

                        Map<String, Object> big = named(locals, "big");
                        eq("int[][500]", big.get("value"), "array summary");
                        List<Map<String, Object>> bigKids = s.children(big);
                        eq(201, bigKids.size(), "array capped at 200 + marker");
                        eq("300 more elements", bigKids.get(200).get("value"), "array remainder");

                        Map<String, Object> m = named(locals, "m");
                        that(String.valueOf(m.get("value")).endsWith("(300 entries)"),
                                "map summary: " + m.get("value"));
                        List<Map<String, Object>> mapKids = s.children(m);
                        eq(201, mapKids.size(), "map capped at 200 + marker");
                        eq("...", mapKids.get(200).get("name"), "map remainder marker");

                        checkEvaluate(s, frame);

                        body(s.channel.request("next", map("threadId", threadId)));
                        Map<String, Object> stepped = s.channel.awaitEvent("stopped", 20_000);
                        @SuppressWarnings("unchecked")
                        Map<String, Object> sb = (Map<String, Object>) stepped.get("body");
                        eq("step", sb.get("reason"), "step stop reason");
                        Map<String, Object> frame2 = s.topFrame(threadId);
                        eq(BREAKPOINT_LINE + 1, intOf(frame2.get("line")), "line after next");
                        eq("42", named(s.locals(frame2), "y").get("value"), "y after the step");
                        eq("42", body(s.evaluate(frame2, "y", "watch")).get("result"),
                                "evaluate y after the step");
                        // the same stack frame object asked again later, once more, is still fine
                        eq("42", body(s.evaluate(frame2, "y", "hover")).get("result"), "hover");

                        body(s.channel.request("continue", map("threadId", threadId)));
                        Map<String, Object> afterResume = s.evaluate(frame2, "y", "watch");
                        eq(Boolean.FALSE, afterResume.get("success"),
                                "a frame from before the resume must not evaluate: " + afterResume);
                        s.channel.awaitEvent("terminated", 20_000);

                        body(s.channel.request("disconnect", null));
                        s.joinCleanly();
                        that(target.process.waitFor(20, TimeUnit.SECONDS), "target did not exit");
                        eq(0, target.process.exitValue(), "target exit code");
                        that(target.output.stream().anyMatch(l -> l.equals("target done 43")),
                                "target output: " + target.output);
                    } finally {
                        s.channel.closeClient();
                    }
                }
            });

            test("e2e: next and continue each send one continued{allThreadsContinued} before the next stop", () -> {
                try (Target target = new Target(dir)) {
                    Session s = new Session();
                    try {
                        int threadId = s.attachAndHitBreakpoint(target, dir);

                        int mark = s.channel.mark();
                        body(s.channel.request("next", map("threadId", threadId)));
                        s.channel.awaitEvent("stopped", 20_000);
                        eq(java.util.Arrays.asList("response:next", "event:continued", "event:stopped"),
                                s.channel.since(mark), "message order around next");
                        assertAllThreadsContinued(s, threadId);

                        mark = s.channel.mark();
                        body(s.channel.request("continue", map("threadId", threadId)));
                        s.channel.awaitEvent("terminated", 20_000);
                        java.util.List<String> seen = s.channel.since(mark);
                        eq(1, java.util.Collections.frequency(seen, "event:continued"), "continued count: " + seen);
                        that(seen.indexOf("response:continue") < seen.indexOf("event:continued")
                                && seen.indexOf("event:continued") < seen.indexOf("event:terminated"),
                                "continue order: " + seen);
                        assertAllThreadsContinued(s, threadId);

                        body(s.channel.request("disconnect", null));
                        s.joinCleanly();
                    } finally {
                        s.channel.closeClient();
                    }
                }
            });

            test("e2e: setVariable/setExpression change the target's variables; refusals change nothing", () -> {
                try (Target target = new Target(dir)) {
                    Session s = new Session();
                    try {
                        Map<String, Object> init = body(s.channel.request("initialize", map()));
                        eq(Boolean.TRUE, init.get("supportsSetVariable"), "supportsSetVariable");
                        eq(Boolean.TRUE, init.get("supportsSetExpression"), "supportsSetExpression");
                        eq(Boolean.FALSE, init.get("supportsConditionalBreakpoints"), "conditional stays off");
                        int threadId = s.attachAndHitBreakpoint(target, dir);
                        Map<String, Object> frame = s.topFrame(threadId);
                        Object locals = s.localsRef(frame);
                        List<Map<String, Object>> before = s.locals(frame);
                        Object nums = named(before, "nums").get("variablesReference");
                        Object hold = named(before, "hold").get("variablesReference");

                        String needs = "changing it would need to create an object in the application, "
                                + "which the debugger does not do";
                        String[][] refused = {
                            {"boxed", "5", "'boxed' holds a java.lang.Integer; " + needs},
                            {"label", "7", "'label' is a java.lang.String; write the value in quotes, e.g. \"7\""},
                            {"x", "y + 1", "value must be a literal: a number, true or false, 'c', "
                                    + "\"text\" or null (the debugger does not run code in the application)"},
                            {"tiny", "300", "300 does not fit in byte"},
                            {"x", "3.5", "3.5 is not an int literal"},
                            {"x", "null", "cannot assign null to int"},
                            {"this", "null", "'this' cannot be changed"},
                            {"y", "1", "'y' is not a variable of this frame"},
                        };
                        for (String[] r : refused) {
                            Map<String, Object> resp = s.setVariable(locals, r[0], r[1]);
                            eq(Boolean.FALSE, resp.get("success"), r[0] + " <- " + r[1] + " must fail");
                            eq(r[2], resp.get("message"), r[0] + " <- " + r[1]);
                        }
                        Map<String, Object> fin = s.setVariable(hold, "fixed", "1");
                        eq("'fixed' is final", fin.get("message"), "final field");
                        eq("'[7]' is not an element the debugger shows",
                                s.setVariable(nums, "[7]", "1").get("message"), "past the end");
                        eq("only variable paths can be evaluated here "
                                + "(the debugger does not run code in the application)",
                                s.setExpression(frame, "x + 1", "5").get("message"), "expression target");
                        eq("'zz' is not defined in this frame",
                                s.setExpression(frame, "zz", "5").get("message"), "unknown target");
                        Map<String, Object> noFrame = s.channel.request("setExpression", map(
                                "expression", "x", "value", "1", "frameId", 999 << 16));
                        eq(Boolean.FALSE, noFrame.get("success"), "unknown frame fails, does not throw");
                        List<Map<String, Object>> unchanged = s.locals(frame);
                        eq("40", named(unchanged, "x").get("value"), "x unchanged by refusals");
                        eq("5", named(unchanged, "boxed").get("value"), "boxed unchanged");
                        eq("\"old\"", named(unchanged, "label").get("value"), "label unchanged");
                        eq("1", named(unchanged, "tiny").get("value"), "tiny unchanged");

                        // the writes: setVariable on the locals / an array / an object ...
                        eq("100", body(s.setVariable(locals, "x", "100")).get("value"), "x");
                        eq("true", body(s.setVariable(locals, "flag", "true")).get("value"), "flag");
                        Map<String, Object> ratio = body(s.setVariable(locals, "ratio", "2"));
                        eq("2.0", ratio.get("value"), "ratio re-read");
                        eq("double", ratio.get("type"), "ratio type");
                        eq("z", body(s.setVariable(locals, "ch", "'z'")).get("value"), "ch");
                        eq("\"new \"label\"\"", body(s.setVariable(locals, "label",
                                "\"new \\\"label\\\"\"")).get("value"), "label");
                        Map<String, Object> gone = body(s.setVariable(locals, "gone", "null"));
                        eq("null", gone.get("value"), "gone");
                        eq("java.lang.String", gone.get("type"), "a null keeps its declared type");
                        eq("99", body(s.setVariable(nums, "[1]", "99")).get("value"), "nums[1]");
                        eq("42", body(s.setVariable(hold, "count", "42")).get("value"), "hold.count");
                        // ... and setExpression, which is how Watch edits a value
                        eq("77", body(s.setExpression(frame, "nums[2]", "77")).get("value"), "nums[2]");
                        eq("5", body(s.setExpression(frame, "tiny", "5")).get("value"), "tiny");

                        // later reads see the new values: the pane (no cache) and evaluate
                        List<Map<String, Object>> after = s.locals(frame);
                        eq("100", named(after, "x").get("value"), "pane x");
                        eq("\"new \"label\"\"", named(after, "label").get("value"), "pane label");
                        eq("null", named(after, "gone").get("value"), "pane gone");
                        eq("99", named(s.children(named(after, "nums")), "[1]").get("value"), "pane nums[1]");
                        eq("100", body(s.evaluate(frame, "x", "watch")).get("result"), "evaluate x");
                        eq("77", body(s.evaluate(frame, "nums[2]", "watch")).get("result"), "evaluate nums[2]");
                        eq("42", body(s.evaluate(frame, "hold.count", "hover")).get("result"), "evaluate hold.count");

                        body(s.channel.request("continue", map("threadId", threadId)));
                        // a handle from before the resume must not write
                        Map<String, Object> late = s.setVariable(locals, "x", "1");
                        eq(Boolean.FALSE, late.get("success"), "a stale handle must not write: " + late);
                        eq("no stopped frame to change a variable in (the program is running, "
                                + "or has resumed since this frame was shown)", late.get("message"), "stale message");
                        Map<String, Object> lateExpr = s.setExpression(frame, "x", "1");
                        eq(Boolean.FALSE, lateExpr.get("success"), "setExpression after resume fails");

                        s.channel.awaitEvent("terminated", 20_000);
                        body(s.channel.request("disconnect", null));
                        s.joinCleanly();
                        that(target.process.waitFor(20, TimeUnit.SECONDS), "target did not exit");
                        eq(0, target.process.exitValue(), "target exit code");
                        that(target.output.contains("target done 103"),
                                "y is computed from the new x: " + target.output);
                        String printed = "after x=100 flag=true ratio=2.0 ch=z tiny=5 boxed=5 "
                                + "label=new \"label\" gone=null nums=1,99,77 count=42 fixed=9";
                        that(target.output.contains(printed),
                                "the target must print the new values, got: " + target.output);
                    } finally {
                        s.channel.closeClient();
                    }
                }
            });

            test("e2e: disconnect while stopped detaches, resumes the target, which exits 0", () -> {
                try (Target target = new Target(dir)) {
                    Session s = new Session();
                    try {
                        s.attachAndHitBreakpoint(target, dir);
                        body(s.channel.request("disconnect", null));
                        s.joinCleanly();
                        that(target.process.waitFor(20, TimeUnit.SECONDS),
                                "target stayed suspended after detach");
                        eq(0, target.process.exitValue(), "target exit code");
                        that(target.output.stream().anyMatch(l -> l.equals("target done 43")),
                                "target must run to completion: " + target.output);
                    } finally {
                        s.channel.closeClient();
                    }
                }
            });

            test("e2e: client hang-up (EOF) while stopped also detaches cleanly", () -> {
                try (Target target = new Target(dir)) {
                    Session s = new Session();
                    s.attachAndHitBreakpoint(target, dir);
                    s.channel.closeClient();
                    s.joinCleanly();
                    that(target.process.waitFor(20, TimeUnit.SECONDS),
                            "target stayed suspended after EOF");
                    eq(0, target.process.exitValue(), "target exit code");
                }
            });

            test("e2e: shutdown() from another thread detaches like a shutdown hook would", () -> {
                try (Target target = new Target(dir)) {
                    Session s = new Session();
                    try {
                        s.attachAndHitBreakpoint(target, dir);
                        s.session.shutdown();
                        that(target.process.waitFor(20, TimeUnit.SECONDS),
                                "target stayed suspended after shutdown()");
                        eq(0, target.process.exitValue(), "target exit code");
                        s.session.shutdown(); // idempotent
                    } finally {
                        s.channel.closeClient();
                        s.thread.join(10_000);
                    }
                }
            });
        } finally {
            try (var walk = Files.walk(dir)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            } catch (IOException ignored) {
                // temp dir; the OS will get it
            }
        }
    }
}
