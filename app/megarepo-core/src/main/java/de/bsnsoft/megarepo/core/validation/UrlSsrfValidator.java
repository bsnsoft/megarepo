package de.bsnsoft.megarepo.core.validation;

import de.bsnsoft.megarepo.core.exception.ValidationException;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * Validates URLs to prevent Server-Side Request Forgery (SSRF) attacks.
 * Rejects URLs that resolve to loopback, private, or link-local addresses.
 */
public final class UrlSsrfValidator {

    private UrlSsrfValidator() {}

    /**
     * Validates that the given URL does not point to an internal/private network address.
     *
     * @param url the URL to validate
     * @throws ValidationException if the URL is malformed or resolves to an internal address
     */
    public static void validateUrlNotInternal(String url) {
        if (url == null || url.isBlank()) {
            throw new ValidationException("URL must not be empty");
        }

        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new ValidationException("Invalid URL: " + url);
        }

        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            throw new ValidationException("Only http and https URLs are allowed, got: " + scheme);
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new ValidationException("URL must contain a valid host: " + url);
        }

        validateHostNotInternal(host);
    }

    /**
     * Validates that the given hostname does not resolve to a loopback, private,
     * or link-local address.
     *
     * @param host the hostname to validate
     * @throws ValidationException if the host resolves to an internal address
     */
    public static void validateHostNotInternal(String host) {
        if (host == null || host.isBlank()) {
            throw new ValidationException("Host must not be empty");
        }
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            for (InetAddress addr : addresses) {
                if (addr.isLoopbackAddress() || addr.isLinkLocalAddress()
                        || addr.isSiteLocalAddress() || addr.isAnyLocalAddress()) {
                    throw new ValidationException(
                            "Host '%s' resolves to a private/internal address. Only public hosts are allowed."
                                    .formatted(host));
                }
            }
        } catch (UnknownHostException e) {
            throw new ValidationException("Cannot resolve host: " + host);
        }
    }
}
