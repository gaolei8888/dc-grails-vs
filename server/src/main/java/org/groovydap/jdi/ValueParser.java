package org.groovydap.jdi;

import com.sun.jdi.Value;
import com.sun.jdi.VirtualMachine;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns what the user typed into a value to store, and refuses everything that
 * would need the application to run code to build.
 *
 * <p>Only literals, and only ones that need no object: the eight primitives, a
 * String (the one object the VM can make by itself, {@code mirrorOf}) and
 * {@code null}. Boxing an int into an {@code Integer} is {@code Integer.valueOf},
 * which is application code, and mutating a boxed value in place would corrupt
 * the shared Integer cache for the whole JVM -- so those are refused, not worked
 * around. No narrowing and no silent rounding: the literal has to fit the
 * declared type as written, apart from an integer literal widening to float or
 * double when that is exact.
 */
public final class ValueParser {

    public static final String LITERAL_ONLY = "value must be a literal: a number, true or false, "
            + "'c', \"text\" or null (the debugger does not run code in the application)";
    public static final String NEEDS_OBJECT = "changing it would need to create an object in "
            + "the application, which the debugger does not do";

    private static final Pattern INTEGER =
            Pattern.compile("([+-]?)(0[xX][0-9a-fA-F]+|[0-9]+)([lL]?)");
    private static final Pattern DECIMAL =
            Pattern.compile("[+-]?(?:[0-9]+\\.?[0-9]*|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?([fFdD]?)");
    /** Width in bits of each integer type; the table everything else is derived from. */
    private static final Map<String, Integer> INTEGER_BITS =
            Map.of("byte", 8, "short", 16, "int", 32, "long", 64);

    private ValueParser() {
    }

    public static boolean isPrimitive(String type) {
        return INTEGER_BITS.containsKey(type) || type.equals("float") || type.equals("double")
                || type.equals("boolean") || type.equals("char");
    }

    /**
     * @param declared the declared type of the target, e.g. {@code int} or {@code java.lang.Integer}
     * @param held the type of what the target holds now (the declared type if it is null)
     * @return the value to store; null is the null reference
     * @throws EvaluationException with the reason, for anything that is not storable
     */
    public static Value parse(VirtualMachine vm, String name, String declared, String held,
            String text) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) {
            throw new EvaluationException("the value is empty");
        }
        if (isPrimitive(declared)) {
            if (t.equals("null")) {
                throw new EvaluationException("cannot assign null to " + declared);
            }
            return primitive(vm, declared, t);
        }
        if (t.equals("null")) {
            return null;
        }
        String string = unquote(t);
        if (string != null) {
            return vm.mirrorOf(string); // JDI refuses it if the target cannot hold a String
        }
        if (!isLiteral(t)) {
            throw new EvaluationException(LITERAL_ONLY);
        }
        if (declared.equals("java.lang.String")) {
            throw new EvaluationException("'" + name + "' is a java.lang.String; "
                    + "write the value in quotes, e.g. \"" + t + "\"");
        }
        throw new EvaluationException("'" + name + "' holds a " + held + "; " + NEEDS_OBJECT);
    }

    private static Value primitive(VirtualMachine vm, String type, String t) {
        switch (type) {
            case "boolean":
                if (t.equals("true") || t.equals("false")) {
                    return vm.mirrorOf(t.equals("true"));
                }
                throw reject(t, type);
            case "char":
                return vm.mirrorOf(character(t));
            case "float":
            case "double":
                return decimal(vm, type, t);
            default:
                return integer(vm, type, t);
        }
    }

    private static Value integer(VirtualMachine vm, String type, String t) {
        Matcher m = INTEGER.matcher(t);
        if (!m.matches() || (!m.group(3).isEmpty() && !type.equals("long"))) {
            throw reject(t, type);
        }
        BigInteger v = integerOf(m);
        if (v.bitLength() >= INTEGER_BITS.get(type)) {
            throw new EvaluationException(t + " does not fit in " + type);
        }
        switch (type) {
            case "byte": return vm.mirrorOf(v.byteValue());
            case "short": return vm.mirrorOf(v.shortValue());
            case "int": return vm.mirrorOf(v.intValue());
            default: return vm.mirrorOf(v.longValue());
        }
    }

    private static Value decimal(VirtualMachine vm, String type, String t) {
        boolean isFloat = type.equals("float");
        Matcher whole = INTEGER.matcher(t);
        double d;
        if (whole.matches()) { // widening int -> float/double, allowed only when exact
            BigInteger v = integerOf(whole);
            d = isFloat ? v.floatValue() : v.doubleValue();
            if (Double.isInfinite(d) || !new BigDecimal(d).toBigInteger().equals(v)) {
                throw new EvaluationException(t + " does not fit exactly in " + type);
            }
        } else {
            Matcher m = DECIMAL.matcher(t);
            String suffix = m.matches() ? m.group(1).toLowerCase() : "";
            if (!m.matches() || (suffix.equals("f") && !isFloat) || (suffix.equals("d") && isFloat)) {
                throw reject(t, type);
            }
            String body = suffix.isEmpty() ? t : t.substring(0, t.length() - 1);
            d = isFloat ? Float.parseFloat(body) : Double.parseDouble(body);
            if (Double.isInfinite(d)) {
                throw new EvaluationException(t + " does not fit in " + type);
            }
            if (d == 0 && new BigDecimal(body).signum() != 0) {
                throw new EvaluationException(t + " is too small for " + type);
            }
        }
        return isFloat ? vm.mirrorOf((float) d) : vm.mirrorOf(d);
    }

    private static char character(String t) {
        if (t.startsWith("'")) {
            String s = unquote(t);
            if (s.length() == 1) {
                return s.charAt(0);
            }
            throw new EvaluationException(t + " is not a char literal (one character, like 'x')");
        }
        // A bare character is taken as itself, unless it could be a variable name:
        // `y` is far more likely to mean the variable than the letter.
        if (t.length() == 1 && !Character.isJavaIdentifierStart(t.charAt(0)) && t.charAt(0) != '"') {
            return t.charAt(0);
        }
        throw reject(t, "char");
    }

    private static BigInteger integerOf(Matcher m) {
        String digits = m.group(2);
        boolean hex = digits.length() > 1 && (digits.charAt(1) == 'x' || digits.charAt(1) == 'X');
        BigInteger v = hex ? new BigInteger(digits.substring(2), 16) : new BigInteger(digits);
        return m.group(1).equals("-") ? v.negate() : v;
    }

    /** A literal of some other kind than the target wants, or no literal at all. */
    private static EvaluationException reject(String t, String type) {
        return new EvaluationException(isLiteral(t)
                ? t + " is not " + ("int".equals(type) ? "an" : "a") + " " + type + " literal"
                : LITERAL_ONLY);
    }

    private static boolean isLiteral(String t) {
        return t.equals("null") || t.equals("true") || t.equals("false")
                || INTEGER.matcher(t).matches() || DECIMAL.matcher(t).matches()
                || t.startsWith("'") || t.startsWith("\"");
    }

    /** The text of a quoted literal, or null if {@code t} is not quoted. */
    static String unquote(String t) {
        char quote = t.charAt(0);
        if (quote != '"' && quote != '\'') {
            return null;
        }
        StringBuilder out = new StringBuilder();
        for (int i = 1; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == quote) {
                if (i == t.length() - 1) {
                    return out.toString();
                }
                break; // an unescaped quote before the end
            }
            if (c != '\\') {
                out.append(c);
                continue;
            }
            if (++i >= t.length()) {
                break;
            }
            char e = t.charAt(i);
            int plain = "\\\"'ntrbf".indexOf(e);
            if (plain >= 0) {
                out.append("\\\"'\n\t\r\b\f".charAt(plain));
            } else if (e == 'u' && i + 4 < t.length() && t.substring(i + 1, i + 5).matches("[0-9a-fA-F]{4}")) {
                out.append((char) Integer.parseInt(t.substring(i + 1, i + 5), 16));
                i += 4;
            } else {
                throw new EvaluationException("unknown escape \\" + e + " in " + t);
            }
        }
        throw new EvaluationException("unterminated literal, or an unescaped quote inside it: " + t);
    }
}
