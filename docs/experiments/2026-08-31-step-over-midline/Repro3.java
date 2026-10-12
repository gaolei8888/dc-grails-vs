import com.sun.jdi.*;
import com.sun.jdi.connect.*;
import com.sun.jdi.event.*;
import com.sun.jdi.request.*;
import java.util.*;

/**
 * args: <mainClass> <classpath> <calleeLine> [excludes]
 * Breakpoint in callee; STEP_OUT repeatedly until back in mainClass.main (that landing
 * is mid-line by construction); then STEP_OVER four times, printing every event.
 */
public class Repro3 {
    static String target;
    static boolean landed = false;
    static int overs = 0;
    static List<String> excludes = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        target = args[0];
        int calleeLine = Integer.parseInt(args[2]);
        if (args.length > 3 && !args[3].isEmpty()) {
            excludes = Arrays.asList(args[3].split(","));
        }
        LaunchingConnector conn = Bootstrap.virtualMachineManager().defaultConnector();
        Map<String, Connector.Argument> a = conn.defaultArguments();
        a.get("main").setValue(target);
        a.get("options").setValue("-cp " + args[1]);
        VirtualMachine vm = conn.launch(a);
        EventRequestManager erm = vm.eventRequestManager();

        ClassPrepareRequest cpr = erm.createClassPrepareRequest();
        cpr.addClassFilter(target);
        cpr.setSuspendPolicy(EventRequest.SUSPEND_ALL);
        cpr.enable();

        // drain the target's own stdout so it cannot block on a full pipe
        pump(vm.process().getInputStream(), "out");
        pump(vm.process().getErrorStream(), "err");

        EventQueue q = vm.eventQueue();
        while (true) {
            EventSet set = q.remove();
            for (Event e : set) {
                if (e instanceof ClassPrepareEvent) {
                    ReferenceType rt = ((ClassPrepareEvent) e).referenceType();
                    List<Location> locs = rt.locationsOfLine(calleeLine);
                    if (locs.isEmpty()) { System.out.println("no location for line " + calleeLine); return; }
                    BreakpointRequest bp = erm.createBreakpointRequest(locs.get(0));
                    bp.setSuspendPolicy(EventRequest.SUSPEND_ALL);
                    bp.enable();
                    System.out.println("bp line " + calleeLine + " bci " + locs.get(0).codeIndex()
                            + " excludes=" + excludes);
                } else if (e instanceof BreakpointEvent) {
                    ThreadReference t = ((BreakpointEvent) e).thread();
                    print("BP", t, ((BreakpointEvent) e).location());
                    erm.deleteEventRequest(e.request());
                    System.out.println("  -> STEP_OUT");
                    step(erm, t, StepRequest.STEP_OUT);
                } else if (e instanceof StepEvent) {
                    StepEvent se = (StepEvent) e;
                    ThreadReference t = se.thread();
                    Location l = se.location();
                    print("STEP", t, l);
                    erm.deleteEventRequest(e.request());
                    boolean inMain = l.declaringType().name().equals(target)
                            && l.method().name().equals("action");
                    if (!landed) {
                        if (inMain) {
                            landed = true;
                            System.out.println("  -> landed mid-line; STEP_OVER");
                            step(erm, t, StepRequest.STEP_OVER);
                        } else {
                            System.out.println("  -> not in main yet; STEP_OUT again");
                            step(erm, t, StepRequest.STEP_OUT);
                        }
                    } else if (++overs < 4) {
                        System.out.println("  -> STEP_OVER");
                        step(erm, t, StepRequest.STEP_OVER);
                    } else {
                        System.out.println("  (done)");
                    }
                } else if (e instanceof VMDeathEvent || e instanceof VMDisconnectEvent) {
                    System.out.println("VM gone");
                    return;
                }
            }
            set.resume();
        }
    }

    static void pump(java.io.InputStream in, String tag) {
        Thread t = new Thread(() -> {
            try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(in))) {
                String line;
                while ((line = r.readLine()) != null) { System.out.println("[" + tag + "] " + line); }
            } catch (Exception ignored) { }
        });
        t.setDaemon(true);
        t.start();
    }

    static void step(EventRequestManager erm, ThreadReference t, int depth) {
        for (StepRequest s : new ArrayList<>(erm.stepRequests())) {
            if (s.thread().equals(t)) { erm.deleteEventRequest(s); }
        }
        StepRequest s = erm.createStepRequest(t, StepRequest.STEP_LINE, depth);
        for (String x : excludes) { s.addClassExclusionFilter(x); }
        s.setSuspendPolicy(EventRequest.SUSPEND_ALL);
        s.enable();
    }

    static void print(String kind, ThreadReference t, Location l) {
        int depth;
        try { depth = t.frameCount(); } catch (Exception ex) { depth = -1; }
        System.out.printf("%-5s %s.%s line %d bci %d depth %d%n",
                kind, l.declaringType().name(), l.method().name(),
                l.lineNumber(), l.codeIndex(), depth);
    }
}
