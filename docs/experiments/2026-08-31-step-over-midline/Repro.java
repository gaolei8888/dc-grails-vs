import com.sun.jdi.*;
import com.sun.jdi.connect.*;
import com.sun.jdi.event.*;
import com.sun.jdi.request.*;
import java.util.*;

/**
 * mode "midline": breakpoint in callee, STEP_OUT to land mid-line in main, then STEP_OVER.
 * mode "linestart": breakpoint at the start of that same line, then STEP_OVER.
 * Prints every step event (line/bci/depth) until the thread leaves main or 12 events pass.
 */
public class Repro {
    public static void main(String[] args) throws Exception {
        String mode = args[0];
        LaunchingConnector conn = Bootstrap.virtualMachineManager().defaultConnector();
        Map<String, Connector.Argument> a = conn.defaultArguments();
        a.get("main").setValue("Target");
        a.get("options").setValue("-cp " + args[1]);
        VirtualMachine vm = conn.launch(a);
        EventRequestManager erm = vm.eventRequestManager();

        ClassPrepareRequest cpr = erm.createClassPrepareRequest();
        cpr.addClassFilter("Target");
        cpr.setSuspendPolicy(EventRequest.SUSPEND_ALL);
        cpr.enable();

        int bpLine = mode.equals("midline") ? 3 : 7;
        boolean steppedOut = false;
        int events = 0;
        EventQueue q = vm.eventQueue();
        while (true) {
            EventSet set = q.remove();
            boolean resume = true;
            for (Event e : set) {
                if (e instanceof ClassPrepareEvent) {
                    ReferenceType rt = ((ClassPrepareEvent) e).referenceType();
                    List<Location> locs = rt.locationsOfLine(bpLine);
                    BreakpointRequest bp = erm.createBreakpointRequest(locs.get(0));
                    bp.setSuspendPolicy(EventRequest.SUSPEND_ALL);
                    bp.enable();
                    System.out.println("bp at line " + bpLine + " bci " + locs.get(0).codeIndex());
                } else if (e instanceof BreakpointEvent) {
                    ThreadReference t = ((BreakpointEvent) e).thread();
                    print("BP", t, ((BreakpointEvent) e).location());
                    erm.deleteEventRequest(e.request());
                    int depth = mode.equals("midline")
                            ? StepRequest.STEP_OUT : StepRequest.STEP_OVER;
                    System.out.println("  -> creating " + name(depth));
                    step(erm, t, depth);
                    if (!mode.equals("midline")) { steppedOut = true; }
                } else if (e instanceof StepEvent) {
                    StepEvent se = (StepEvent) e;
                    ThreadReference t = se.thread();
                    print("STEP", t, se.location());
                    erm.deleteEventRequest(e.request());
                    if (!steppedOut) {
                        // just landed back in main, mid-line: this is the case under test
                        steppedOut = true;
                        System.out.println("  -> creating STEP_OVER from here");
                        step(erm, t, StepRequest.STEP_OVER);
                    } else if (++events < 6) {
                        System.out.println("  -> creating STEP_OVER from here");
                        step(erm, t, StepRequest.STEP_OVER);
                    } else {
                        System.out.println("  (done)");
                    }
                } else if (e instanceof VMDeathEvent || e instanceof VMDisconnectEvent) {
                    System.out.println("VM gone");
                    return;
                }
            }
            if (resume) { set.resume(); }
        }
    }

    static String name(int d) {
        return d == StepRequest.STEP_OUT ? "STEP_OUT"
             : d == StepRequest.STEP_OVER ? "STEP_OVER" : "STEP_INTO";
    }

    static void step(EventRequestManager erm, ThreadReference t, int depth) {
        for (StepRequest s : new ArrayList<>(erm.stepRequests())) {
            if (s.thread().equals(t)) { erm.deleteEventRequest(s); }
        }
        StepRequest s = erm.createStepRequest(t, StepRequest.STEP_LINE, depth);
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
