package org.groovydap;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** The whole test framework: named checks, a pass/fail count, no dependencies. */
final class Check {

    interface Body {
        void run() throws Exception;
    }

    private static int passed;
    private static final List<String> failures = new ArrayList<>();

    private Check() {
    }

    static void test(String name, Body body) {
        try {
            body.run();
            passed++;
            System.out.println("  ok   " + name);
        } catch (Throwable t) {
            failures.add(name + ": " + t);
            System.out.println("  FAIL " + name + ": " + t);
        }
    }

    static void that(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    static void eq(Object expected, Object actual, String what) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(what + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }

    static int passed() {
        return passed;
    }

    static List<String> failures() {
        return failures;
    }
}
