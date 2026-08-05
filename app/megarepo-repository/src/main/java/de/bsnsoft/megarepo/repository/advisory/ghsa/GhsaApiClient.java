package de.bsnsoft.megarepo.repository.advisory.ghsa;

import de.bsnsoft.megarepo.repository.proxy.RemoteHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTTP access to GitHub's <em>list global security advisories</em> endpoint.
 *
 * <p>REST rather than the GraphQL {@code securityAdvisories} query, for three reasons:
 * GraphQL <em>requires</em> a token even to read the public advisory database, so a
 * token-less deployment could not even fall back to it; the REST payload is already
 * purl-shaped ({@code package.ecosystem} + {@code package.name} +
 * {@code vulnerable_version_range}), so there is no query document to keep in sync with a
 * schema; and its {@code Link}-header cursor plus {@code sort=updated&direction=asc}
 * gives a resumable, monotonically advancing import for free.
 *
 * <p>Every request goes through {@link RemoteHttpClient#upstreamHttpClient()} and
 * therefore through {@code megarepo.outbound-proxy.*}, like every other outbound call in
 * MegaRepo. Nothing here retries or sleeps: pacing is {@link GhsaAdvisorySource}'s
 * decision, and a background sync that blocks for an hour waiting out a rate limit is a
 * background sync nobody can stop.
 *
 * <p>This class never logs the token and never puts it into an exception message; the
 * response body is scrubbed of it before being quoted, in case a proxy ever echoes the
 * request back.
 */
@Component
public class GhsaApiClient {

    private static final Logger log = LoggerFactory.getLogger(GhsaApiClient.class);

    /** {@code <https://api.github.com/advisories?…&after=Y3Vyc29y…>; rel="next"} */
    private static final Pattern NEXT_LINK =
            Pattern.compile("<([^>]+)>\\s*;\\s*rel\\s*=\\s*\"?next\"?", Pattern.CASE_INSENSITIVE);

    private static final Pattern AFTER_PARAM = Pattern.compile("[?&]after=([^&]+)");

    /** Shorter values are not credentials and must not be substituted — see {@code scrub}. */
    private static final int MIN_SCRUBBABLE_TOKEN = 8;

    private final Supplier<HttpClient> httpClient;
    private final GhsaProperties properties;
    private final String userAgent;

    public GhsaApiClient(
            RemoteHttpClient remoteHttpClient,
            GhsaProperties properties,
            @Value("${megarepo.proxy.user-agent:MegaRepo/1.0}") String userAgent) {
        // Resolved per request, so a proxy change made in the UI takes effect without a
        // restart — see RemoteHttpClient#applyRuntimeConfig.
        this(remoteHttpClient::upstreamHttpClient, properties, userAgent);
    }

    /** Visible for tests and for use without a Spring context. */
    GhsaApiClient(Supplier<HttpClient> httpClient, GhsaProperties properties, String userAgent) {
        this.httpClient = httpClient;
        this.properties = properties;
        this.userAgent = userAgent;
    }

    /**
     * One raw page of advisories.
     *
     * @param statusCode HTTP status
     * @param body response body, empty string when there was none
     * @param nextAfter GitHub's pagination token for the following page, null on the last
     * @param rateLimitRemaining {@code X-RateLimit-Remaining}, null when not published
     * @param retryAfterSeconds {@code Retry-After}, null when not published
     */
    record Page(
            int statusCode,
            String body,
            String nextAfter,
            Long rateLimitRemaining,
            Long retryAfterSeconds) {

        boolean ok() {
            return statusCode >= 200 && statusCode < 300;
        }

        /**
         * Whether this response means "you are out of budget for now". GitHub answers
         * primary limits with 403 plus {@code X-RateLimit-Remaining: 0}, secondary limits
         * with 403 or 429 plus {@code Retry-After}; both also say so in the body. A 403
         * that shows none of these is a rejected token, not a rate limit.
         */
        boolean rateLimited() {
            if (statusCode != 403 && statusCode != 429) {
                return false;
            }
            if (retryAfterSeconds != null) {
                return true;
            }
            if (rateLimitRemaining != null && rateLimitRemaining <= 0) {
                return true;
            }
            return statusCode == 429 || body.toLowerCase(Locale.ROOT).contains("rate limit");
        }
    }

    /**
     * Fetches one page.
     *
     * @param after GitHub pagination token, null to start at the beginning of the window
     * @param since only advisories updated at or after this instant, null for all
     */
    Page fetchPage(String after, Instant since) throws IOException, InterruptedException {
        URI uri = buildUri(after, since);
        log.debug("Fetching GHSA page: {}", uri);

        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(properties.requestTimeout())
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", userAgent)
                .GET();
        if (properties.hasToken()) {
            request.header("Authorization", "Bearer " + properties.token());
        }

        HttpResponse<String> response =
                httpClient.get().send(request.build(), HttpResponse.BodyHandlers.ofString());

        String body = response.body() == null ? "" : scrub(response.body());
        return new Page(
                response.statusCode(),
                body,
                nextAfter(response.headers().firstValue("Link").orElse(null)),
                header(response, "X-RateLimit-Remaining"),
                header(response, "Retry-After"));
    }

    URI buildUri(String after, Instant since) {
        StringBuilder url = new StringBuilder(properties.baseUrl());
        url.append(properties.baseUrl().contains("?") ? '&' : '?');
        url.append("per_page=").append(properties.pageSize());
        // Ascending by update time is what makes the import resumable: the cursor can be
        // dropped at any point and replaced by "everything updated since the newest entry
        // we have seen", without a gap.
        url.append("&sort=updated&direction=asc");
        if (properties.type() != null) {
            url.append("&type=").append(encode(properties.type()));
        }
        if (since != null) {
            url.append("&updated=").append(encode(">=" + since));
        }
        if (after != null && !after.isBlank()) {
            url.append("&after=").append(encode(after));
        }
        return URI.create(url.toString());
    }

    /** Extracts the {@code after} token of the {@code rel="next"} link, if any. */
    static String nextAfter(String linkHeader) {
        if (linkHeader == null || linkHeader.isBlank()) {
            return null;
        }
        Matcher next = NEXT_LINK.matcher(linkHeader);
        if (!next.find()) {
            return null;
        }
        Matcher after = AFTER_PARAM.matcher(next.group(1));
        if (!after.find()) {
            return null;
        }
        String token = URLDecoder.decode(after.group(1), StandardCharsets.UTF_8);
        return token.isBlank() ? null : token;
    }

    private static Long header(HttpResponse<String> response, String name) {
        return response.headers()
                .firstValue(name)
                .map(String::trim)
                .filter(value -> value.matches("-?\\d+"))
                .map(Long::valueOf)
                .orElse(null);
    }

    /**
     * Removes the token from text that may end up in a log line or an exception message.
     * GitHub does not echo credentials, but a misconfigured forward proxy might.
     *
     * <p>Only credential-length values are substituted. A short string — a misconfigured
     * one-character "token", say — occurs all over an advisory payload, and replacing it
     * would corrupt the very JSON we are about to parse. GitHub's tokens are 40 characters
     * and longer, so the guard costs nothing real.
     */
    private String scrub(String text) {
        if (text == null || !properties.hasToken() || properties.token().length() < MIN_SCRUBBABLE_TOKEN) {
            return text;
        }
        return text.replace(properties.token(), "***");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
