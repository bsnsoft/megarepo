package de.bsnsoft.megarepo.core.format;

import java.io.InputStream;
import java.util.Map;

public sealed interface FormatResponse {

    record ContentResponse(
            InputStream content,
            String contentType,
            long contentLength,
            Map<String, String> headers,
            Map<String, String> checksums
    ) implements FormatResponse {
    }

    record NotFoundResponse(String message) implements FormatResponse {
    }

    record RedirectResponse(String location) implements FormatResponse {
    }

    record ErrorResponse(int statusCode, String message) implements FormatResponse {
    }

    /**
     * Error response with custom headers and optional JSON body.
     * Used by Docker token auth to return {@code Www-Authenticate} headers on 401.
     */
    record ErrorResponseWithHeaders(int statusCode, String message, Map<String, String> headers, String body)
            implements FormatResponse {
    }

    record CreatedResponse(String path, Map<String, String> headers) implements FormatResponse {
    }
}
