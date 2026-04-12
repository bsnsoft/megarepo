package de.bsnsoft.megarepo.app.migration;

import de.bsnsoft.megarepo.core.exception.ValidationException;

public class NexusMigrationException extends ValidationException {

    public NexusMigrationException(String message) {
        super(message);
    }

    public NexusMigrationException(String message, Throwable cause) {
        super(message, cause);
    }
}
