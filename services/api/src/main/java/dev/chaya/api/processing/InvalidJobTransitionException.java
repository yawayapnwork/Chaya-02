package dev.chaya.api.processing;

/**
 * A job transition was refused. Deliberately not an IllegalStateException: Spring's repository
 * exception translation rewrites those into data-access exceptions.
 */
public class InvalidJobTransitionException extends RuntimeException {
    public InvalidJobTransitionException(String message) {
        super(message);
    }
}
