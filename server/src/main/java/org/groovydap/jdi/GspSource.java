package org.groovydap.jdi;

import com.sun.jdi.ArrayReference;
import com.sun.jdi.ClassObjectReference;
import com.sun.jdi.Field;
import com.sun.jdi.IntegerValue;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.Value;
import com.sun.jdi.VirtualMachine;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A GSP, which reaches bytecode through one more indirection than a .groovy file.
 *
 * <p>A GSP is compiled into a Groovy class whose line numbers are the generated
 * Groovy's, not the page's, so {@code locationsOfLine} cannot be asked about a
 * GSP line directly. Everything needed to translate was measured on a running
 * Grails 7.2.3 application; see design doc §7.9.
 *
 * <ul>
 *   <li><b>There is no SMAP.</b> The page class reports {@code availableStrata()}
 *       of {@code [Java]} and nothing else, so JDI's JSR-45 support has nothing to
 *       work with. The compiler does emit a mapping, but into a
 *       {@code @LineNumber} annotation, and JDI has no API for annotations.</li>
 *   <li><b>The class is named after the file's path</b>, every character that is
 *       not a letter or digit turned into an underscore, and
 *       {@code sourceName()} returns that same string rather than a file name.
 *       So the class can be named without asking the target anything.</li>
 *   <li><b>The mapping is a plain field.</b> {@code GroovyPageMetaInfo.lineNumbers}
 *       is an {@code int[]} indexed by generated line, zero based:
 *       {@code lineNumbers[G - 1]} is the GSP line of generated line G. Checked
 *       against the generated Groovy itself, which Grails will write out with
 *       {@code grails.views.gsp.keepgenerateddir}.</li>
 * </ul>
 */
public final class GspSource {

    private static final String META_INFO = "org.grails.gsp.GroovyPageMetaInfo";

    /** Enough to cover a page; the array itself is 1000 long and mostly zeros. */
    private static final int MAX_INSTANCES = 200;

    private GspSource() {
    }

    public static boolean isGsp(String path) {
        return path != null && path.toLowerCase(Locale.ROOT).endsWith(".gsp");
    }

    /**
     * The class a GSP compiles into.
     *
     * <p>Measured: {@code C:\...\grails-app\views\spike\page.gsp} becomes
     * {@code C__Users_..._grails_app_views_spike_page_gsp}. Drive separator, path
     * separators, dots and dashes all become underscores, which is the same rule
     * as "everything that is not a letter or a digit".
     */
    public static String classNameFor(Path path) {
        String absolute = path.toAbsolutePath().toString();
        StringBuilder name = new StringBuilder(absolute.length());
        for (int i = 0; i < absolute.length(); i++) {
            char c = absolute.charAt(i);
            name.append(Character.isLetterOrDigit(c) ? c : '_');
        }
        return name.toString();
    }

    /** The page class of any class from a GSP, closures included. */
    public static String pageClassNameOf(String className) {
        int inner = className.indexOf('$');
        return inner < 0 ? className : className.substring(0, inner);
    }

    /**
     * The generated-line to GSP-line mapping for a page, or null.
     *
     * <p>Found by walking the live {@code GroovyPageMetaInfo} objects. Two shapes
     * occur, and both were measured:
     *
     * <ul>
     *   <li>After the page has run, the metaInfo's {@code pageClass} is the class,
     *       so it can be matched exactly.</li>
     *   <li><b>At the moment the page class is prepared -- which is when a
     *       breakpoint has to be armed -- {@code pageClass} is still null</b>, and
     *       {@code lineNumbers} is already filled in. The metaInfo is built, given
     *       its matrix, and only then handed the class it belongs to. So an
     *       unlinked metaInfo at that moment is the one for the class just
     *       prepared. If more than one is unlinked, this refuses rather than
     *       guessing: the closures of the same page prepare later and give another
     *       chance, with the link in place.</li>
     * </ul>
     */
    public static int[] lineNumbers(VirtualMachine vm, ReferenceType pageClass) {
        List<ReferenceType> types = vm.classesByName(META_INFO);
        if (types.isEmpty() || !vm.canGetInstanceInfo()) {
            return null;
        }
        List<int[]> unlinked = new ArrayList<>();
        for (ReferenceType type : types) {
            Field pageClassField = type.fieldByName("pageClass");
            Field lineNumbersField = type.fieldByName("lineNumbers");
            if (pageClassField == null || lineNumbersField == null) {
                continue;
            }
            for (ObjectReference metaInfo : type.instances(MAX_INSTANCES)) {
                int[] matrix = intArray(metaInfo.getValue(lineNumbersField));
                if (matrix == null) {
                    continue;
                }
                Value owner = metaInfo.getValue(pageClassField);
                if (owner instanceof ClassObjectReference) {
                    if (((ClassObjectReference) owner).reflectedType().equals(pageClass)) {
                        return matrix;
                    }
                } else if (owner == null) {
                    unlinked.add(matrix);
                }
            }
        }
        return unlinked.size() == 1 ? unlinked.get(0) : null;
    }

    /**
     * Every generated line that came from this GSP line.
     *
     * <p>All of them, not the first: the same reason the Groovy binder arms every
     * location of a line. One GSP line becomes several generated statements and
     * there is no telling from here which of them runs.
     */
    public static List<Integer> generatedLines(int[] lineNumbers, int gspLine) {
        List<Integer> generated = new ArrayList<>();
        for (int index = 0; index < lineNumbers.length; index++) {
            if (lineNumbers[index] == gspLine) {
                generated.add(index + 1);
            }
        }
        return generated;
    }

    /** The GSP line a generated line came from, or -1. */
    public static int gspLine(int[] lineNumbers, int generatedLine) {
        int index = generatedLine - 1;
        if (index < 0 || index >= lineNumbers.length) {
            return -1;
        }
        int line = lineNumbers[index];
        return line > 0 ? line : -1;
    }

    /** The first GSP line at or after this one that any generated line came from. */
    public static int firstMappedLineAtOrAfter(int[] lineNumbers, int gspLine, int limit) {
        for (int candidate = gspLine; candidate <= gspLine + limit; candidate++) {
            for (int value : lineNumbers) {
                if (value == candidate) {
                    return candidate;
                }
            }
        }
        return gspLine;
    }

    private static int[] intArray(Value value) {
        if (!(value instanceof ArrayReference)) {
            return null;
        }
        ArrayReference array = (ArrayReference) value;
        List<Value> values = array.getValues();
        int[] out = new int[values.size()];
        for (int i = 0; i < out.length; i++) {
            Value element = values.get(i);
            out[i] = element instanceof IntegerValue ? ((IntegerValue) element).value() : 0;
        }
        return out;
    }
}
