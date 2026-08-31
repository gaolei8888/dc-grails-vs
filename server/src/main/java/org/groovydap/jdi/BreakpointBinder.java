package org.groovydap.jdi;

import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.Location;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.EventRequestManager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Turns "file plus line" into JDI breakpoints without parsing any Groovy.
 *
 * <p>The algorithm (design doc §4): narrow the loaded classes by name prefix,
 * confirm with {@code sourceName()}, and let {@code locationsOfLine(n)} decide
 * ownership. Closures need no special handling -- a synthetic closure class
 * reports the same {@code sourceName()} as the class that encloses it and holds
 * the line numbers for its own body -- and classes that are not loaded yet are
 * caught by a {@code ClassPrepareRequest} on {@code prefix + "*"}.
 *
 * <p>Two things measured on a real Grails 7.2.3 application shape this code:
 *
 * <ul>
 *   <li><b>Every location of a line gets a request.</b> Groovy emits two code
 *       paths per statement under the same line number, and the one that runs is
 *       not always the first: {@code int x = seed * 3} maps to bci 26 and bci 73
 *       and only bci 73 ever executes. Arming just the lowest bci produces a
 *       breakpoint that can never fire. The duplicate stops this causes are
 *       removed by {@link StopDeduper}, not here.</li>
 *   <li><b>Class names prove nothing.</b> {@code Foo$_bar_closure1} may be the
 *       transaction callback that {@code @Transactional} generates rather than a
 *       user closure, and {@code Foo$bar}, {@code Foo$bar$0} carry no source
 *       information at all. Both fall out for free: the first owns no lines, the
 *       second throws {@link AbsentInformationException}.</li>
 * </ul>
 */
public final class BreakpointBinder {

    /** One breakpoint the client asked for, and everything armed on its behalf. */
    private static final class Bp {
        final int id;
        final int requestedLine;
        int line;
        boolean verified;
        String message;

        /**
         * Requests, kept per class name rather than in one list.
         *
         * <p>A class can be prepared more than once in the life of a session. Spring
         * Boot devtools restarts the application in a new class loader whenever the
         * classpath changes, which prepares every application class again -- and the
         * standing ClassPrepareRequest fires again, so the breakpoint rebinds to the
         * new class by itself. Measured: a breakpoint hit, a restart, and the same
         * breakpoint hit again with no intervention.
         *
         * <p>What that leaves behind is the previous requests, pointing at classes
         * nobody can reach any more, two of them per restart. Keying by name lets
         * the old ones go when the name comes back.
         */
        final Map<String, List<BreakpointRequest>> requestsByClass = new LinkedHashMap<>();

        Bp(int id, int requestedLine, int line) {
            this.id = id;
            this.requestedLine = requestedLine;
            this.line = line;
        }

        List<BreakpointRequest> allRequests() {
            List<BreakpointRequest> all = new ArrayList<>();
            for (List<BreakpointRequest> perClass : requestsByClass.values()) {
                all.addAll(perClass);
            }
            return all;
        }
    }

    private static final class SourceState {
        final SourceRef ref;
        final List<Bp> breakpoints = new ArrayList<>();
        final List<ClassPrepareRequest> classPrepareRequests = new ArrayList<>();

        SourceState(SourceRef ref) {
            this.ref = ref;
        }
    }

    private final VirtualMachine vm;
    private final EventRequestManager erm;
    private final Consumer<String> log;
    private final Consumer<Map<String, Object>> breakpointChanged;

    private final Map<String, SourceState> bySource = new LinkedHashMap<>();
    private final Map<BreakpointRequest, Bp> owners = new HashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(1);

    public BreakpointBinder(VirtualMachine vm,
                            Consumer<String> log,
                            Consumer<Map<String, Object>> breakpointChanged) {
        this.vm = vm;
        this.erm = vm.eventRequestManager();
        this.log = log;
        this.breakpointChanged = breakpointChanged;
    }

    /**
     * Replaces every breakpoint in one source file.
     *
     * @return the DAP {@code Breakpoint} objects to answer {@code setBreakpoints} with
     */
    public synchronized List<Map<String, Object>> setBreakpoints(String path, List<Integer> lines)
            throws IOException {
        String key = key(path);
        SourceState previous = bySource.remove(key);
        if (previous != null) {
            removeAll(previous);
        }

        SourceRef ref = SourceRef.of(path);
        SourceState state = new SourceState(ref);
        bySource.put(key, state);

        for (int requested : lines) {
            state.breakpoints.add(new Bp(nextId.getAndIncrement(), requested, requested));
        }

        // Classes already loaded. Filter by name before asking anything else --
        // allClasses() brings the names back in one reply, sourceName() is a round
        // trip per class.
        for (ReferenceType type : vm.allClasses()) {
            if (ref.mayOwn(type.name())) {
                install(state, type);
            }
        }

        // Everything not loaded yet, including closure classes, which only appear
        // once the method that declares them first runs.
        for (String filter : ref.classPrepareFilters()) {
            ClassPrepareRequest request = erm.createClassPrepareRequest();
            request.addClassFilter(filter);
            request.setSuspendPolicy(EventRequest.SUSPEND_ALL);
            request.enable();
            state.classPrepareRequests.add(request);
        }

        // A line the JVM refuses -- a method signature, a blank line, a brace --
        // slides down to the first line that could hold code. This runs only after
        // the exact bind failed against every loaded class, so a line that really
        // does carry code is never moved.
        for (Bp bp : state.breakpoints) {
            if (!bp.verified) {
                trySnap(state, bp);
            }
        }

        List<Map<String, Object>> result = new ArrayList<>(state.breakpoints.size());
        for (Bp bp : state.breakpoints) {
            result.add(describe(bp, ref));
        }
        return result;
    }

    /** A class just loaded: arm anything waiting on it. */
    public synchronized void onClassPrepare(ReferenceType type) {
        String name = type.name();
        for (SourceState state : bySource.values()) {
            if (state.ref.mayOwn(name)) {
                install(state, type);
            }
        }
    }

    /** The breakpoint ids to report on a {@code stopped} event, or an empty list. */
    public synchronized List<Integer> idsFor(BreakpointRequest request) {
        Bp bp = owners.get(request);
        return bp == null ? new ArrayList<>() : new ArrayList<>(List.of(bp.id));
    }

    public synchronized void clearAll() {
        for (SourceState state : bySource.values()) {
            removeAll(state);
        }
        bySource.clear();
        owners.clear();
    }

    // ------------------------------------------------------------------ internals

    private void install(SourceState state, ReferenceType type) {
        String sourceName;
        try {
            sourceName = type.sourceName();
        } catch (AbsentInformationException e) {
            // Grails generates helper classes (Foo$bar, Foo$bar$0) with no source
            // attribute at all. Nothing to bind; not an error.
            return;
        }
        if (!state.ref.fileName().equals(sourceName)) {
            return;
        }

        for (Bp bp : state.breakpoints) {
            installOne(state, type, bp, bp.line);
        }
    }

    private boolean installOne(SourceState state, ReferenceType type, Bp bp, int line) {
        List<Location> locations;
        try {
            locations = type.locationsOfLine(line);
        } catch (AbsentInformationException e) {
            return false;
        }
        if (locations.isEmpty()) {
            return false;
        }

        boolean wasVerified = bp.verified;
        // The same class name coming back means it was prepared again -- a devtools
        // restart, most often. Its old requests point into a class loader nobody
        // holds any more, so let them go before arming the new ones.
        List<BreakpointRequest> previous = bp.requestsByClass.remove(type.name());
        if (previous != null) {
            log.accept(String.format("%s was prepared again; dropping %d stale request(s)",
                    type.name(), previous.size()));
            for (BreakpointRequest stale : previous) {
                owners.remove(stale);
                try {
                    erm.deleteEventRequest(stale);
                } catch (RuntimeException e) {
                    // the VM has already discarded it along with the class
                }
            }
        }

        List<BreakpointRequest> installed = new ArrayList<>();
        bp.requestsByClass.put(type.name(), installed);
        for (Location location : locations) {
            BreakpointRequest request = erm.createBreakpointRequest(location);
            request.setSuspendPolicy(EventRequest.SUSPEND_ALL);
            request.enable();
            installed.add(request);
            owners.put(request, bp);
            log.accept(String.format("bound %s:%d -> %s.%s (bci %d)",
                    state.ref.fileName(), line, type.name(),
                    location.method().name(), location.codeIndex()));
        }
        bp.verified = true;
        bp.message = null;
        if (!wasVerified) {
            breakpointChanged.accept(describe(bp, state.ref));
        }
        return true;
    }

    /**
     * Slides an unbindable breakpoint down to the first line that could hold code
     * and tries again against the classes already loaded.
     */
    private void trySnap(SourceState state, Bp bp) {
        int snapped = state.ref.firstExecutableLineAtOrAfter(bp.requestedLine);
        if (snapped == bp.requestedLine) {
            // The line looks executable, so the class that owns it is probably just
            // not loaded yet -- a closure body, most often. Leave it to
            // ClassPrepare and say nothing misleading in the meantime.
            bp.message = "not bound yet: no class from " + state.ref.fileName()
                    + " that owns line " + bp.requestedLine + " is loaded";
            return;
        }

        int original = bp.line;
        bp.line = snapped;
        for (ReferenceType type : vm.allClasses()) {
            if (!state.ref.mayOwn(type.name())) {
                continue;
            }
            String sourceName;
            try {
                sourceName = type.sourceName();
            } catch (AbsentInformationException e) {
                continue;
            }
            if (state.ref.fileName().equals(sourceName) && installOne(state, type, bp, snapped)) {
                return;
            }
        }
        if (!bp.verified) {
            // Keep the moved line: the class owning it may still load later, and
            // the original line has no bytecode either way.
            bp.line = snapped;
            bp.message = "line " + original + " has no code; moved to " + snapped;
        }
    }

    private void removeAll(SourceState state) {
        for (Bp bp : state.breakpoints) {
            for (BreakpointRequest request : bp.allRequests()) {
                owners.remove(request);
                try {
                    erm.deleteEventRequest(request);
                } catch (RuntimeException e) {
                    // The VM may already be gone; there is nothing to clean up then.
                }
            }
            bp.requestsByClass.clear();
        }
        for (ClassPrepareRequest request : state.classPrepareRequests) {
            try {
                erm.deleteEventRequest(request);
            } catch (RuntimeException e) {
                // as above
            }
        }
        state.classPrepareRequests.clear();
    }

    private Map<String, Object> describe(Bp bp, SourceRef ref) {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("name", ref.fileName());
        source.put("path", ref.path().toString());

        Map<String, Object> breakpoint = new LinkedHashMap<>();
        breakpoint.put("id", bp.id);
        breakpoint.put("verified", bp.verified);
        breakpoint.put("line", bp.line);
        breakpoint.put("source", source);
        if (bp.message != null) {
            breakpoint.put("message", bp.message);
        }
        return breakpoint;
    }

    private static String key(String path) {
        // Windows paths differ in case and separator between the editor and here.
        return path.replace('\\', '/').toLowerCase(Locale.ROOT);
    }
}
