package de.bsnsoft.megarepo.core.exception;

public class ConflictException extends MegaRepoException {

    public ConflictException(String message) {
        super(message);
    }

    public ConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
