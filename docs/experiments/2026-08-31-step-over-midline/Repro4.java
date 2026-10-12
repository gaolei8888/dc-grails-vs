import com.sun.jdi.*;
import com.sun.jdi.connect.*;
import com.sun.jdi.event.*;
import com.sun.jdi.request.*;
import java.util.*;

/**
 * Mimics DebugSession's auto-restep chain exactly: break in the innermost user
 * code, "press next", and on any landing with no line number (or in an excluded
 * class) re-step -- STEP_OUT when deeper than where the user's step began,
 * otherwise the user's own depth. Then, once back in action() mid-line, press
 * next again and print what the VM reports.
 */
public class Repro4 {
    static List<String> excludes = new ArrayList<>();
    static int originFrames;
    static int userDepth = StepRequest.STEP_OVER;
    static boolean landedInAction = false;
    static int overs = 0;
    static int budget = 200;

    public static void main(String[] args) throws Exception {
        String target = args[0];
        if (args.length > 3 && !args[3].isEmpty()) { excludes = Arrays.asList(args[3].split(",")); }
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
        pump(vm.process().getInputStream(), "out");
        pump(vm.process().getErrorStream(), "err");
        int bpLine = Integer.parseInt(args[2]);

        while (true) {
            EventSet set = vm.eventQueue().remove();
            for (Event e : set) {
                if (e instanceof ClassPrepareEvent) {
                    ReferenceType rt = ((ClassPrepareEvent) e).referenceType();
                    List<Location> locs = rt.locationsOfLine(bpLine);
                    if (locs.isEmpty()) { System.out.println("no loc line " + bpLine); return; }
                    BreakpointRequest bp = erm.createBreakpointRequest(locs.get(0));
                    bp.setSuspendPolicy(EventRequest.SUSPEND_ALL);
                    bp.enable();
                } else if (e instanceof BreakpointEvent) {
                    ThreadReference t = ((BreakpointEvent) e).thread();
                    print("BP", t, ((BreakpointEvent) e).location());
                    erm.deleteEventRequest(e.request());
                    originFrames = frames(t);
                    userDepth = StepRequest.STEP_OVER;
                    System.out.println("  user next (origin depth " + originFrames + ")");
                    step(erm, t, StepRequest.STEP_OVER);
                } else if (e instanceof StepEvent) {
                    StepEvent se = (StepEvent) e;
                    ThreadReference t = se.thread();
                    Location l = se.location();
                    print("STEP", t, l);
                    erm.deleteEventRequest(e.request());
                    boolean unusable = l.lineNumber() < 0 || excluded(l.declaringType().name());
                    if (unusable) {
                        if (--budget <= 0) { System.out.println("  budget out"); return; }
                        int depth = frames(t) > originFrames ? StepRequest.STEP_OUT : userDepth;
                        System.out.println("  unusable -> auto-restep " + nm(depth));
                        step(erm, t, depth);
                        continue;
                    }
                    boolean inAction = l.method().name().equals("action");
                    if (inAction && !landedInAction) {
                        landedInAction = true;
                        System.out.println("  *** landed in action() line " + l.lineNumber()
                                + " bci " + l.codeIndex() + " -- user presses next ***");
                        originFrames = frames(t);
                        step(erm, t, StepRequest.STEP_OVER);
                    } else if (++overs < 5) {
                        System.out.println("  user next");
                        originFrames = frames(t);
                        step(erm, t, StepRequest.STEP_OVER);
                    } else { System.out.println("  (done)"); }
                } else if (e instanceof VMDeathEvent || e instanceof VMDisconnectEvent) {
                    System.out.println("VM gone"); return;
                }
            }
            set.resume();
        }
    }

    static boolean excluded(String cls) {
        for (String p : excludes) {
            String s = p.endsWith("*") ? p.substring(0, p.length() - 1) : p;
            if (p.endsWith("*") ? cls.startsWith(s) : cls.equals(p)) { return true; }
        }
        return false;
    }
    static String nm(int d) { return d == StepRequest.STEP_OUT ? "STEP_OUT" : "STEP_OVER"; }
    static int frames(ThreadReference t) { try { return t.frameCount(); } catch (Exception e) { return 0; } }
    static void pump(java.io.InputStream in, String tag) {
        Thread th = new Thread(() -> {
            try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(in))) {
                String s; while ((s = r.readLine()) != null) { System.out.println("[" + tag + "] " + s); }
            } catch (Exception ignored) { } });
        th.setDaemon(true); th.start();
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
        System.out.printf("%-5s %s.%s line %d bci %d depth %d%n", kind,
                l.declaringType().name(), l.method().name(), l.lineNumber(), l.codeIndex(), frames(t));
    }
}
