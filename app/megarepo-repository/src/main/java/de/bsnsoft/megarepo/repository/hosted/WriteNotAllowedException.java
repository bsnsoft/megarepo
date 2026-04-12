package de.bsnsoft.megarepo.repository.hosted;

import de.bsnsoft.megarepo.core.exception.MegaRepoException;

public class WriteNotAllowedException extends MegaRepoException {

    public WriteNotAllowedException(String message) {
        super(message);
    }
}
