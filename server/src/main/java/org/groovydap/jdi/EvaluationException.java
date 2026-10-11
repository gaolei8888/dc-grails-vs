package org.groovydap.jdi;

/** An expression the debugger cannot answer; the message is what the user reads. */
public final class EvaluationException extends RuntimeException {

    public EvaluationException(String message) {
        super(message);
    }
}
