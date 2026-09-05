package org.groovydap;

import com.sun.jdi.Bootstrap;
import com.sun.jdi.Field;
import com.sun.jdi.Location;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.StringReference;
import com.sun.jdi.Value;
import com.sun.jdi.StackFrame;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.VMDisconnectedException;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.ClassPrepareEvent;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.ExceptionEvent;
import com.sun.jdi.event.EventQueue;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.event.MethodEntryEvent;
import com.sun.jdi.event.MethodExitEvent;
import com.sun.jdi.event.StepEvent;
import com.sun.jdi.event.VMDeathEvent;
import com.sun.jdi.event.VMDisconnectEvent;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.EventRequestManager;
import com.sun.jdi.request.ExceptionRequest;
import com.sun.jdi.request.MethodEntryRequest;
import com.sun.jdi.request.MethodExitRequest;
import com.sun.jdi.request.StepRequest;
import org.groovydap.dap.DapTransport;
import org.groovydap.jdi.BreakpointBinder;
import org.groovydap.jdi.GrailsWebScope;
import org.groovydap.jdi.PathEvaluator;
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
import java.util.Set;
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
        // The JDK. Step into is otherwise unusable in Groovy: every value that
        // crosses a call site is boxed, so the first stepIn on a line of ordinary
        // Groovy lands in Integer.valueOf, and the next in String.equals.
        "java.*", "javax.*", "jakarta.*", "sun.*", "jdk.*", "com.sun.*",
        // The Groovy runtime. A plain call is dispatched through an invokedynamic
        // call site, a closure call through the metaclass; both were measured in
        // the T0 spike and both bury the frame the user wants.
        // groovyjarjar* is Groovy's shaded ASM and friends: the first call through
        // a given call site generates a class at run time, so step into lands in a
        // bytecode writer if this is missing.
        "groovy.*", "org.codehaus.groovy.*", "org.apache.groovy.*",
        "groovyjarjar*", "org.objectweb.asm.*",
        // Grails' own plumbing. Stepping off the end of a @Transactional method
        // walks into the transaction template, and off the end of a controller
        // action into the MVC dispatcher.
        "grails.gorm.transactions.*", "org.grails.*", "org.springframework.*",
        // Everything else a Grails request touches on its way through. This list
        // is only about cost: isOutsideProject decides where it is worth stopping,
        // but a pattern here stops the VM generating the event at all, and each
        // event it does generate is a round trip. All of these were measured being
        // stepped through.
        "org.slf4j.*", "ch.qos.logback.*", "org.hibernate.*", "com.zaxxer.*",
        // The servlet container, which is what a controller action returns into.
        "org.apache.tomcat.*", "org.apache.catalina.*", "org.apache.coyote.*");

    /** Guard against stepping forever through code the user cannot see. */
    private static final int MAX_AUTOMATIC_RESTEPS = 500;

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
    private volatile ExceptionEvent lastException;
    private volatile boolean running = true;
    private volatile boolean configured;

    private List<String> stepExcludes = DEFAULT_STEP_EXCLUDES;
    private boolean projectCodeOnly = true;
    private boolean trace;
    private final Map<Long, Integer> stepDepths = new ConcurrentHashMap<>();
    private final Map<Long, Integer> stepOriginFrames = new ConcurrentHashMap<>();
    private final Map<Long, Integer> restepBudget = new ConcurrentHashMap<>();
    /** Requests standing in for a step over that JDI cannot do from here. */
    private final Set<EventRequest> stepAssist = ConcurrentHashMap.newKeySet();
    private volatile int assistOriginFrames;

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
            case "setExceptionBreakpoints": onSetExceptionBreakpoints(request); break;
            case "exceptionInfo": onExceptionInfo(request); break;
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
            case "evaluate": onEvaluate(request); break;
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
        // These two need no expression compiler: a hit count is arithmetic, and a
        // log message is the path reading that already answers hovers.
        capabilities.put("supportsHitConditionalBreakpoints", Boolean.TRUE);
        capabilities.put("supportsLogPoints", Boolean.TRUE);
        capabilities.put("supportsEvaluateForHovers", Boolean.TRUE);
        capabilities.put("supportsSetVariable", Boolean.FALSE);
        capabilities.put("supportsExceptionInfoRequest", Boolean.TRUE);
        capabilities.put("exceptionBreakpointFilters", List.of(
                filter("uncaught", "Uncaught exceptions", true),
                filter("caught", "Caught exceptions", false)));
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
        if (args.containsKey("stepIntoProjectCodeOnly")) {
            projectCodeOnly = Boolean.TRUE.equals(args.get("stepIntoProjectCodeOnly"));
        }
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

        List<BreakpointBinder.Spec> specs = new ArrayList<>();
        Object raw = args.get("breakpoints");
        if (raw instanceof List) {
            for (Object item : (List<?>) raw) {
                if (item instanceof Map) {
                    Map<?, ?> entry = (Map<?, ?>) item;
                    specs.add(new BreakpointBinder.Spec(
                            (int) number(entry.get("line"), 0),
                            text(entry.get("logMessage")),
                            text(entry.get("hitCondition"))));
                }
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("breakpoints", binder.setBreakpoints(path, specs));
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

            // params, request and session are not locals of anything on the stack.
            // They belong to the request, which Spring keeps in a thread local, so
            // they get a scope of their own when the thread is serving one.
            ObjectReference webRequest = GrailsWebScope.find(vm, thread);
            if (webRequest != null) {
                Map<String, Object> grails = new LinkedHashMap<>();
                grails.put("name", "Grails");
                grails.put("variablesReference",
                        variables.curatedHandle(webRequest, GrailsWebScope.interestingFields()));
                grails.put("expensive", Boolean.FALSE);
                scopes.add(grails);
            }
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

    /**
     * A hover, a watch or a repl line, answered by reading rather than running.
     *
     * <p>Which is most of what is asked: a name, a field path, a map key. What it
     * cannot do -- a call, an operator, a closure -- it says so about, because
     * "no such field" would be a wrong answer about something that exists.
     */
    private void onEvaluate(Map<String, Object> request) {
        Map<String, Object> args = arguments(request);
        int frameId = (int) number(args.get("frameId"), -1);
        ThreadReference thread = threadsById.get(frameId >> 16);
        int frameIndex = frameId & 0xFFFF;
        if (thread == null) {
            transport.sendError(request, "no frame to evaluate against");
            return;
        }
        try {
            StackFrame frame = thread.frame(frameIndex);
            Value value = PathEvaluator.evaluate(String.valueOf(args.get("expression")),
                    frame, GrailsWebScope.find(vm, thread));
            transport.sendResponse(request, variables.describeResult(value));
        } catch (PathEvaluator.Unsupported e) {
            transport.sendError(request, e.getMessage());
        } catch (Exception e) {
            transport.sendError(request, e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        }
    }

    private void onContinue(Map<String, Object> request) {
        clearStepAssist(); // a continue abandons any step in progress
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
        stepOriginFrames.put(thread.uniqueID(), frameCountOf(thread));
        restepBudget.put(thread.uniqueID(), MAX_AUTOMATIC_RESTEPS);
        // Step over and step into never go through JDI stepping. Where a step over
        // lands was not predictable here: issuing one partway through a line ran to
        // the end of the method, and after three attempts at characterising when
        // else it does that -- exclusion filters, count filters, the shape of the
        // previous stop -- a step from the first bytecode of a line did it too. A
        // step into has a different problem with the same shape: it cannot enter a
        // @Transactional method, because the entry runs through the transaction
        // template and the step completes back on the calling line. Both are said
        // directly instead, out of breakpoints, and neither is subject to any of
        // that. Step out still uses JDI, where it was measured working.
        boolean armed = depth == StepRequest.STEP_OVER ? armLineStep(thread)
                : depth == StepRequest.STEP_INTO && armStepInto(thread);
        if (!armed) {
            requestStep(thread, depth);
        }

        transport.sendResponse(request, null);
        resumeTarget();
    }

    /**
     * A step over built out of breakpoints, for where JDI will not do one.
     *
     * <p>A STEP_OVER issued while the thread is partway through a line -- exactly
     * where a returning call leaves it -- runs to the end of the method instead of
     * stopping at the next line. Measured repeatedly: from index line 9 at bci 104,
     * lines 10 and 11 execute, no step event is generated for either, and the next
     * event arrives once the frame has popped. Class exclusion filters and the
     * count filter were both ruled out as the cause; what remains is that the
     * request does not behave, so this stops asking it to.
     *
     * <p>The semantics of a step over are "stop at the next line reached in this
     * frame, or when it returns". Both halves can be said directly: a breakpoint on
     * the first location of every other line of this method, and a method exit
     * request for the return. Whichever happens first is the answer.
     *
     * @return false if the method has no line table to work from, in which case the
     *     caller should fall back to an ordinary step
     */
    private boolean armLineStep(ThreadReference thread) {
        try {
            return armLineStepAt(thread, thread.frame(0).location(), frameCountOf(thread));
        } catch (Exception e) {
            clearStepAssist();
            return false;
        }
    }

    private boolean armLineStepAt(ThreadReference thread, Location here, int originFrames) {
        try {
            List<Location> lines = here.method().allLineLocations();
            if (lines.isEmpty()) {
                return false;
            }
            clearStepAssist();
            assistOriginFrames = originFrames;
            EventRequestManager erm = vm.eventRequestManager();

            // Every location of every other line, not the first of each. Groovy
            // compiles a statement twice under one line number and the copy that
            // runs is not always the first: line 34 of the target maps to bci 26
            // and bci 73, and only 73 ever executes, so arming the first location
            // armed the path that never runs. Measured as a step into that skipped
            // the last line of the method and came out in the caller. This is the
            // same rule BreakpointBinder follows, and for the same reason; the
            // duplicate it can cause is removed by clearing the whole set on the
            // first arrival.
            for (Location location : lines) {
                if (location.lineNumber() == here.lineNumber()) {
                    continue;
                }
                BreakpointRequest at = erm.createBreakpointRequest(location);
                at.addThreadFilter(thread);
                at.setSuspendPolicy(EventRequest.SUSPEND_ALL);
                at.enable();
                stepAssist.add(at);
            }

            MethodExitRequest exit = erm.createMethodExitRequest();
            exit.addThreadFilter(thread);
            exit.addClassFilter(here.declaringType());
            exit.setSuspendPolicy(EventRequest.SUSPEND_ALL);
            exit.enable();
            stepAssist.add(exit);
            return !stepAssist.isEmpty();
        } catch (Exception e) {
            clearStepAssist();
            return false;
        }
    }

    /**
     * A step into, said directly: stop wherever the project's code runs next.
     *
     * <p>Which is either a method it enters or, if this line calls nothing of the
     * user's, the next line of this one -- so it is a step over with a method entry
     * request added. That last part is not a compromise; it is what a step into
     * means on a line with no call in it.
     *
     * <p>The entry request is why this works on a {@code @Transactional} method
     * where a JDI step into does not. Grails routes the call through the
     * transaction template, so the step completes back on the calling line, having
     * run the whole method. Entering {@code $tt__foo} is a fact about the target
     * that no amount of stepping reaches, and asking for it directly costs one
     * request. The wrappers on the way -- {@code foo}, the callback closure,
     * {@code foo$0} -- are passed over by the same rule everything else uses: no
     * line number, nowhere to stop.
     *
     * @return false if the project's packages could not be worked out, in which
     *     case an unfiltered method entry request would be far too expensive and
     *     the caller should fall back to JDI stepping
     */
    private boolean armStepInto(ThreadReference thread) {
        List<String> packages = sources.packageFilters();
        if (packages.isEmpty()) {
            return false;
        }
        if (!armLineStep(thread)) {
            return false;
        }
        try {
            MethodEntryRequest entry = vm.eventRequestManager().createMethodEntryRequest();
            entry.addThreadFilter(thread);
            for (String pattern : packages) {
                entry.addClassFilter(pattern);
            }
            entry.setSuspendPolicy(EventRequest.SUSPEND_ALL);
            entry.enable();
            stepAssist.add(entry);
            return true;
        } catch (Exception e) {
            clearStepAssist();
            return false;
        }
    }

    /**
     * A method of the project's was entered while a step into was waiting.
     *
     * <p>Reports nothing for a method with no line table -- the three wrappers
     * Grails puts around a transactional method are entered before the body is --
     * and stays armed, so the next entry is still considered.
     */
    private Runnable onAssistEntry(MethodEntryEvent event) {
        if (!stepAssist.contains(event.request())) {
            return null;
        }
        Location at = event.location();
        if (at.lineNumber() < 0 || isOutsideProject(at)) {
            return null; // a wrapper, or a class that only looks like the project's
        }
        ThreadReference thread = event.thread();
        clearStepAssist();
        stoppedThread = thread;
        variables.reset();
        deduper.forget(thread);
        return () -> sendStopped("step", thread, new ArrayList<>());
    }

    private void clearStepAssist() {
        if (stepAssist.isEmpty()) {
            return;
        }
        EventRequestManager erm = vm.eventRequestManager();
        for (EventRequest request : stepAssist) {
            try {
                erm.deleteEventRequest(request);
            } catch (RuntimeException e) {
                // the VM may be gone, or the class unloaded with it
            }
        }
        stepAssist.clear();
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

    /**
     * Code the user cannot see, judged by whether its source file is under one of
     * the configured source roots.
     *
     * <p>A package blacklist never ends. Stepping into one line of ordinary Groovy
     * was measured landing in {@code Integer.valueOf}, then -- once the JDK was
     * excluded -- in Groovy's shaded ASM, because the first call through a call
     * site generates a class at run time, and then in
     * {@code org.slf4j.LoggerFactory}, because a Grails service initialises its
     * log field on first use. Each of those needed another pattern, and the next
     * dependency would need one more.
     *
     * <p>Whether the file is in the project answers the same question once and for
     * anything. The package filters stay as the cheap first pass: they stop the VM
     * from generating most of these events at all, which is what keeps stepping
     * affordable. Set {@code stepIntoProjectCodeOnly} to false to step into
     * dependencies, e.g. in a multi-module build whose sibling modules are not on
     * {@code sourcePaths}.
     */
    private boolean isOutsideProject(Location location) {
        return projectCodeOnly && sources.find(location) == null;
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
                        BreakpointEvent hit = (BreakpointEvent) event;
                        announce = orFirst(announce, stepAssist.contains(hit.request())
                                ? onAssistArrival(hit.thread())
                                : onBreakpointHit(hit));
                    }
                } else if (event instanceof MethodEntryEvent) {
                    announce = orFirst(announce, onAssistEntry((MethodEntryEvent) event));
                } else if (event instanceof MethodExitEvent) {
                    announce = orFirst(announce, onAssistExit((MethodExitEvent) event));
                } else if (event instanceof ExceptionEvent) {
                    announce = orFirst(announce, onExceptionThrown((ExceptionEvent) event));
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
        BreakpointBinder.Hit hit = binder.onHit((BreakpointRequest) event.request());
        if (hit.logMessage != null) {
            // A logpoint. Print where a stop would have been and carry on -- which
            // is the whole point of one: seeing a value on every pass through a
            // line without stopping the application on each of them.
            printLog(hit.logMessage, thread, event.location());
            return null;
        }
        if (!hit.stop) {
            return null; // the hit count has not reached this hit yet
        }
        stoppedThread = thread;
        variables.reset();
        return () -> sendStopped("breakpoint", thread, hit.ids);
    }

    /**
     * A logpoint's message, with {@code {expression}} replaced by what it reads.
     *
     * <p>The expressions are read the same way a hover is, by walking fields and
     * keys, so the same limit applies: a call or an operator is refused. Its reason
     * is printed in place of the value rather than dropped, because a log line that
     * silently says nothing about {@code {list.size()}} is worse than one that says
     * why it cannot.
     */
    private void printLog(String template, ThreadReference thread, Location location) {
        StringBuilder out = new StringBuilder(template.length() + 16);
        StackFrame frame = null;
        ObjectReference webRequest = null;
        for (int i = 0; i < template.length(); i++) {
            char c = template.charAt(i);
            if (c != '{') {
                out.append(c);
                continue;
            }
            int close = template.indexOf('}', i);
            if (close < 0) {
                out.append(template.substring(i));
                break;
            }
            String expression = template.substring(i + 1, close);
            i = close;
            try {
                if (frame == null) {
                    frame = thread.frame(0);
                    webRequest = GrailsWebScope.find(vm, thread);
                }
                out.append(variables.plain(
                        PathEvaluator.evaluate(expression, frame, webRequest)));
            } catch (PathEvaluator.Unsupported e) {
                out.append('<').append(e.getMessage()).append('>');
            } catch (Exception e) {
                out.append('<').append(e.getClass().getSimpleName()).append('>');
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("category", "stdout");
        body.put("output", out.append('\n').toString());
        Path file = sources.find(location);
        if (file != null) {
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("name", file.getFileName().toString());
            source.put("path", file.toString());
            body.put("source", source);
            body.put("line", Math.max(location.lineNumber(), 0));
        }
        transport.sendEvent("output", body);
    }

    /** The stand-in step over reached the next line of the frame. */
    private Runnable onAssistArrival(ThreadReference thread) {
        clearStepAssist();
        try {
            Location at = thread.frame(0).location();
            if (at.lineNumber() < 0 || isOutsideProject(at)) {
                // Same rule the ordinary step follows: nowhere worth stopping.
                if (armLineStepOnCaller(thread)) {
                    return null;
                }
            }
        } catch (Exception ignored) {
            // fall through and report where we are
        }
        stoppedThread = thread;
        variables.reset();
        deduper.forget(thread);
        return () -> sendStopped("step", thread, new ArrayList<>());
    }

    /**
     * The frame is returning, so the step over continues in whoever called it.
     *
     * <p>Method exit fires for every method of the class on this thread, so a
     * deeper call returning arrives here too; the frame count tells them apart.
     *
     * <p>It also fires <em>before</em> the frame pops, which is why this reports
     * nothing. Stopping here would stop on the line just stopped at -- the return
     * -- a second time. Instead the same trick is set up again on the caller, and
     * the step lands on the next line executed there.
     *
     * <p>The caller may be a wrapper with no line table of its own: Grails puts
     * three of them between a transactional method and the code that called it. So
     * it climbs to the nearest frame that has lines and belongs to the project.
     */
    private Runnable onAssistExit(MethodExitEvent event) {
        if (!stepAssist.contains(event.request())) {
            return null;
        }
        ThreadReference thread = event.thread();
        if (frameCountOf(thread) > assistOriginFrames) {
            return null; // a nested call returning, not this frame
        }
        clearStepAssist();
        if (armLineStepOnCaller(thread)) {
            return null; // carry on; the caller's lines are armed now
        }
        // Nothing above has lines we can use. Fall back to an ordinary step out.
        requestStep(thread, StepRequest.STEP_OUT);
        return null;
    }

    /**
     * Arms the line breakpoints on the nearest caller worth stopping in.
     *
     * @return false if no frame above has both a line table and a source file in
     *     the project, in which case there is nothing useful to arm
     */
    private boolean armLineStepOnCaller(ThreadReference thread) {
        try {
            List<StackFrame> frames = thread.frames();
            for (int i = 1; i < frames.size(); i++) {
                Location at = frames.get(i).location();
                if (at.lineNumber() < 0 || isOutsideProject(at)) {
                    continue;
                }
                if (armLineStepAt(thread, at, frames.size() - i)) {
                    return true;
                }
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }

    private Runnable onStepDone(StepEvent event) {
        vm.eventRequestManager().deleteEventRequest(event.request());
        ThreadReference thread = event.thread();
        traceLocation("step", thread, event.location());

        boolean unusable = event.location().lineNumber() < 0
                || isFilteredClass(event.location().declaringType().name())
                || isOutsideProject(event.location());
        if (unusable && keepStepping(thread)) {
            // Nowhere worth stopping: generated code with no line number table --
            // the callback @Transactional synthesises around a method body, which
            // lives in the user's own source file and so cannot be filtered out by
            // package name -- or a frame outside the project.
            //
            // Climb out rather than stepping line by line. Walking a library one
            // line at a time costs a round trip per line and, measured against
            // logback and Hibernate on the way into a Grails service, ran through a
            // 200-step budget without reaching the far side. Stepping out of the
            // frame converges in a handful of events instead.
            long id = thread.uniqueID();
            int origin = stepOriginFrames.getOrDefault(id, 0);
            int depth = frameCountOf(thread) > origin
                    ? StepRequest.STEP_OUT
                    : stepDepths.getOrDefault(id, StepRequest.STEP_OVER);
            requestStep(thread, depth);
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

    private int frameCountOf(ThreadReference thread) {
        try {
            return thread.frameCount();
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Exception breakpoints.
     *
     * <p>Filtered by the same rule stepping uses -- the exception's throw site has
     * to be in a file under the configured source roots. Without that, "caught
     * exceptions" is unusable on a Grails application: the framework throws and
     * catches constantly on the way up, class loading included, and the debugger
     * would stop dozens of times before reaching anything the user wrote.
     */
    private void onSetExceptionBreakpoints(Map<String, Object> request) {
        Map<String, Object> args = arguments(request);
        List<String> filters = strings(args.get("filters"));
        boolean caught = filters.contains("caught");
        boolean uncaught = filters.contains("uncaught");

        for (ExceptionRequest existing : new ArrayList<>(vm.eventRequestManager().exceptionRequests())) {
            vm.eventRequestManager().deleteEventRequest(existing);
        }
        if (caught || uncaught) {
            ExceptionRequest exceptions =
                    vm.eventRequestManager().createExceptionRequest(null, caught, uncaught);
            // Cheap first pass, the same one stepping uses: these never generate an
            // event at all, which matters because the alternative is a round trip
            // per throw and Grails throws a great many.
            for (String exclude : stepExcludes) {
                exceptions.addClassExclusionFilter(exclude);
            }
            exceptions.setSuspendPolicy(EventRequest.SUSPEND_ALL);
            exceptions.enable();
            log("exception breakpoints: caught=" + caught + " uncaught=" + uncaught);
        }
        transport.sendResponse(request, null);
    }

    private Runnable onExceptionThrown(ExceptionEvent event) {
        Location where = event.location();
        if (isOutsideProject(where)) {
            return null; // thrown inside a dependency; not this user's problem
        }
        ThreadReference thread = event.thread();
        int depth;
        try {
            depth = thread.frameCount();
        } catch (Exception e) {
            depth = -1;
        }
        // Same rule as breakpoints: one stop per line. An exception on its way up
        // passes the same line more than once -- Groovy's dispatch rethrows -- and
        // reporting each pass says nothing the first did not.
        if (!deduper.shouldReport(thread, where, depth)) {
            return null;
        }
        lastException = event;
        stoppedThread = thread;
        variables.reset();
        String type = event.exception().referenceType().name();
        return () -> sendStopped("exception", thread, new ArrayList<>(), type);
    }

    /** DAP exceptionInfo: what was thrown, and where it goes if nobody catches it. */
    private void onExceptionInfo(Map<String, Object> request) {
        ExceptionEvent event = lastException;
        if (event == null) {
            transport.sendError(request, "no exception is being reported");
            return;
        }
        String type = event.exception().referenceType().name();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("message", exceptionMessage(event));
        details.put("typeName", type);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("exceptionId", type);
        body.put("description", exceptionMessage(event));
        body.put("breakMode", event.catchLocation() == null ? "unhandled" : "always");
        body.put("details", details);
        transport.sendResponse(request, body);
    }

    /**
     * The exception's message, read as a field.
     *
     * <p>Not by calling getMessage(): invoking a method in the target VM means
     * resuming threads to run it, and a debugger that runs user code to describe a
     * stop can deadlock or change what it is describing.
     */
    private String exceptionMessage(ExceptionEvent event) {
        try {
            ObjectReference thrown = event.exception();
            Field message = thrown.referenceType().fieldByName("detailMessage");
            if (message == null) {
                return thrown.referenceType().name();
            }
            Value value = thrown.getValue(message);
            return value instanceof StringReference
                    ? ((StringReference) value).value()
                    : thrown.referenceType().name();
        } catch (Exception e) {
            return "<could not read the message: " + e + ">";
        }
    }

    private static Map<String, Object> filter(String id, String label, boolean on) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("filter", id);
        entry.put("label", label);
        entry.put("default", on);
        return entry;
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
        sendStopped(reason, thread, breakpointIds, null);
    }

    private void sendStopped(String reason, ThreadReference thread,
                             List<Integer> breakpointIds, String description) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reason", reason);
        if (description != null) {
            body.put("description", description);
            body.put("text", description);
        }
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

    /** A string field of a request, or null when it was absent or empty. */
    private static String text(Object value) {
        if (!(value instanceof String)) {
            return null;
        }
        String string = (String) value;
        return string.isEmpty() ? null : string;
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
