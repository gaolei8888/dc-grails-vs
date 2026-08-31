package org.groovydap.jdi;

import com.sun.jdi.Location;
import com.sun.jdi.ThreadReference;

import java.util.HashMap;
import java.util.Map;

/**
 * Suppresses the second stop that one source line produces in a single call.
 *
 * <p>Groovy 4 compiles every statement twice -- a callsite/metaclass path and a
 * primitive path, chosen at run time by
 * {@code BytecodeInterface8.isOrigInt() && !__$stMC && !disabledStandardMetaClass()}
 * -- and both copies carry the same line number. Measured on a Grails 7.2.3 app
 * (design doc §7.2):
 *
 * <ul>
 *   <li>{@code int base = seed + 100} maps to bci 20 (the dispatch test itself)
 *       and bci 79 (the fast path). A single call passes through both, so a naive
 *       adapter stops twice on one line.</li>
 *   <li>{@code int x = seed * 3} maps to bci 26 (slow) and bci 73 (fast), and only
 *       bci 73 ever executes.</li>
 * </ul>
 *
 * <p>The second case is why every location has to be armed: keeping only the
 * lowest bci would arm the path that never runs, and the breakpoint would simply
 * never fire. So the duplicate is removed here instead, at the point of the stop.
 *
 * <p>The rule: a stop is a duplicate when it repeats the previous stop's thread,
 * method, line and stack depth but at a <em>different</em> bytecode index, and
 * nothing was reported in between. A loop that calls the same method repeatedly
 * re-enters at the same bci each time, so it is never mistaken for a duplicate.
 * The one case this gets wrong is a method whose dispatch flips paths between two
 * consecutive calls at the same depth -- which needs the metaclass to change
 * mid-run -- and it costs one missed stop, not a wrong one.
 */
public final class StopDeduper {

    private static final class Stop {
        final String method;
        final int line;
        final int depth;
        final long codeIndex;

        Stop(String method, int line, int depth, long codeIndex) {
            this.method = method;
            this.line = line;
            this.depth = depth;
            this.codeIndex = codeIndex;
        }
    }

    private final Map<Long, Stop> lastReported = new HashMap<>();

    /**
     * @return true if this event should be reported to the client, false if it is
     *         the second half of a line already reported.
     */
    public synchronized boolean shouldReport(ThreadReference thread, Location location, int depth) {
        long id = thread.uniqueID();
        String method = location.declaringType().name() + "." + location.method().name();
        Stop previous = lastReported.get(id);

        if (previous != null
                && previous.line == location.lineNumber()
                && previous.depth == depth
                && previous.method.equals(method)
                && previous.codeIndex != location.codeIndex()) {
            // Keep the record rather than clearing it: a line can have more than
            // two locations, and the next call to the same method re-enters at the
            // same bci as the one recorded here, which is what tells a fresh call
            // apart from the tail of this one.
            return false;
        }

        lastReported.put(id, new Stop(method, location.lineNumber(), depth, location.codeIndex()));
        return true;
    }

    public synchronized void forget(ThreadReference thread) {
        lastReported.remove(thread.uniqueID());
    }

    public synchronized void clear() {
        lastReported.clear();
    }
}
