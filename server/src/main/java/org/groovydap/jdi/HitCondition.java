package org.groovydap.jdi;

/**
 * How many times a breakpoint has to be hit before it stops.
 *
 * <p>The syntax is the one the Java and Node debuggers use, because that is what
 * anyone typing into the Hit Count box in VSCode has typed before: an optional
 * operator and a number.
 *
 * <pre>
 *   100     stop from the 100th hit onward   (same as &gt;=100)
 *   &gt;=100   the same, written out
 *   &gt;100    stop from the 101st
 *   =100     stop on the 100th hit only      (== is accepted too)
 *   &lt;100    stop until the 99th
 *   &lt;=100   stop until the 100th
 *   %10     stop on every 10th hit
 * </pre>
 *
 * <p>A bare number reads as "after this many", which is what the VSCode
 * documentation says a hit count is and what gdb's ignore count does. Stopping on
 * exactly one iteration is {@code =100}.
 *
 * <p>The count this tests is hits that were going to stop -- the duplicate a
 * Groovy line produces has already been dropped by {@link StopDeduper} before the
 * count is raised, so it counts the same thing the user counts.
 */
public final class HitCondition {

    private enum Operator { AT_LEAST, ABOVE, EQUAL, BELOW, AT_MOST, EVERY }

    private final Operator operator;
    private final long operand;

    private HitCondition(Operator operator, long operand) {
        this.operator = operator;
        this.operand = operand;
    }

    /**
     * @return the condition, or null if this is not one -- an empty box, or
     *     something that is not an operator and a number. The caller reports that
     *     rather than guessing, because a hit condition silently ignored looks
     *     exactly like a debugger that stops when it should not.
     */
    public static HitCondition parse(String text) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        Operator operator = Operator.AT_LEAST;
        int start = 0;
        if (trimmed.startsWith(">=")) {
            operator = Operator.AT_LEAST;
            start = 2;
        } else if (trimmed.startsWith("<=")) {
            operator = Operator.AT_MOST;
            start = 2;
        } else if (trimmed.startsWith("==")) {
            operator = Operator.EQUAL;
            start = 2;
        } else if (trimmed.startsWith(">")) {
            operator = Operator.ABOVE;
            start = 1;
        } else if (trimmed.startsWith("<")) {
            operator = Operator.BELOW;
            start = 1;
        } else if (trimmed.startsWith("=")) {
            operator = Operator.EQUAL;
            start = 1;
        } else if (trimmed.startsWith("%")) {
            operator = Operator.EVERY;
            start = 1;
        }

        long operand;
        try {
            operand = Long.parseLong(trimmed.substring(start).trim());
        } catch (NumberFormatException e) {
            return null;
        }
        if (operand <= 0) {
            return null; // every operator here is meaningless at zero, % worst of all
        }
        return new HitCondition(operator, operand);
    }

    /** @param count how many times this breakpoint has now been hit, starting at 1 */
    public boolean test(long count) {
        switch (operator) {
            case AT_LEAST: return count >= operand;
            case ABOVE: return count > operand;
            case EQUAL: return count == operand;
            case BELOW: return count < operand;
            case AT_MOST: return count <= operand;
            case EVERY: return count % operand == 0;
            default: return true;
        }
    }
}
