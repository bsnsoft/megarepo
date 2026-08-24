package de.bsnsoft.megarepo.repository.firewall.facts;

import de.bsnsoft.megarepo.repository.firewall.facts.ComponentFactsSource.ComponentFactsException;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * The one way a {@link ComponentFactsSource} is allowed to talk to an upstream
 * registry.
 *
 * <p>It exists for two reasons, and both are load-bearing.
 *
 * <p><b>Egress.</b> The production implementation goes through
 * {@code RemoteHttpClient}, which is the single place {@code
 * megarepo.outbound-proxy.*} is applied — proxy authentication, the
 * non-proxy-host bypass, and a proxy change made in the UI taking effect without
 * a restart. A source that built its own {@code HttpClient} would be a second
 * egress path an operator only discovers on a network with no direct egress,
 * where it does not fail loudly but simply never resolves anything.
 *
 * <p><b>Tests.</b> Four format modules have to be tested against recorded
 * metadata documents and none of them may reach a live registry. An interface
 * with one method is a stub a test writes in five lines, and the "a lookup never
 * fetches" assertion is a stub that fails the test when it is called at all.
 *
 * <p>Implementations are called from the facts resolver's background pool only.
 */
public interface ComponentFactsHttpClient {

    /**
     * Fetches one metadata document.
     *
     * @param url absolute URL; never built from user-controlled input without the
     *     caller encoding it first
     * @param headers extra request headers, may be empty
     * @return the response, including a non-2xx status — deciding what a 404
     *     means is the source's job, because "no such version" and "no such
     *     package" are different answers in different ecosystems
     * @throws ComponentFactsException when the request could not be completed at
     *     all: a timeout, a connection failure, an oversized body. Retryable by
     *     definition — the resolver counts the attempt
     */
    Response get(String url, Map<String, String> headers) throws ComponentFactsException;

    /**
     * One upstream answer.
     *
     * @param statusCode HTTP status
     * @param body decoded body, empty string when there was none
     * @param lastModified the {@code Last-Modified} header, parsed. Present for
     *     the sources whose ecosystem publishes no release timestamp in the
     *     document itself — a Maven POM says nothing about when it was
     *     published, but the file's upload time to the repository is exactly
     *     that
     */
    record Response(int statusCode, String body, Optional<Instant> lastModified) {

        public Response {
            body = body == null ? "" : body;
            lastModified = lastModified == null ? Optional.empty() : lastModified;
        }

        /** Convenience for tests and for sources that never need the header. */
        public static Response of(int statusCode, String body) {
            return new Response(statusCode, body, Optional.empty());
        }

        public boolean isSuccess() {
            return statusCode >= 200 && statusCode < 300;
        }

        public boolean isNotFound() {
            return statusCode == 404 || statusCode == 410;
        }
    }
}
