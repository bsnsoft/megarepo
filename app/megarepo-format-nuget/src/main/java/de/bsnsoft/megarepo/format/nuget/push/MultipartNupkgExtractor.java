package de.bsnsoft.megarepo.format.nuget.push;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Extracts the package bytes from a {@code dotnet nuget push} request body.
 *
 * <p>The NuGet client sends {@code PUT multipart/form-data} with a single
 * file part. Servlet-container multipart parsing ({@code request.getParts()})
 * is unreliable for non-POST methods across containers, so this is a small,
 * deterministic boundary parser for exactly that shape. Non-multipart bodies
 * (e.g. {@code curl --upload-file}) are passed through unchanged.
 */
@Component
public class MultipartNupkgExtractor {

    private static final byte[] CRLF_CRLF = {'\r', '\n', '\r', '\n'};

    /**
     * @param contentType the request {@code Content-Type} header (may be null)
     * @param body        the full request body
     * @return the bytes of the first multipart part, or the body itself when
     *         the request is not multipart; empty when a multipart body is
     *         malformed or contains no part
     */
    public Optional<byte[]> extract(String contentType, byte[] body) {
        if (body == null || body.length == 0) {
            return Optional.empty();
        }
        String boundary = boundaryOf(contentType);
        if (boundary == null) {
            return Optional.of(body);
        }

        byte[] delimiter = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        int partStart = indexOf(body, delimiter, 0);
        if (partStart < 0) {
            return Optional.empty();
        }
        // Skip past the delimiter line (delimiter + CRLF)
        int headersStart = partStart + delimiter.length;
        // Tolerate transport quirks: skip optional CR/LF after the delimiter
        while (headersStart < body.length && (body[headersStart] == '\r' || body[headersStart] == '\n')) {
            headersStart++;
            if (headersStart >= 2 && body[headersStart - 2] == '\r' && body[headersStart - 1] == '\n') {
                break;
            }
        }

        int contentStart = indexOf(body, CRLF_CRLF, partStart + delimiter.length);
        if (contentStart < 0) {
            return Optional.empty();
        }
        contentStart += CRLF_CRLF.length;

        // Content ends at CRLF + next delimiter
        byte[] closing = ("\r\n--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        int contentEnd = indexOf(body, closing, contentStart);
        if (contentEnd < 0) {
            return Optional.empty();
        }

        byte[] content = new byte[contentEnd - contentStart];
        System.arraycopy(body, contentStart, content, 0, content.length);
        return content.length > 0 ? Optional.of(content) : Optional.empty();
    }

    private static String boundaryOf(String contentType) {
        if (contentType == null) {
            return null;
        }
        String lower = contentType.toLowerCase();
        if (!lower.startsWith("multipart/")) {
            return null;
        }
        for (String param : contentType.split(";")) {
            String trimmed = param.trim();
            if (trimmed.toLowerCase().startsWith("boundary=")) {
                String value = trimmed.substring("boundary=".length()).trim();
                if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
                    value = value.substring(1, value.length() - 1);
                }
                return value.isEmpty() ? null : value;
            }
        }
        return null;
    }

    private static int indexOf(byte[] haystack, byte[] needle, int from) {
        outer:
        for (int i = Math.max(from, 0); i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
