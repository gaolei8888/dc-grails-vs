package org.groovydap.jdi;

import com.sun.jdi.Field;
import com.sun.jdi.ObjectReference;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fields the user has asked to be told about, and the tokens that name them.
 *
 * <p>DAP hands a data breakpoint around as an opaque {@code dataId} string: the
 * client asks what can be watched about a variable, gets an id back, and later
 * sends that id to arm the breakpoint. The id has to survive that round trip and
 * name a field -- and, where there is one, the single object whose field it is,
 * since "tell me when this changes" means this one, not every instance of the
 * class.
 *
 * <p>Ids are session-scoped and reported as not persistable: an
 * {@link ObjectReference} is a handle into one connection to one VM, and a token
 * remembered across sessions would name nothing.
 */
public final class DataPoints {

    /** One watchable field, with the instance it belongs to if it has one. */
    public static final class Point {
        public final Field field;
        /** Null for a static field, or when only the class could be resolved. */
        public final ObjectReference instance;
        public final String label;

        Point(Field field, ObjectReference instance, String label) {
            this.field = field;
            this.instance = instance;
            this.label = label;
        }
    }

    private final Map<String, Point> points = new LinkedHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(1);

    /**
     * Registers a field and returns the token for it.
     *
     * <p>The instance is pinned against collection: the point of the breakpoint is
     * this object, and if the target collects it between the click and the write
     * the request would quietly watch nothing.
     */
    public synchronized String register(Field field, ObjectReference instance, String label) {
        if (instance != null) {
            try {
                instance.disableCollection();
            } catch (RuntimeException e) {
                // already collected, or the VM will not pin it; the request can
                // still be made, it may just never fire
            }
        }
        String id = "field:" + nextId.getAndIncrement();
        points.put(id, new Point(field, instance, label));
        return id;
    }

    public synchronized Point get(String dataId) {
        return points.get(dataId);
    }
}
