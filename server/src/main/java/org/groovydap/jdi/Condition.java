package org.groovydap.jdi;

import com.sun.jdi.BooleanValue;
import com.sun.jdi.CharValue;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.PrimitiveValue;
import com.sun.jdi.StackFrame;
import com.sun.jdi.StringReference;
import com.sun.jdi.Value;

/**
 * A breakpoint condition, as far as it can be answered without running code.
 *
 * <p>A full condition means compiling a Groovy expression and executing it inside
 * the target VM, which is a large piece of work and one that resumes the
 * application's threads to get its answer. But the conditions people actually
 * type are comparisons:
 *
 * <pre>
 *   params.id == '5'      user.name != null      i &gt; 3      w.count &lt;= limit
 * </pre>
 *
 * <p>Each side of one of those is a path or a literal, and {@link PathEvaluator}
 * already reads paths. So this handles comparisons and refuses everything else by
 * name, the same way a hover does -- a condition quietly treated as "true" would
 * be a breakpoint that stops when the user said not to, and one treated as
 * "false" would be a breakpoint that never fires and looks broken.
 */
public final class Condition {

    private enum Operator { EQ, NE, LT, LE, GT, GE }

    /** Longest first: "&gt;=" has to be recognised before "&gt;". */
    private static final String[][] OPERATORS = {
        {"==", "EQ"}, {"!=", "NE"}, {">=", "GE"}, {"<=", "LE"}, {">", "GT"}, {"<", "LT"},
    };

    private final String left;
    private final Operator operator;
    private final String right;

    private Condition(String left, Operator operator, String right) {
        this.left = left;
        this.operator = operator;
        this.right = right;
    }

    /**
     * @return the condition, or null if this is not a comparison of two paths or
     *     literals -- which the caller reports rather than guessing at
     */
    public static Condition parse(String text) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        for (String[] candidate : OPERATORS) {
            int at = indexOfOperator(trimmed, candidate[0]);
            if (at < 0) {
                continue;
            }
            String left = trimmed.substring(0, at).trim();
            String right = trimmed.substring(at + candidate[0].length()).trim();
            if (left.isEmpty() || right.isEmpty()) {
                return null;
            }
            return new Condition(left, Operator.valueOf(candidate[1]), right);
        }
        return null;
    }

    /** The operator's position, ignoring anything inside quotes or brackets. */
    private static int indexOfOperator(String text, String operator) {
        char quote = 0;
        int brackets = 0;
        for (int i = 0; i + operator.length() <= text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                continue;
            }
            if (c == '[') {
                brackets++;
                continue;
            }
            if (c == ']') {
                brackets--;
                continue;
            }
            if (brackets == 0 && text.startsWith(operator, i)) {
                // "=" of "==" must not be read as the start of a bare "="; the
                // table is ordered longest first, so only the reverse can happen:
                // ">" matching the ">" of ">=". Look ahead for that one case.
                if (operator.length() == 1 && i + 1 < text.length()
                        && text.charAt(i + 1) == '=') {
                    continue;
                }
                return i;
            }
        }
        return -1;
    }

    /**
     * @throws PathEvaluator.Unsupported when a side cannot be read -- a call, an
     *     operator, or a name that is not in scope here
     */
    public boolean test(StackFrame frame, ObjectReference webRequest)
            throws PathEvaluator.Unsupported {
        Object a = side(left, frame, webRequest);
        Object b = side(right, frame, webRequest);

        if (operator == Operator.EQ || operator == Operator.NE) {
            boolean equal = equal(a, b);
            return operator == Operator.EQ ? equal : !equal;
        }

        if (!(a instanceof Comparable) || !(b instanceof Comparable)) {
            throw new PathEvaluator.Unsupported(
                    "cannot order " + describe(a) + " against " + describe(b));
        }
        int order;
        if (a instanceof Number && b instanceof Number) {
            order = Double.compare(((Number) a).doubleValue(), ((Number) b).doubleValue());
        } else if (a.getClass() == b.getClass()) {
            @SuppressWarnings("unchecked")
            Comparable<Object> left = (Comparable<Object>) a;
            order = left.compareTo(b);
        } else {
            throw new PathEvaluator.Unsupported(
                    "cannot order " + describe(a) + " against " + describe(b));
        }
        switch (operator) {
            case LT: return order < 0;
            case LE: return order <= 0;
            case GT: return order > 0;
            case GE: return order >= 0;
            default: return false;
        }
    }

    private static boolean equal(Object a, Object b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a instanceof Number && b instanceof Number) {
            return ((Number) a).doubleValue() == ((Number) b).doubleValue();
        }
        // Groovy's == is equals(), not identity, which is what anyone writing
        // params.id == '5' means. Object references that are not values compare
        // by identity because that is all that can be known without calling
        // equals() in the target.
        return a.equals(b);
    }

    /** A literal if it looks like one, otherwise a path read from the frame. */
    private static Object side(String text, StackFrame frame, ObjectReference webRequest)
            throws PathEvaluator.Unsupported {
        Object literal = literal(text);
        if (literal != NOT_A_LITERAL) {
            return literal;
        }
        return plain(PathEvaluator.evaluate(text, frame, webRequest));
    }

    private static final Object NOT_A_LITERAL = new Object();

    private static Object literal(String text) {
        if (text.equals("null")) {
            return null;
        }
        if (text.equals("true")) {
            return Boolean.TRUE;
        }
        if (text.equals("false")) {
            return Boolean.FALSE;
        }
        if (text.length() >= 2) {
            char first = text.charAt(0);
            if ((first == '\'' || first == '"') && text.charAt(text.length() - 1) == first) {
                return text.substring(1, text.length() - 1);
            }
        }
        try {
            if (text.indexOf('.') >= 0) {
                return Double.valueOf(text);
            }
            return Long.valueOf(text);
        } catch (NumberFormatException e) {
            return NOT_A_LITERAL;
        }
    }

    /**
     * A JDI value as something Java can compare.
     *
     * <p>Boxed values are read out of their {@code value} field rather than by
     * calling {@code intValue()}: Groovy boxes everything that crosses a call site,
     * so most of what a condition compares arrives as an Integer, and invoking a
     * method in the target VM to unwrap it would mean resuming it.
     */
    private static Object plain(Value raw) {
        Value value = Values.unwrapReference(raw);
        if (value == null) {
            return null;
        }
        if (value instanceof StringReference) {
            return ((StringReference) value).value();
        }
        if (value instanceof BooleanValue) {
            return ((BooleanValue) value).value();
        }
        if (value instanceof CharValue) {
            return ((CharValue) value).value();
        }
        if (value instanceof PrimitiveValue) {
            PrimitiveValue primitive = (PrimitiveValue) value;
            double asDouble = primitive.doubleValue();
            return asDouble == Math.rint(asDouble) && !Double.isInfinite(asDouble)
                    ? (Object) primitive.longValue() : (Object) asDouble;
        }
        if (value instanceof ObjectReference) {
            Value boxed = Values.boxedValue((ObjectReference) value);
            if (boxed != null) {
                return plain(boxed);
            }
        }
        return value; // compared by identity, which is all that can be known
    }

    private static String describe(Object value) {
        if (value == null) {
            return "null";
        }
        return value instanceof Value ? ((Value) value).type().name()
                : value.getClass().getSimpleName();
    }
}
