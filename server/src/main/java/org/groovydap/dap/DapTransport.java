package org.groovydap.dap;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The DAP wire format: {@code Content-Length: N\r\n\r\n} followed by N bytes of
 * UTF-8 JSON.
 *
 * <p>Headers are ASCII and the length counts bytes, not characters, so the input
 * has to be read as bytes and decoded only once the body is complete -- wrapping
 * stdin in a Reader would mis-count any non-ASCII payload.
 *
 * <p>{@link #send} is synchronized because two threads write to the client: the
 * request loop answering requests, and the JDI event thread pushing {@code
 * stopped} / {@code thread} events.
 */
public final class DapTransport {

    private final InputStream in;
    private final OutputStream out;
    private final Object writeLock = new Object();
    private int nextSeq = 1;

    public DapTransport(InputStream in, OutputStream out) {
        this.in = in;
        this.out = out;
    }

    /** Reads one message, or returns null once the client closes the stream. */
    public Map<String, Object> receive() throws IOException {
        int contentLength = -1;
        while (true) {
            String header = readHeaderLine();
            if (header == null) {
                return null; // clean EOF between messages
            }
            if (header.isEmpty()) {
                break; // blank line ends the header block
            }
            int colon = header.indexOf(':');
            if (colon > 0 && header.substring(0, colon).trim().equalsIgnoreCase("Content-Length")) {
                contentLength = Integer.parseInt(header.substring(colon + 1).trim());
            }
        }
        if (contentLength < 0) {
            throw new IOException("DAP message without a Content-Length header");
        }

        byte[] body = new byte[contentLength];
        int read = 0;
        while (read < contentLength) {
            int n = in.read(body, read, contentLength - read);
            if (n < 0) {
                throw new EOFException("stream closed mid-message");
            }
            read += n;
        }
        return Json.parseObject(new String(body, StandardCharsets.UTF_8));
    }

    private String readHeaderLine() throws IOException {
        StringBuilder sb = new StringBuilder();
        while (true) {
            int c = in.read();
            if (c < 0) {
                return sb.length() == 0 ? null : sb.toString();
            }
            if (c == '\n') {
                int len = sb.length();
                if (len > 0 && sb.charAt(len - 1) == '\r') {
                    sb.setLength(len - 1);
                }
                return sb.toString();
            }
            sb.append((char) c);
        }
    }

    public void sendResponse(Map<String, Object> request, Map<String, Object> body) {
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

    public void sendError(Map<String, Object> request, String message) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", "response");
        msg.put("request_seq", request.get("seq"));
        msg.put("success", Boolean.FALSE);
        msg.put("command", request.get("command"));
        msg.put("message", message);
        send(msg);
    }

    public void sendEvent(String event, Map<String, Object> body) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", "event");
        msg.put("event", event);
        if (body != null) {
            msg.put("body", body);
        }
        send(msg);
    }

    public void send(Map<String, Object> message) {
        synchronized (writeLock) {
            message.put("seq", nextSeq++);
            byte[] body = Json.write(message).getBytes(StandardCharsets.UTF_8);
            try {
                out.write(("Content-Length: " + body.length + "\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                out.write(body);
                out.flush();
            } catch (IOException e) {
                // The client is gone; nothing useful is left to say to it.
                throw new IllegalStateException("failed to write to the DAP client", e);
            }
        }
    }
}
