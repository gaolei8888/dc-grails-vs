package org.groovydap.jdi;

import com.sun.jdi.ClassType;
import com.sun.jdi.Method;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Type;
import com.sun.jdi.Value;
import com.sun.jdi.VirtualMachine;

import java.util.Collections;
import java.util.List;

/**
 * Turns what the user typed into a value the target VM will accept.
 *
 * <p>Everything here is a mirror -- {@code mirrorOf} allocates in the target
 * without running anything in it -- with one exception, and it is deliberate.
 *
 * <p><b>The exception: boxes.</b> Groovy boxes nearly everything, so a local the
 * source declares as {@code def i} or a value captured by a closure arrives as
 * {@code java.lang.Integer}, and JDI will not put an int into a slot typed
 * Integer. The only way to make one is {@code Integer.valueOf}, which means
 * invoking a method in the target. This adapter otherwise refuses to run code in
 * the application -- doing so to <em>describe</em> a stop can deadlock it or
 * change what is being looked at -- but setting a variable is not describing
 * anything: it is a change the user asked for, and refusing it would leave the
 * feature able to set almost nothing in a Groovy program. The invocation is
 * limited to {@code valueOf} on the eight JDK box classes, which take no locks
 * and have no side effects, and runs single-threaded so nothing else in the
 * application moves.
 */
public final class ValueFactory {

    private ValueFactory() {
    }

    /**
     * @param declared the type of the slot being written, which decides how the
     *     text is read: "5" is an int for an int field and a String for a String
     *     one
     */
    public static Value of(String text, Type declared, ThreadReference thread)
            throws PathEvaluator.Unsupported {
        VirtualMachine vm = thread.virtualMachine();
        String trimmed = text == null ? "" : text.trim();
        String type = declared == null ? "java.lang.Object" : declared.name();

        switch (type) {
            case "int": return vm.mirrorOf((int) integer(trimmed, Integer.MIN_VALUE, Integer.MAX_VALUE));
            case "long": return vm.mirrorOf(integer(trimmed, Long.MIN_VALUE, Long.MAX_VALUE));
            case "short": return vm.mirrorOf((short) integer(trimmed, Short.MIN_VALUE, Short.MAX_VALUE));
            case "byte": return vm.mirrorOf((byte) integer(trimmed, Byte.MIN_VALUE, Byte.MAX_VALUE));
            case "char": return vm.mirrorOf(character(trimmed));
            case "float": return vm.mirrorOf((float) decimal(trimmed));
            case "double": return vm.mirrorOf(decimal(trimmed));
            case "boolean": return vm.mirrorOf(bool(trimmed));
            case "java.lang.String":
                return trimmed.equals("null") ? null : vm.mirrorOf(unquote(trimmed));
            default:
                break;
        }

        if (trimmed.equals("null")) {
            return null;
        }
        if (Values.isBox(type)) {
            return box(type, trimmed, thread);
        }
        if (isQuoted(trimmed)) {
            return vm.mirrorOf(unquote(trimmed));
        }
        // An untyped slot -- Object, or the value field of a Reference. Read the
        // text the way Groovy would and box the result.
        if (trimmed.equals("true") || trimmed.equals("false")) {
            return box("java.lang.Boolean", trimmed, thread);
        }
        try {
            if (trimmed.indexOf('.') >= 0) {
                Double.parseDouble(trimmed);
                return box("java.lang.Double", trimmed, thread);
            }
            long value = Long.parseLong(trimmed);
            return box(value == (int) value ? "java.lang.Integer" : "java.lang.Long",
                    trimmed, thread);
        } catch (NumberFormatException e) {
            throw new PathEvaluator.Unsupported("cannot make a " + type + " out of "
                    + trimmed + ": this sets numbers, strings, booleans and null, "
                    + "not objects -- those would have to be constructed in the "
                    + "application");
        }
    }

    /** {@code Integer.valueOf(5)} and its seven siblings, run single-threaded. */
    private static Value box(String type, String text, ThreadReference thread)
            throws PathEvaluator.Unsupported {
        VirtualMachine vm = thread.virtualMachine();
        String primitive;
        String signature;
        Value argument;
        switch (type) {
            case "java.lang.Integer":
                primitive = "int"; signature = "(I)Ljava/lang/Integer;";
                argument = vm.mirrorOf((int) integer(text, Integer.MIN_VALUE, Integer.MAX_VALUE));
                break;
            case "java.lang.Long":
                primitive = "long"; signature = "(J)Ljava/lang/Long;";
                argument = vm.mirrorOf(integer(text, Long.MIN_VALUE, Long.MAX_VALUE));
                break;
            case "java.lang.Short":
                primitive = "short"; signature = "(S)Ljava/lang/Short;";
                argument = vm.mirrorOf((short) integer(text, Short.MIN_VALUE, Short.MAX_VALUE));
                break;
            case "java.lang.Byte":
                primitive = "byte"; signature = "(B)Ljava/lang/Byte;";
                argument = vm.mirrorOf((byte) integer(text, Byte.MIN_VALUE, Byte.MAX_VALUE));
                break;
            case "java.lang.Character":
                primitive = "char"; signature = "(C)Ljava/lang/Character;";
                argument = vm.mirrorOf(character(text));
                break;
            case "java.lang.Float":
                primitive = "float"; signature = "(F)Ljava/lang/Float;";
                argument = vm.mirrorOf((float) decimal(text));
                break;
            case "java.lang.Double":
                primitive = "double"; signature = "(D)Ljava/lang/Double;";
                argument = vm.mirrorOf(decimal(text));
                break;
            case "java.lang.Boolean":
                primitive = "boolean"; signature = "(Z)Ljava/lang/Boolean;";
                argument = vm.mirrorOf(bool(text));
                break;
            default:
                throw new PathEvaluator.Unsupported(type + " cannot be made here");
        }

        List<ReferenceType> classes = vm.classesByName(type);
        if (classes.isEmpty() || !(classes.get(0) instanceof ClassType)) {
            throw new PathEvaluator.Unsupported(type + " is not loaded in the target");
        }
        ClassType box = (ClassType) classes.get(0);
        Method valueOf = box.concreteMethodByName("valueOf", signature);
        if (valueOf == null) {
            throw new PathEvaluator.Unsupported(
                    "no " + type + ".valueOf(" + primitive + ") in the target");
        }
        try {
            return box.invokeMethod(thread, valueOf, Collections.singletonList(argument),
                    ObjectReference.INVOKE_SINGLE_THREADED);
        } catch (Exception e) {
            throw new PathEvaluator.Unsupported("could not make a " + type + ": " + e);
        }
    }

    private static long integer(String text, long min, long max)
            throws PathEvaluator.Unsupported {
        try {
            long value = Long.parseLong(unquote(text));
            if (value < min || value > max) {
                throw new PathEvaluator.Unsupported(text + " does not fit");
            }
            return value;
        } catch (NumberFormatException e) {
            throw new PathEvaluator.Unsupported(text + " is not a whole number");
        }
    }

    private static double decimal(String text) throws PathEvaluator.Unsupported {
        try {
            return Double.parseDouble(unquote(text));
        } catch (NumberFormatException e) {
            throw new PathEvaluator.Unsupported(text + " is not a number");
        }
    }

    private static boolean bool(String text) throws PathEvaluator.Unsupported {
        String value = unquote(text);
        if (value.equals("true")) {
            return true;
        }
        if (value.equals("false")) {
            return false;
        }
        throw new PathEvaluator.Unsupported(text + " is not true or false");
    }

    private static char character(String text) throws PathEvaluator.Unsupported {
        String value = unquote(text);
        if (value.length() != 1) {
            throw new PathEvaluator.Unsupported(text + " is not a single character");
        }
        return value.charAt(0);
    }

    private static boolean isQuoted(String text) {
        if (text.length() < 2) {
            return false;
        }
        char first = text.charAt(0);
        return (first == '\'' || first == '"') && text.charAt(text.length() - 1) == first;
    }

    private static String unquote(String text) {
        return isQuoted(text) ? text.substring(1, text.length() - 1) : text;
    }
}
