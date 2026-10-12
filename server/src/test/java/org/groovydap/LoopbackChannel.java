package org.groovydap;

import org.groovydap.dap.DapChannel;
import org.groovydap.dap.Json;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A DapChannel over two blocking queues, plus a small client on the other end.
 * Messages are pushed through Json.write/parse so the test sees what a real
 * client would, not shared mutable maps.
 */
final class LoopbackChannel implements DapChannel {

    private static final boolean TRACE = System.getenv("DAP_TRACE") != null;
    private static final Map<String, Object> EOF = new LinkedHashMap<>();

    private final BlockingQueue<Map<String, Object>> toServer = new LinkedBlockingQueue<>();
    private final BlockingQueue<Map<String, Object>> toClient = new LinkedBlockingQueue<>();
    private final AtomicInteger serverSeq = new AtomicInteger(1);
    private final AtomicInteger clientSeq = new AtomicInteger(1);
    private final java.util.List<Map<String, Object>> events = new java.util.ArrayList<>();
    private final java.util.List<Map<String, Object>> arrivals = new java.util.ArrayList<>();

    // ---- server side

    @Override
    public Map<String, Object> receive() throws IOException {
        try {
            Map<String, Object> next = toServer.take();
            return next == EOF ? null : next;
        } catch (InterruptedException e) {
            throw new IOException(e);
        }
    }

    @Override
    public void send(Map<String, Object> message) {
        message.put("seq", serverSeq.getAndIncrement());
        if (TRACE) {
            System.err.println("<- " + Json.write(message));
        }
        toClient.add(Json.parseObject(Json.write(message)));
    }

    // ---- client side

    /** Sends a request and returns its response; events seen meanwhile are kept. */
    Map<String, Object> request(String command, Map<String, Object> arguments) throws Exception {
        Map<String, Object> msg = new LinkedHashMap<>();
        int seq = clientSeq.getAndIncrement();
        msg.put("seq", seq);
        msg.put("type", "request");
        msg.put("command", command);
        if (arguments != null) {
            msg.put("arguments", arguments);
        }
        if (TRACE) {
            System.err.println("-> " + Json.write(msg));
        }
        toServer.add(Json.parseObject(Json.write(msg)));
        long deadline = System.currentTimeMillis() + 30_000;
        while (true) {
            Map<String, Object> m = poll(deadline);
            if ("response".equals(m.get("type")) && Long.valueOf(seq).equals(
                    ((Number) m.get("request_seq")).longValue())) {
                return m;
            }
            if ("event".equals(m.get("type"))) {
                events.add(m);
            }
        }
    }

    /** The next event with this name (earlier ones are consumed), or fails after the timeout. */
    Map<String, Object> awaitEvent(String name, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            for (java.util.Iterator<Map<String, Object>> it = events.iterator(); it.hasNext();) {
                Map<String, Object> e = it.next();
                if (name.equals(e.get("event"))) {
                    it.remove();
                    return e;
                }
            }
            Map<String, Object> m = poll(deadline);
            if ("event".equals(m.get("type"))) {
                events.add(m);
            }
        }
    }

    /** Every message the client has read so far, in arrival order; pair with {@link #since}. */
    int mark() {
        return arrivals.size();
    }

    /** "response:next" / "event:stopped" for each message read after the mark. */
    java.util.List<String> since(int mark) {
        java.util.List<String> names = new java.util.ArrayList<>();
        for (Map<String, Object> m : arrivals.subList(mark, arrivals.size())) {
            names.add(m.get("type") + ":" + ("response".equals(m.get("type")) ? m.get("command") : m.get("event")));
        }
        return names;
    }

    /** Every event of this name that has arrived, in order. */
    java.util.List<Map<String, Object>> events(String name) {
        java.util.List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (Map<String, Object> m : new java.util.ArrayList<>(arrivals)) {
            if (name.equals(m.get("event"))) {
                out.add(m);
            }
        }
        return out;
    }

    Map<String, Object> lastEvent(String name) {
        for (int i = arrivals.size() - 1; i >= 0; i--) {
            if (name.equals(arrivals.get(i).get("event"))) {
                return arrivals.get(i);
            }
        }
        return null;
    }

    boolean sawEvent(String name) {
        for (Map<String, Object> e : events) {
            if (name.equals(e.get("event"))) {
                return true;
            }
        }
        return false;
    }

    void closeClient() {
        toServer.add(EOF);
    }

    private Map<String, Object> poll(long deadline) throws Exception {
        Map<String, Object> m = toClient.poll(Math.max(1, deadline - System.currentTimeMillis()),
                TimeUnit.MILLISECONDS);
        if (m == null) {
            throw new AssertionError("timed out waiting for the adapter; pending events: " + events);
        }
        arrivals.add(m);
        return m;
    }
}
