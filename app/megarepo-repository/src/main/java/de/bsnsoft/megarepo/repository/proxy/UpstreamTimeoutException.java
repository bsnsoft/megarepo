package de.bsnsoft.megarepo.repository.proxy;

import java.io.IOException;

/**
 * Thrown when an upstream proxy fetch times out after all retry attempts are exhausted.
 */
public class UpstreamTimeoutException extends IOException {

    public UpstreamTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
