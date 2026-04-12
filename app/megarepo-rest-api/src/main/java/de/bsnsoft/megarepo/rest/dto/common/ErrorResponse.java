package de.bsnsoft.megarepo.rest.dto.common;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record ErrorResponse(int status, String error, String message, Instant timestamp, Map<String, List<String>> fieldErrors) {

    public ErrorResponse(int status, String error, String message, Instant timestamp) {
        this(status, error, message, timestamp, null);
    }
}
