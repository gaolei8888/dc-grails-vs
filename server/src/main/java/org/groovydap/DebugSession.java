package org.groovydap;

import com.sun.jdi.Bootstrap;
import com.sun.jdi.Location;
import com.sun.jdi.StackFrame;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.VMDisconnectedException;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.ClassPrepareEvent;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventQueue;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.event.StepEvent;
import com.sun.jdi.event.VMDeathEvent;
import com.sun.jdi.event.VMDisconnectEvent;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.StepRequest;
import org.groovydap.dap.DapTransport;
import org.groovydap.jdi.BreakpointBinder;
import org.groovydap.jdi.SourceLocator;
import org.groovydap.jdi.StopDeduper;
import org.groovydap.jdi.Variables;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One debug session: the DAP request loop on one side, a JDI connection to the
 * target JVM on the other.
 *
 * <p>Attach only. That restriction is what makes the whole thing tractable --
 * with a live VM to ask, {@code locationsOfLine} answers "does this class own
 * this source line", and no Groovy has to be parsed to bind a breakpoint. See
 * docs/2026-08-29-vscode-groovy-debug-adapter.md §4.
 */
public final class DebugSession {

    /**
     * Frames the user did not write. Stepping passes through these -- Groovy
     * dispatches every call through the metaclass or an invokedynamic call site --
     * but never stops in them. Measured in the T0 spike: a plain method call is
     * buried under {@code LambdaForm$MH} and {@code IndyInterface.fromCache},
     * while a closure call arrives through {@code NativeMethodAccessorImpl},
     * {@code CachedMethod.invoke} and {@code ClosureMetaClass.invokeMethod}.
     */
    private static final List<String> DEFAULT_STEP_EXCLUDES = List.of(
        "org.codehaus.groovy.*",
        "groovy.lang.*",
        "groovy.util.*",
        "java.lang.invoke.*",
        "java.lang.reflect.*",
        "jdk.internal.reflect.*",
        "sun.reflect.*",
        // Stepping off the end of a @Transactional method lands in the template
        // that wraps it -- measured against Grails 7.2.3, where step-over out of
        // the method walked into GrailsTransactionTemplate and then Spring's
        // TransactionTemplate.
        "grails.gorm.transactions.*",
        "org.grails.datastore.*",
        "org.springframework.transaction.*",
        "org.springframework.aop.*",
        "org.springframework.cglib.*");

    /** Guard against stepping forever through generated code with no line table. */
    private static final int MAX_AUTOMATIC_RESTEPS = 200;

    private final DapTransport transport;

    private VirtualMachine vm;
    private BreakpointBinder binder;
    private SourceLocator sources = new SourceLocator(null);
    private final StopDeduper deduper = new StopDeduper();
    private final Variables variables = new Variables();

    private final Map<Long, Integer> threadIds = new ConcurrentHashMap<>();
    private final Map<Integer, ThreadReference> threadsById = new ConcurrentHashMap<>();
    private final AtomicInteger nextThreadId = new AtomicInteger(1);

    /** The event set holding the VM suspended, owed a resume when we continue. */
    private volatile EventSet suspendedSet;
    private volatile ThreadReference stoppedThread;
    private volatile boolean running = true;
    private volatile boolean configured;

    private List<String> stepExcludes = DEFAULT_STEP_EXCLUDES;
    private boolean trace;
    private final Map<Long, Integer> stepDepths = new ConcurrentHashMap<>();
    private final Map<Long, Integer> restepBudget = new ConcurrentHashMap<>();

    public DebugSession(InputStream in, OutputStream out) {
        this.transport = new DapTransport(in, out);
    }

    public void run() throws IOException {
        while (running) {
            Map<String, Object> request = transport.receive();
            if (request == null) {
                break;
            }
            try {
                dispatch(request);
            } catch (Exception e) {
                transport.sendError(request, e.getClass().getSimpleName()
                        + (e.getMessage() == null ? "" : ": " + e.getMessage()));
            }
        }
        detach();
    }

    // ------------------------------------------------------------------ requests

    private void dispatch(Map<String, Object> request) throws Exception {
        String command = String.valueOf(request.get("command"));
        switch (command) {
            case "initialize": onInitialize(request); break;
            case "attach": onAttach(request); break;
            case "setBreakpoints": onSetBreakpoints(request); break;
            case "setExceptionBreakpoints": transport.sendResponse(request, null); break;
            case "configurationDone": onConfigurationDone(request); break;
            case "threads": onThreads(request); break;
            case "stackTrace": onStackTrace(request); break;
            case "scopes": onScopes(request); break;
            case "variables": onVariables(request); break;
            case "continue": onContinue(request); break;
            case "next": onStep(request, StepRequest.STEP_OVER); break;
            case "stepIn": onStep(request, StepRequest.STEP_INTO); break;
            case "stepOut": onStep(request, StepRequest.STEP_OUT); break;
            case "pause": onPause(request); break;
            case "evaluate": transport.sendError(request,
                    "expression evaluation is not implemented yet"); break;
            case "disconnect":
            case "terminate":
                transport.sendResponse(request, null);
                running = false;
                break;
            default:
                transport.sendError(request, "unsupported request: " + command);
        }
    }

    private void onInitialize(Map<String, Object> request) {
        Map<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put("supportsConfigurationDoneRequest", Boolean.TRUE);
        capabilities.put("supportsTerminateRequest", Boolean.TRUE);
        // Not yet: conditional breakpoints need Groovy expressions compiled and
        // evaluated inside the target VM, which is the T2 work item.
        capabilities.put("supportsConditionalBreakpoints", Boolean.FALSE);
        capabilities.put("supportsEvaluateForHovers", Boolean.FALSE);
        capabilities.put("supportsSetVariable", Boolean.FALSE);
        transport.sendResponse(request, capabilities);
    }

    private void onAttach(Map<String, Object> request) throws Exception {
        Map<String, Object> args = arguments(request);
        String host = args.containsKey("hostName")
                ? String.valueOf(args.get("hostName")) : "localhost";
        String port = String.valueOf(number(args.get("port"), 5005));
        long timeoutMs = number(args.get("timeout"), 30_000);

        vm = attachWithRetry(host, port, timeoutMs);
        binder = new BreakpointBinder(vm, this::log, this::sendBreakpointChanged);
        sources = new SourceLocator(strings(args.get("sourcePaths")));
        trace = Boolean.TRUE.equals(args.get("trace"));
        List<String> configuredExcludes = strings(args.get("stepFilters"));
        if (!configuredExcludes.isEmpty()) {
            stepExcludes = configuredExcludes;
        }

        log("attached to " + host + ":" + port + " (" + vm.name() + " " + vm.version() + ")");
        transport.sendResponse(request, null);

        startEventLoop();

        // Only now is it safe to accept setBreakpoints: binding asks the live VM
        // which classes own which lines.
        transport.sendEvent("initialized", null);
    }

    private VirtualMachine attachWithRetry(String host, String port, long timeoutMs)
            throws Exception {
        AttachingConnector connector = null;
        for (AttachingConnector candidate : Bootstrap.virtualMachineManager().attachingConnectors()) {
            if ("com.sun.jdi.SocketAttach".equals(candidate.name())) {
                connector = candidate;
                break;
            }
        }
        if (connector == null) {
            throw new IllegalStateException(
                    "no SocketAttach connector -- start this jar with --add-modules jdk.jdi");
        }
        Map<String, Connector.Argument> args = connector.defaultArguments();
        args.get("hostname").setValue(host);
        args.get("port").setValue(port);

        long deadline = System.currentTimeMillis() + timeoutMs;
        Exception last = null;
        while (true) {
            try {
                return connector.attach(args);
            } catch (Exception e) {
                last = e;
                if (System.currentTimeMillis() >= deadline) {
                    throw new IOException("could not attach to " + host + ":" + port
                            + " within " + timeoutMs + "ms: " + last, last);
                }
                Thread.sleep(200);
            }
        }
    }

    private void onSetBreakpoints(Map<String, Object> request) throws Exception {
        requireVm();
        Map<String, Object> args = arguments(request);
        Map<?, ?> source = (Map<?, ?>) args.get("source");
        String path = source == null ? null : String.valueOf(source.get("path"));
        if (path == null || path.equals("null")) {
            transport.sendError(request, "setBreakpoints without a source path");
            return;
        }

        List<Integer> lines = new ArrayList<>();
        Object raw = args.get("breakpoints");
        if (raw instanceof List) {
            for (Object item : (List<?>) raw) {
                if (item instanceof Map) {
                    lines.add((int) number(((Map<?, ?>) item).get("line"), 0));
                }
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("breakpoints", binder.setBreakpoints(path, lines));
        transport.sendResponse(request, body);
    }

    private void onConfigurationDone(Map<String, Object> request) {
        transport.sendResponse(request, null);
        configured = true;
        // The target may have been started with suspend=y and be waiting for us.
        resumeTarget();
    }

    private void onThreads(Map<String, Object> request) {
        List<Map<String, Object>> threads = new ArrayList<>();
        if (vm != null) {
            for (ThreadReference thread : safeAllThreads()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("id", idOf(thread));
                entry.put("name", safeThreadName(thread));
                threads.add(entry);
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("threads", threads);
        transport.sendResponse(request, body);
    }

    private void onStackTrace(Map<String, Object> request) {
        Map<String, Object> args = arguments(request);
        ThreadReference thread = threadsById.get((int) number(args.get("threadId"), -1));
        List<Map<String, Object>> frames = new ArrayList<>();
        int total = 0;
        if (thread != null) {
            try {
                List<StackFrame> stack = thread.frames();
                total = stack.size();
                int start = (int) number(args.get("startFrame"), 0);
                long levels = number(args.get("levels"), 0);
                int end = levels <= 0 ? total : Math.min(total, start + (int) levels);
                for (int i = start; i < end; i++) {
                    frames.add(describeFrame(thread, i, stack.get(i)));
                }
            } catch (Exception e) {
                log("stackTrace failed: " + e);
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("stackFrames", frames);
        body.put("totalFrames", total);
        transport.sendResponse(request, body);
    }

    private Map<String, Object> describeFrame(ThreadReference thread, int index, StackFrame frame) {
        Location location = frame.location();
        Map<String, Object> entry = new LinkedHashMap<>();
        // The frame id has to survive a round trip and identify both the thread and
        // the depth; DAP hands it back verbatim in scopes.
        entry.put("id", frameId(thread, index));
        entry.put("name", location.declaringType().name() + "." + location.method().name());
        entry.put("line", Math.max(location.lineNumber(), 0));
        entry.put("column", 1);

        Path file = sources.find(location);
        if (file != null) {
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("name", file.getFileName().toString());
            source.put("path", file.toString());
            entry.put("source", source);
        } else {
            entry.put("presentationHint", "subtle");
        }
        return entry;
    }

    private void onScopes(Map<String, Object> request) {
        Map<String, Object> args = arguments(request);
        int frameId = (int) number(args.get("frameId"), -1);
        ThreadReference thread = threadsById.get(frameId >> 16);
        int frameIndex = frameId & 0xFFFF;

        List<Map<String, Object>> scopes = new ArrayList<>();
        if (thread != null) {
            Map<String, Object> locals = new LinkedHashMap<>();
            locals.put("name", "Locals");
            locals.put("variablesReference", variables.localsHandle(thread, frameIndex));
            locals.put("expensive", Boolean.FALSE);
            scopes.add(locals);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("scopes", scopes);
        transport.sendResponse(request, body);
    }

    private void onVariables(Map<String, Object> request) {
        Map<String, Object> args = arguments(request);
        int reference = (int) number(args.get("variablesReference"), 0);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("variables", variables.children(reference));
        transport.sendResponse(request, body);
    }

    private void onContinue(Map<String, Object> request) {
        Map<String, Object> args = arguments(request);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("allThreadsContinued", Boolean.TRUE);
        transport.sendResponse(request, body);
        resumeTarget();

        // The event needs the thread the client asked about; the response does not
        // carry one, so it cannot be reused here.
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("threadId", (int) number(args.get("threadId"), 0));
        event.put("allThreadsContinued", Boolean.TRUE);
        transport.sendEvent("continued", event);
    }

    private void onStep(Map<String, Object> request, int depth) {
        Map<String, Object> args = arguments(request);
        ThreadReference thread = threadsById.get((int) number(args.get("threadId"), -1));
        if (thread == null || vm == null) {
            transport.sendError(request, "no such thread");
            return;
        }
        stepDepths.put(thread.uniqueID(), depth);
        restepBudget.put(thread.uniqueID(), MAX_AUTOMATIC_RESTEPS);
        requestStep(thread, depth);

        transport.sendResponse(request, null);
        resumeTarget();
    }

    private void requestStep(ThreadReference thread, int depth) {
        // One step request at a time per thread: JDI rejects a second one.
        for (StepRequest existing : new ArrayList<>(vm.eventRequestManager().stepRequests())) {
            if (existing.thread().equals(thread)) {
                vm.eventRequestManager().deleteEventRequest(existing);
            }
        }
        StepRequest step = vm.eventRequestManager()
                .createStepRequest(thread, StepRequest.STEP_LINE, depth);

        // Class exclusion filters are what keep stepping affordable: without them
        // one `next` off the end of a method crawls frame by frame through the
        // reflection and metaclass plumbing -- measured at ten stops and still
        // short of the caller. They are not the cause of the mid-line overshoot in
        // design doc §7.3; dropping them there was tried and changed nothing.
        for (String exclude : stepExcludes) {
            step.addClassExclusionFilter(exclude);
        }
        // No count filter: the request is deleted on its first event anyway, and
        // a Count modifier on a Step is the kind of combination JVMs implement
        // inconsistently.
        step.setSuspendPolicy(EventRequest.SUSPEND_ALL);
        step.enable();
    }

    /** A class the user did not write, by the same patterns JDI would have used. */
    private boolean isFilteredClass(String className) {
        for (String pattern : stepExcludes) {
            if (pattern.endsWith("*")) {
                if (className.startsWith(pattern.substring(0, pattern.length() - 1))) {
                    return true;
                }
            } else if (className.equals(pattern)) {
                return true;
            }
        }
        return false;
    }

    private void onPause(Map<String, Object> request) {
        if (vm == null) {
            transport.sendError(request, "not attached");
            return;
        }
        Map<String, Object> args = arguments(request);
        ThreadReference thread = threadsById.get((int) number(args.get("threadId"), -1));
        vm.suspend();
        transport.sendResponse(request, null);
        stoppedThread = thread != null ? thread : firstThread();
        sendStopped("pause", stoppedThread, new ArrayList<>());
    }

    // -------------------------------------------------------------------- events

    private void startEventLoop() {
        Thread thread = new Thread(this::eventLoop, "jdi-events");
        thread.setDaemon(true);
        thread.start();
    }

    private void eventLoop() {
        EventQueue queue = vm.eventQueue();
        while (running) {
            EventSet set;
            try {
                set = queue.remove(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (VMDisconnectedException e) {
                onTargetGone();
                return;
            }
            if (set == null) {
                continue;
            }

            Runnable announce = null;
            boolean sawBreakpoint = false;
            for (Event event : set) {
                if (event instanceof ClassPrepareEvent) {
                    binder.onClassPrepare(((ClassPrepareEvent) event).referenceType());
                } else if (event instanceof BreakpointEvent) {
                    // One set can carry several breakpoint events for the same
                    // location -- two requests on one line, which happens as soon
                    // as two source lines snap to the same executable line. They
                    // are the same stop, so only the first one is a stop.
                    if (!sawBreakpoint) {
                        sawBreakpoint = true;
                        announce = orFirst(announce, onBreakpointHit((BreakpointEvent) event));
                    }
                } else if (event instanceof StepEvent) {
                    announce = orFirst(announce, onStepDone((StepEvent) event));
                } else if (event instanceof VMDeathEvent || event instanceof VMDisconnectEvent) {
                    onTargetGone();
                    return;
                }
            }

            if (announce != null) {
                // Record what holds the VM *before* telling the client it stopped:
                // a continue racing in between would otherwise find no event set
                // and resume the VM a second time.
                suspendedSet = set;
                announce.run();
            } else {
                try {
                    set.resume();
                } catch (VMDisconnectedException e) {
                    onTargetGone();
                    return;
                }
            }
        }
    }

    /** @return what to tell the client, or null if this hit is not worth a stop. */
    private Runnable onBreakpointHit(BreakpointEvent event) {
        ThreadReference thread = event.thread();
        int depth;
        try {
            depth = thread.frameCount();
        } catch (Exception e) {
            depth = -1;
        }
        traceLocation("breakpoint", thread, event.location());
        if (!deduper.shouldReport(thread, event.location(), depth)) {
            // The other half of a line Groovy compiled twice; see StopDeduper.
            return null;
        }
        stoppedThread = thread;
        variables.reset();
        List<Integer> ids = binder.idsFor((BreakpointRequest) event.request());
        return () -> sendStopped("breakpoint", thread, ids);
    }

    private Runnable onStepDone(StepEvent event) {
        vm.eventRequestManager().deleteEventRequest(event.request());
        ThreadReference thread = event.thread();
        traceLocation("step", thread, event.location());

        boolean unusable = event.location().lineNumber() < 0
                || isFilteredClass(event.location().declaringType().name());
        if (unusable && keepStepping(thread)) {
            // Nowhere worth stopping. Either generated code with no line number
            // table -- the callback @Transactional synthesises around a method
            // body, which lives in the user's own source file and so cannot be
            // filtered out by package name -- or a frame the step filters cover but
            // JDI was not asked to exclude, because the step started mid-line.
            requestStep(thread, stepDepths.getOrDefault(thread.uniqueID(), StepRequest.STEP_OVER));
            return null;
        }

        stoppedThread = thread;
        variables.reset();
        deduper.forget(thread);
        return () -> sendStopped("step", thread, new ArrayList<>());
    }

    /**
     * Every stop the VM reports, before any of it is filtered. Off by default and
     * enabled with {@code "trace": true} in the attach arguments -- the bci and the
     * frame depth are what distinguish "the same line again" from "a different code
     * path for the same line", and neither is visible in the DAP messages.
     */
    private void traceLocation(String kind, ThreadReference thread, Location location) {
        if (!trace) {
            return;
        }
        int depth;
        try {
            depth = thread.frameCount();
        } catch (Exception e) {
            depth = -1;
        }
        log(String.format("%s event: %s.%s line %d bci %d depth %d thread %s",
                kind, location.declaringType().name(), location.method().name(),
                location.lineNumber(), location.codeIndex(), depth, safeThreadName(thread)));
    }

    /** @return false once a single user step has re-stepped too many times. */
    private boolean keepStepping(ThreadReference thread) {
        long id = thread.uniqueID();
        int left = restepBudget.getOrDefault(id, 0);
        if (left <= 0) {
            return false;
        }
        restepBudget.put(id, left - 1);
        return true;
    }

    private static Runnable orFirst(Runnable existing, Runnable candidate) {
        return existing != null ? existing : candidate;
    }

    private void sendStopped(String reason, ThreadReference thread, List<Integer> breakpointIds) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reason", reason);
        body.put("threadId", thread == null ? 0 : idOf(thread));
        body.put("allThreadsStopped", Boolean.TRUE);
        if (!breakpointIds.isEmpty()) {
            body.put("hitBreakpointIds", breakpointIds);
        }
        transport.sendEvent("stopped", body);
    }

    private void sendBreakpointChanged(Map<String, Object> breakpoint) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reason", "changed");
        body.put("breakpoint", breakpoint);
        transport.sendEvent("breakpoint", body);
    }

    private void onTargetGone() {
        if (!running) {
            return;
        }
        running = false;
        log("target VM disconnected");
        transport.sendEvent("terminated", null);
    }

    // ------------------------------------------------------------------- helpers

    private void resumeTarget() {
        if (vm == null) {
            return;
        }
        variables.reset();
        stoppedThread = null;
        EventSet set = suspendedSet;
        suspendedSet = null;
        try {
            if (set != null) {
                set.resume();
            } else if (configured) {
                // Nothing of ours is holding the VM -- this is the initial resume
                // of a target started with suspend=y.
                vm.resume();
            }
        } catch (VMDisconnectedException e) {
            onTargetGone();
        }
    }

    private void detach() {
        if (vm == null) {
            return;
        }
        try {
            if (binder != null) {
                binder.clearAll();
            }
            vm.resume();
            vm.dispose();
        } catch (VMDisconnectedException e) {
            // already gone
        } catch (RuntimeException e) {
            log("detach failed: " + e);
        } finally {
            vm = null;
        }
    }

    private void requireVm() {
        if (vm == null) {
            throw new IllegalStateException("not attached to a VM yet");
        }
    }

    private List<ThreadReference> safeAllThreads() {
        try {
            return vm.allThreads();
        } catch (VMDisconnectedException e) {
            onTargetGone();
            return new ArrayList<>();
        }
    }

    private ThreadReference firstThread() {
        List<ThreadReference> threads = safeAllThreads();
        return threads.isEmpty() ? null : threads.get(0);
    }

    private String safeThreadName(ThreadReference thread) {
        try {
            return thread.name();
        } catch (Exception e) {
            return "thread-" + thread.uniqueID();
        }
    }

    private int idOf(ThreadReference thread) {
        return threadIds.computeIfAbsent(thread.uniqueID(), key -> {
            int id = nextThreadId.getAndIncrement();
            threadsById.put(id, thread);
            return id;
        });
    }

    /** Thread id in the high bits, frame depth in the low ones. */
    private int frameId(ThreadReference thread, int frameIndex) {
        return (idOf(thread) << 16) | (frameIndex & 0xFFFF);
    }

    private void log(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("category", "console");
        body.put("output", "[groovy-dap] " + message + "\n");
        transport.sendEvent("output", body);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> arguments(Map<String, Object> request) {
        Object args = request.get("arguments");
        return args instanceof Map ? (Map<String, Object>) args : new LinkedHashMap<>();
    }

    private static long number(Object value, long fallback) {
        return value instanceof Number ? ((Number) value).longValue() : fallback;
    }

    private static List<String> strings(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof List) {
            for (Object item : (List<?>) value) {
                out.add(String.valueOf(item));
            }
        }
        return out;
    }
}
