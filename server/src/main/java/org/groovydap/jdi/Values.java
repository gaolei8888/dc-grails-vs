package org.groovydap.jdi;

import com.sun.jdi.ArrayReference;
import com.sun.jdi.Field;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.Value;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * The two unwrappings every reader of a Groovy value needs.
 *
 * <p>Kept in one place because the variables pane, a hover, a log message and a
 * breakpoint condition all have to do them, and a box list that disagreed between
 * two of those would show a value one way and compare it another.
 */
public final class Values {

    /** The box Groovy puts around a local that a closure captures. */
    private static final String REFERENCE_CLASS = "groovy.lang.Reference";

    /**
     * Boxed primitives. Groovy boxes far more than Java does -- every value that
     * crosses a metaclass call site arrives as an Integer -- so leaving these
     * wrapped hides most of what anyone is looking at.
     */
    private static final Set<String> BOXES = new HashSet<>(Arrays.asList(
            "java.lang.Integer", "java.lang.Long", "java.lang.Short", "java.lang.Byte",
            "java.lang.Double", "java.lang.Float", "java.lang.Boolean",
            "java.lang.Character"));

    private Values() {
    }

    /** One level out of {@code groovy.lang.Reference}, or the value unchanged. */
    public static Value unwrapReference(Value value) {
        if (!(value instanceof ObjectReference)) {
            return value;
        }
        ObjectReference object = (ObjectReference) value;
        if (!REFERENCE_CLASS.equals(object.referenceType().name())) {
            return value;
        }
        Field field = object.referenceType().fieldByName("value");
        return field == null ? value : object.getValue(field);
    }

    public static boolean isBox(String typeName) {
        return BOXES.contains(typeName);
    }

    /**
     * What a boxed primitive holds, read as a field.
     *
     * <p>A field read, not an {@code intValue()} call: invoking a method in the
     * target VM means resuming its threads to run it, and a debugger that runs
     * application code to describe a value can deadlock or change what it is
     * describing.
     *
     * @return null if this is not a box, or holds something that is not a primitive
     */
    public static Value boxedValue(ObjectReference object) {
        if (!BOXES.contains(object.referenceType().name())) {
            return null;
        }
        Field field = object.referenceType().fieldByName("value");
        if (field == null) {
            return null;
        }
        Value inner = object.getValue(field);
        // BigInteger keeps an int[]; nothing here can say anything useful about it.
        return inner instanceof ArrayReference ? null : inner;
    }
}
