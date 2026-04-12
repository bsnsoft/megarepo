package de.bsnsoft.megarepo.core.exception;

public class AccessDeniedException extends MegaRepoException {

    public AccessDeniedException(String message) {
        super(message);
    }

    public AccessDeniedException(String message, Throwable cause) {
        super(message, cause);
    }
}
