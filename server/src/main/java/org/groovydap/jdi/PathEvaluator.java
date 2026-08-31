package org.groovydap.jdi;

import com.sun.jdi.ArrayReference;
import com.sun.jdi.Field;
import com.sun.jdi.LocalVariable;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StackFrame;
import com.sun.jdi.Value;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolves a path expression against a stopped frame, by reading only.
 *
 * <p>Shapes like {@code user.name}, {@code params['id']} and
 * {@code items[0].label} -- what a hover or a watch is nearly always made of.
 * Nothing here invokes anything in the target VM: a method call means resuming
 * threads to run it, which can deadlock and can change what is being inspected.
 *
 * <p>That is also the limit. A call, an operator or a closure needs an expression
 * compiled and executed inside the application. Those are refused by name, rather
 * than answered wrongly or reported as missing -- {@code list.size()} exists, and
 * saying "no such field" about it would be a lie.
 */
public final class PathEvaluator {

    /** What the caller asked for and this cannot give, with the reason. */
    public static final class Unsupported extends Exception {
        private static final long serialVersionUID = 1L;

        Unsupported(String message) {
            super(message);
        }
    }

    private PathEvaluator() {
    }

    public static Value evaluate(String expression, StackFrame frame,
                                 ObjectReference webRequest) throws Unsupported {
        String text = expression == null ? "" : expression.trim();
        if (text.isEmpty()) {
            throw new Unsupported("empty expression");
        }
        rejectUnsupported(text);

        List<String> steps = split(text);
        Value current = resolveRoot(steps.get(0), frame, webRequest);
        for (int i = 1; i < steps.size(); i++) {
            current = step(current, steps.get(i), steps.get(i - 1));
        }
        return current;
    }

    /**
     * Turns away what path reading cannot answer, before it looks like a miss.
     *
     * <p>An expression with a call or an operator in it has an answer; this is just
     * not the thing that can produce it. Saying so is better than reporting no such
     * field for something that plainly exists.
     */
    private static void rejectUnsupported(String text) throws Unsupported {
        if (text.indexOf('(') >= 0) {
            throw new Unsupported(
                    "method calls are not supported: this reads values, it does not run code");
        }
        String[] operators = {"+", "-", "*", "/", "%", "=", "!", "<", ">", "?", "&", "|"};
        for (String operator : operators) {
            if (text.contains(operator)) {
                throw new Unsupported("operators are not supported: this reads values, "
                        + "it does not evaluate expressions");
            }
        }
        if (text.indexOf('{') >= 0) {
            throw new Unsupported("closures are not supported");
        }
    }

    /** Splits a path into its parts, keeping a bracket key as written. */
    private static List<String> split(String text) throws Unsupported {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '.') {
                parts.add(current.toString().trim());
                current.setLength(0);
            } else if (c == '[') {
                int close = text.indexOf(']', i);
                if (close < 0) {
                    throw new Unsupported("unbalanced bracket in " + text);
                }
                if (current.length() > 0) {
                    parts.add(current.toString().trim());
                    current.setLength(0);
                }
                parts.add("[" + text.substring(i + 1, close).trim() + "]");
                i = close;
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            parts.add(current.toString().trim());
        }
        if (parts.isEmpty() || parts.get(0).isEmpty()) {
            throw new Unsupported("could not read a name out of " + text);
        }
        return parts;
    }

    /**
     * The first name: a local, then a field of this, then a static of the frame
     * class, then the request. The order a reader of the source would try.
     */
    private static Value resolveRoot(String name, StackFrame frame, ObjectReference webRequest)
            throws Unsupported {
        if (name.equals("this")) {
            ObjectReference self = frame.thisObject();
            if (self == null) {
                throw new Unsupported("this is not available in a static frame");
            }
            return self;
        }
        try {
            LocalVariable local = frame.visibleVariableByName(name);
            if (local != null) {
                return frame.getValue(local);
            }
        } catch (Exception ignored) {
            // no local variable information here; keep looking
        }

        ObjectReference self = frame.thisObject();
        if (self != null) {
            Field field = self.referenceType().fieldByName(name);
            if (field != null) {
                return self.getValue(field);
            }
        }

        ReferenceType declaring = frame.location().declaringType();
        Field staticField = declaring.fieldByName(name);
        if (staticField != null && staticField.isStatic()) {
            return declaring.getValue(staticField);
        }

        if (webRequest != null) {
            Field requestField = webRequest.referenceType().fieldByName(name);
            if (requestField != null) {
                return webRequest.getValue(requestField);
            }
        }
        throw new Unsupported("no local, field or request attribute named " + name);
    }

    private static Value step(Value current, String accessor, String previous) throws Unsupported {
        if (current == null) {
            throw new Unsupported(previous + " is null");
        }
        if (accessor.charAt(0) == '[') {
            return index(current, accessor.substring(1, accessor.length() - 1), previous);
        }
        if (!(current instanceof ObjectReference)) {
            throw new Unsupported(previous + " is a " + current.type().name()
                    + ", which has no field " + accessor);
        }
        ObjectReference object = (ObjectReference) current;

        // A map reads by key before it reads by field: params.id means the
        // parameter, not a field of GrailsParameterMap.
        MapReader.Lookup byKey = MapReader.get(object, accessor);
        if (byKey.found) {
            return byKey.value;
        }
        Field field = object.referenceType().fieldByName(accessor);
        if (field == null) {
            throw new Unsupported(object.referenceType().name() + " has no field " + accessor);
        }
        return object.getValue(field);
    }

    private static Value index(Value current, String key, String previous) throws Unsupported {
        String unquoted = unquote(key);
        if (current instanceof ArrayReference) {
            return ((ArrayReference) current).getValue(asInt(unquoted, previous));
        }
        if (!(current instanceof ObjectReference)) {
            throw new Unsupported(previous + " cannot be indexed");
        }
        ObjectReference object = (ObjectReference) current;
        MapReader.Lookup byKey = MapReader.get(object, unquoted);
        if (byKey.found) {
            return byKey.value;
        }
        MapReader.Lookup byIndex = MapReader.element(object, asInt(unquoted, previous));
        if (byIndex.found) {
            return byIndex.value;
        }
        throw new Unsupported(previous + " is a " + object.referenceType().name()
                + ", with no such key and no element there");
    }

    private static int asInt(String text, String previous) throws Unsupported {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            throw new Unsupported(previous + " has no key " + text + ", and that is not an index");
        }
    }

    private static String unquote(String key) {
        if (key.length() >= 2) {
            char first = key.charAt(0);
            if ((first == '\'' || first == '"') && key.charAt(key.length() - 1) == first) {
                return key.substring(1, key.length() - 1);
            }
        }
        return key;
    }
}
