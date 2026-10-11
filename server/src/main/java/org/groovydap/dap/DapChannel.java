package org.groovydap.dap;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a debug session needs from its client: a stream of requests in, and
 * messages out. {@link DapTransport} is the stdio implementation; an in-process
 * client can supply a queue-backed one instead.
 *
 * <p>{@link #send} may be called from two threads (the request loop and the JDI
 * event thread), so implementations must make it thread-safe, and it stamps the
 * {@code seq} itself.
 */
public interface DapChannel {

    /** Reads one request, or returns null once the client is gone. */
    Map<String, Object> receive() throws IOException;

    void send(Map<String, Object> message);

    default void sendResponse(Map<String, Object> request, Map<String, Object> body) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", "response");
        msg.put("request_seq", request.get("seq"));
        msg.put("success", Boolean.TRUE);
        msg.put("command", request.get("command"));
        if (body != null) {
            msg.put("body", body);
        }
        send(msg);
    }

    default void sendError(Map<String, Object> request, String message) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", "response");
        msg.put("request_seq", request.get("seq"));
        msg.put("success", Boolean.FALSE);
        msg.put("command", request.get("command"));
        msg.put("message", message);
        send(msg);
    }

    default void sendEvent(String event, Map<String, Object> body) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", "event");
        msg.put("event", event);
        if (body != null) {
            msg.put("body", body);
        }
        send(msg);
    }
}
