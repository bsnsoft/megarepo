package de.bsnsoft.megarepo.core.exception;

public class MegaRepoException extends RuntimeException {

    public MegaRepoException(String message) {
        super(message);
    }

    public MegaRepoException(String message, Throwable cause) {
        super(message, cause);
    }
}
