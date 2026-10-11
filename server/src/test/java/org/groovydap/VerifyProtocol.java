package org.groovydap;

/**
 * Entry point of the dependency-free test run: {@code ./gradlew verifyProtocol}.
 * Exits non-zero if any check failed, which is what fails the build.
 */
public final class VerifyProtocol {

    private VerifyProtocol() {
    }

    public static void main(String[] args) {
        System.out.println("unit checks");
        UnitChecks.run();
        EvaluateChecks.run();
        WriteChecks.run();
        System.out.println("end-to-end checks (loopback channel, no Grails)");
        EndToEndChecks.run();

        int failed = Check.failures().size();
        System.out.println();
        System.out.println("verifyProtocol: " + Check.passed() + " passed, " + failed + " failed");
        for (String f : Check.failures()) {
            System.out.println("  FAILED " + f);
        }
        System.exit(failed == 0 ? 0 : 1);
    }
}
