package org.groovydap;

import java.io.OutputStream;
import java.io.PrintStream;

/**
 * Entry point. VSCode starts this jar per debug session and speaks DAP over its
 * stdio:
 *
 * <pre>
 * java --add-modules jdk.jdi -jar dist/groovy-dap.jar
 * </pre>
 *
 * <p>{@code jdk.jdi} is not in the default root module set, so it has to be
 * requested explicitly or {@code com.sun.jdi} will not resolve at run time.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        // stdout is the protocol channel. A stray println anywhere in this process
        // -- ours or a library's -- lands between two DAP messages and desyncs the
        // client, so give the rest of the program stderr and keep the real stdout
        // to ourselves.
        OutputStream protocol = new java.io.FileOutputStream(java.io.FileDescriptor.out);
        System.setOut(new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.err),
                true));

        new DebugSession(System.in, protocol).run();
    }
}
