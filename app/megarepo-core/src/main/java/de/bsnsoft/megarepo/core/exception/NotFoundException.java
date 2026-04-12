package de.bsnsoft.megarepo.core.exception;

public class NotFoundException extends MegaRepoException {

    public NotFoundException(String message) {
        super(message);
    }

    public NotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
