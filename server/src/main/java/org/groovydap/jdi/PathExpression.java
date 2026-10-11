package org.groovydap.jdi;

import java.util.ArrayList;
import java.util.List;

/**
 * The only expressions the debugger evaluates: a variable and a path out of it.
 *
 * <pre>
 *   path  := name ( '.' name | '[' index ']' | '[' quoted ']' )*
 *   name  := a Java identifier (this included)
 *   index := digits
 *   quoted := 'text' | "text"        (no escapes)
 * </pre>
 *
 * <p>Everything else -- operators, calls, literals, assignment, {@code new} -- would
 * need code run in the application, and running code means resuming its threads.
 * So it is refused here, by the grammar, rather than half-supported.
 */
public final class PathExpression {

    public static final String READ_ONLY =
            "only variable paths can be evaluated here "
                    + "(the debugger does not run code in the application)";

    /** One hop out of the value before it. */
    public static final class Step {
        enum Kind { NAME, INDEX, KEY }

        final Kind kind;
        final String text;
        final int index;

        private Step(Kind kind, String text, int index) {
            this.kind = kind;
            this.text = text;
            this.index = index;
        }

        /** A member name: a local or field, or a map key written bare. */
        boolean isMember() {
            return kind != Kind.INDEX;
        }

        @Override
        public String toString() {
            switch (kind) {
                case NAME: return "." + text;
                case KEY: return "['" + text + "']";
                default: return "[" + index + "]";
            }
        }
    }

    private final String in;
    private int pos;

    private PathExpression(String in) {
        this.in = in;
    }

    /** @throws EvaluationException with the user-facing reason if this is not a path. */
    public static List<Step> parse(String expression) {
        String text = expression == null ? "" : expression.trim();
        if (text.isEmpty()) {
            throw new EvaluationException("the expression is empty");
        }
        return new PathExpression(text).path();
    }

    private List<Step> path() {
        List<Step> steps = new ArrayList<>();
        steps.add(new Step(Step.Kind.NAME, identifier(), 0));
        while (skipSpaces() < in.length()) {
            char c = in.charAt(pos);
            if (c == '.') {
                pos++;
                skipSpaces();
                steps.add(new Step(Step.Kind.NAME, identifier(), 0));
            } else if (c == '[') {
                pos++;
                skipSpaces();
                steps.add(subscript());
                if (skipSpaces() >= in.length() || in.charAt(pos) != ']') {
                    throw unsupported();
                }
                pos++;
            } else {
                throw unsupported();
            }
        }
        return steps;
    }

    private Step subscript() {
        if (pos >= in.length()) {
            throw unsupported();
        }
        char c = in.charAt(pos);
        if (c == '\'' || c == '"') {
            int end = in.indexOf(c, pos + 1);
            if (end < 0) {
                throw unsupported();
            }
            String key = in.substring(pos + 1, end);
            if (key.indexOf('\\') >= 0) {
                throw unsupported();
            }
            pos = end + 1;
            return new Step(Step.Kind.KEY, key, 0);
        }
        int start = pos;
        while (pos < in.length() && in.charAt(pos) >= '0' && in.charAt(pos) <= '9') {
            pos++;
        }
        if (pos == start) {
            throw unsupported();
        }
        try {
            return new Step(Step.Kind.INDEX, null, Integer.parseInt(in.substring(start, pos)));
        } catch (NumberFormatException e) {
            throw unsupported();
        }
    }

    private String identifier() {
        int start = pos;
        if (pos >= in.length() || !Character.isJavaIdentifierStart(in.charAt(pos))) {
            throw unsupported();
        }
        pos++;
        while (pos < in.length() && Character.isJavaIdentifierPart(in.charAt(pos))) {
            pos++;
        }
        return in.substring(start, pos);
    }

    private int skipSpaces() {
        while (pos < in.length() && Character.isWhitespace(in.charAt(pos))) {
            pos++;
        }
        return pos;
    }

    private static EvaluationException unsupported() {
        return new EvaluationException(READ_ONLY);
    }
}
