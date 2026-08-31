package org.groovydap.jdi;

import com.sun.jdi.ArrayReference;
import com.sun.jdi.Field;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Value;
import com.sun.jdi.VirtualMachine;

import java.util.Arrays;
import java.util.List;

/**
 * Finds the Grails web request belonging to a stopped thread.
 *
 * <p>{@code params}, {@code request} and {@code session} are the things you
 * actually want to look at when stopped in a controller, and none of them is a
 * local or a field of anything on the stack. They hang off a
 * {@code GrailsWebRequest} that Spring keeps in a thread local, so the debugger
 * has to go and get it.
 *
 * <p>Entirely by reading fields. Calling {@code RequestContextHolder.getRequestAttributes()}
 * would be one line, but invoking a method in the target VM means resuming threads
 * to run it, and a debugger that runs application code to populate a pane can
 * deadlock or change what it is showing. So: read the static ThreadLocal out of
 * RequestContextHolder, then walk the thread's own ThreadLocalMap looking for the
 * entry whose key is that object.
 *
 * <p>Matching by identity matters. A request thread in a Grails application was
 * measured holding three entries keyed by a {@code NamedThreadLocal} -- the web
 * request, a locale resolver and a plain map -- so "the first one that looks
 * right" would be wrong two times in three.
 */
public final class GrailsWebScope {

    private static final String HOLDER =
            "org.springframework.web.context.request.RequestContextHolder";

    /**
     * The fields worth showing, in the order they are worth showing them.
     *
     * <p>A GrailsWebRequest carries twenty-odd, most of them plumbing -- encoders,
     * destruction callbacks, the application context. A null {@code session} is
     * kept because it is informative: it means nothing has touched the session on
     * this request yet.
     */
    private static final List<String> INTERESTING =
            Arrays.asList("params", "request", "response", "session");

    private GrailsWebScope() {
    }

    /** The GrailsWebRequest for this thread, or null if it is not in a request. */
    public static ObjectReference find(VirtualMachine vm, ThreadReference thread) {
        try {
            List<ReferenceType> holders = vm.classesByName(HOLDER);
            if (holders.isEmpty()) {
                return null; // not a web application, or Spring is not loaded yet
            }
            ReferenceType holder = holders.get(0);
            ObjectReference found = lookup(thread, holder, "requestAttributesHolder");
            return found != null ? found : lookup(thread, holder, "inheritableRequestAttributesHolder");
        } catch (Exception e) {
            return null; // never fail a stop over this
        }
    }

    public static List<String> interestingFields() {
        return INTERESTING;
    }

    private static ObjectReference lookup(ThreadReference thread, ReferenceType holder, String name) {
        Field holderField = holder.fieldByName(name);
        if (holderField == null) {
            return null;
        }
        Value threadLocal = holder.getValue(holderField);
        if (!(threadLocal instanceof ObjectReference)) {
            return null;
        }
        return valueFor(thread, (ObjectReference) threadLocal);
    }

    /**
     * A thread local's value for one thread, read out of the thread's own map.
     *
     * <p>ThreadLocal keeps nothing itself; the value lives in
     * {@code Thread.threadLocals}, a table of entries that are weak references to
     * the ThreadLocal with the value alongside. So: the thread's map, its table,
     * and the entry whose referent is the object we are looking for.
     */
    private static ObjectReference valueFor(ThreadReference thread, ObjectReference threadLocal) {
        Field mapField = thread.referenceType().fieldByName("threadLocals");
        if (mapField == null) {
            return null;
        }
        Value mapValue = thread.getValue(mapField);
        if (!(mapValue instanceof ObjectReference)) {
            return null; // the thread has never used a thread local
        }
        ObjectReference map = (ObjectReference) mapValue;
        Field tableField = map.referenceType().fieldByName("table");
        if (tableField == null) {
            return null;
        }
        Value tableValue = map.getValue(tableField);
        if (!(tableValue instanceof ArrayReference)) {
            return null;
        }
        for (Value entryValue : ((ArrayReference) tableValue).getValues()) {
            if (!(entryValue instanceof ObjectReference)) {
                continue;
            }
            ObjectReference entry = (ObjectReference) entryValue;
            Field referentField = entry.referenceType().fieldByName("referent");
            Field valueField = entry.referenceType().fieldByName("value");
            if (referentField == null || valueField == null) {
                continue;
            }
            if (threadLocal.equals(entry.getValue(referentField))) {
                Value value = entry.getValue(valueField);
                return value instanceof ObjectReference ? (ObjectReference) value : null;
            }
        }
        return null;
    }
}
