package org.groovydap.jdi;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A .groovy source file, reduced to what breakpoint binding needs: the file name
 * the JVM reports in {@code sourceName()}, the class-name prefixes its classes
 * share, and the raw lines.
 *
 * <p>Deriving those takes two regexes over the file text, not a parser. That is
 * the point of the design: in attach mode the JVM is the authority on which class
 * owns a line ({@code locationsOfLine}), so the adapter only has to narrow the
 * candidate set cheaply. See docs/2026-08-29-vscode-groovy-debug-adapter.md §4.
 */
public final class SourceRef {

    private static final Pattern PACKAGE =
            Pattern.compile("^\\s*package\\s+([\\w.]+)", Pattern.MULTILINE);

    /**
     * Every type declared in the file. A Groovy file need not be named after the
     * class it holds, and may hold several, so the base name alone is not enough.
     * Nested types need no entry -- they are covered by their outer type's
     * {@code Outer$} prefix -- and a spurious match costs one more prefix that
     * nothing will ever match.
     */
    private static final Pattern TYPE_DECL =
            Pattern.compile("\\b(?:class|interface|trait|enum)\\s+(\\w+)");

    /** Strips block and line comments so commented-out declarations are ignored. */
    private static final Pattern COMMENTS =
            Pattern.compile("/\\*.*?\\*/|//[^\\n]*", Pattern.DOTALL);

    /** {@code [modifiers] [Type] name(params) [throws ...] {} -- a method signature. */
    private static final Pattern METHOD_DECL = Pattern.compile(
            "^(?:(?:public|protected|private|static|final|abstract|synchronized|native|def|"
            + "[\\w.$]+(?:<[^>]*>)?(?:\\[\\])*)\\s+)*"
            + "[\\w$]+\\s*\\([^)]*\\)\\s*(?:throws\\s+[\\w.,\\s]+)?\\{$");

    private static final Pattern TYPE_DECL_LINE = Pattern.compile(
            "^(?:(?:public|protected|private|static|final|abstract)\\s+)*"
            + "(?:class|interface|trait|enum)\\b.*\\{$");

    /** A line starting with one of these is control flow, not a declaration. */
    private static final Set<String> CONTROL_KEYWORDS = new LinkedHashSet<>(Arrays.asList(
            "if", "else", "while", "for", "switch", "try", "catch", "finally",
            "do", "case", "default", "return", "throw", "synchronized"));

    /** How far a breakpoint may slide down before giving up. */
    private static final int MAX_SNAP_DISTANCE = 50;

    private final Path path;
    private final String fileName;
    private final List<String> prefixes;
    private final List<String> lines;

    private SourceRef(Path path, String fileName, List<String> prefixes, List<String> lines) {
        this.path = path;
        this.fileName = fileName;
        this.prefixes = prefixes;
        this.lines = lines;
    }

    public static SourceRef of(String pathText) throws IOException {
        Path path = Paths.get(pathText);
        String fileName = path.getFileName().toString();
        String raw = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        List<String> lines = Arrays.asList(raw.split("\r?\n", -1));
        String stripped = COMMENTS.matcher(raw).replaceAll("");

        Matcher pkgMatch = PACKAGE.matcher(stripped);
        String pkg = pkgMatch.find() ? pkgMatch.group(1) : "";

        // The base name comes first: for a script with no type declaration it is
        // the only candidate, and for the usual one-class-per-file case it is the
        // one that will match.
        Set<String> names = new LinkedHashSet<>();
        names.add(fileName.replaceFirst("\\.(groovy|gvy|gy|gsh)$", ""));
        Matcher typeMatch = TYPE_DECL.matcher(stripped);
        while (typeMatch.find()) {
            names.add(typeMatch.group(1));
        }

        List<String> prefixes = new ArrayList<>(names.size());
        for (String name : names) {
            prefixes.add(pkg.isEmpty() ? name : pkg + "." + name);
        }
        return new SourceRef(path, fileName, Collections.unmodifiableList(prefixes), lines);
    }

    public Path path() {
        return path;
    }

    /** What {@code ReferenceType.sourceName()} reports for classes from this file. */
    public String fileName() {
        return fileName;
    }

    /** e.g. {@code [com.example.FooService]} -- the file's candidate class names. */
    public List<String> prefixes() {
        return prefixes;
    }

    /** Filters for {@code ClassPrepareRequest}: each type and its synthetics. */
    public List<String> classPrepareFilters() {
        List<String> filters = new ArrayList<>(prefixes.size());
        for (String prefix : prefixes) {
            filters.add(prefix + "*");
        }
        return filters;
    }

    /**
     * Whether a loaded class could belong to this file, judged on its name alone.
     *
     * <p>Name first, {@code sourceName()} second: a Grails application loads tens
     * of thousands of classes and asking each for its source name is a JDWP round
     * trip apiece, while {@code allClasses()} brings every name back in one reply.
     */
    public boolean mayOwn(String className) {
        for (String prefix : prefixes) {
            if (className.equals(prefix) || className.startsWith(prefix + "$")) {
                return true;
            }
        }
        return false;
    }

    /** 1-based; empty string past the end of the file. */
    public String lineText(int line) {
        return line >= 1 && line <= lines.size() ? lines.get(line - 1) : "";
    }

    /**
     * The first line at or after {@code line} that could plausibly hold code.
     *
     * <p>This is the fallback for a breakpoint the JVM refuses: a method signature
     * carries no line number entry in Groovy (design doc §7.1, §7.2), and neither
     * do blank lines, lone braces, or annotations, so a user clicking the gutter
     * next to {@code def save() &#123;} would otherwise get a breakpoint that can
     * never bind.
     *
     * <p>The heuristic is deliberately allowed to be loose, because it only runs
     * <em>after</em> an exact bind has failed against every class of this file. A
     * line that really does carry code binds exactly and never reaches here -- so
     * a Groovy DSL call like {@code task foo(type: X) &#123;}, which looks like a
     * declaration, is only ever moved when it genuinely has no bytecode.
     */
    public int firstExecutableLineAtOrAfter(int line) {
        int limit = Math.min(lines.size(), line + MAX_SNAP_DISTANCE);
        for (int candidate = line; candidate <= limit; candidate++) {
            if (!looksNonExecutable(lineText(candidate))) {
                return candidate;
            }
        }
        return line;
    }

    static boolean looksNonExecutable(String rawLine) {
        String text = COMMENTS.matcher(rawLine).replaceAll("").trim();
        if (text.isEmpty()) {
            return true;
        }
        if (isPunctuationOnly(text)) {
            return true;
        }
        if (text.startsWith("@")) {
            return true; // an annotation on a line of its own
        }
        if (text.startsWith("package ") || text.startsWith("import ")) {
            return true;
        }
        if (!text.endsWith("{")) {
            return false;
        }
        String firstWord = text.split("[^\\w$]", 2)[0];
        if (CONTROL_KEYWORDS.contains(firstWord)) {
            return false; // `if (x) {` is executable and binds fine
        }
        return TYPE_DECL_LINE.matcher(text).matches() || METHOD_DECL.matcher(text).matches();
    }

    private static boolean isPunctuationOnly(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ("{}()[];,".indexOf(c) < 0 && !Character.isWhitespace(c)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String toString() {
        return prefixes + " (" + fileName + ")";
    }
}
