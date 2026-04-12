package de.bsnsoft.megarepo.core.exception;

public class ValidationException extends MegaRepoException {

    public ValidationException(String message) {
        super(message);
    }

    public ValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
