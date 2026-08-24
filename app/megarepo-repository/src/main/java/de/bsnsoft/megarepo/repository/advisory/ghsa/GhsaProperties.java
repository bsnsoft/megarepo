package de.bsnsoft.megarepo.repository.advisory.ghsa;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Configuration of the GitHub Advisory (GHSA) source.
 *
 * <p>The token is optional on purpose. MegaRepo must start, and the other advisory
 * sources must sync, on a deployment that has no GitHub credentials at all — so an
 * absent token disables this one source (visible in {@code advisory_sync_state}) instead
 * of failing the context or burning the anonymous rate limit on every run. See
 * {@link GhsaAdvisorySource} for what "disabled" looks like from the outside.
 *
 * <p>All values bind from environment variables through Spring Boot's relaxed binding:
 * <pre>
 *   MEGAREPO_FIREWALL_GHSA_TOKEN=ghp_xxx
 *   MEGAREPO_FIREWALL_GHSA_PAGESPERSYNC=10
 * </pre>
 *
 * <p>The token is a secret: it is never logged, never put into an exception message and
 * never written to {@code advisory_sync_state}. {@code toString()} is overridden because
 * a record's generated one would print every component, and Spring logs bound properties
 * on binding failures.
 *
 * @param enabled operator kill switch; the source also disables itself without a token
 * @param token GitHub personal access token (no scopes required — the advisory database
 *     is public; a token only raises the rate limit from 60/h to 5000/h)
 * @param baseUrl the advisories endpoint; overridable for GitHub Enterprise and tests
 * @param pageSize advisories per HTTP request, clamped to GitHub's maximum of 100
 * @param pagesPerSync how many pages one {@link GhsaAdvisorySource#sync(String)} call
 *     fetches before handing control back with a resumable cursor
 * @param type GitHub's advisory type filter — {@code reviewed} (curated, the useful set),
 *     {@code malware} or {@code unreviewed}
 * @param requestTimeout per-request read timeout
 * @param rateLimitReserve stop paging once GitHub reports this many requests left in the
 *     window, leaving budget for the rest of the deployment
 */
@ConfigurationProperties(prefix = "megarepo.firewall.ghsa")
public record GhsaProperties(
        @DefaultValue("true") boolean enabled,
        String token,
        @DefaultValue(GhsaProperties.DEFAULT_BASE_URL) String baseUrl,
        @DefaultValue("100") int pageSize,
        @DefaultValue("5") int pagesPerSync,
        @DefaultValue("reviewed") String type,
        @DefaultValue("30s") Duration requestTimeout,
        @DefaultValue("0") int rateLimitReserve) {

    public static final String DEFAULT_BASE_URL = "https://api.github.com/advisories";

    /** GitHub's hard maximum for {@code per_page} on this endpoint. */
    public static final int MAX_PAGE_SIZE = 100;

    public GhsaProperties {
        baseUrl = (baseUrl == null || baseUrl.isBlank()) ? DEFAULT_BASE_URL : baseUrl.trim();
        pageSize = Math.clamp(pageSize, 1, MAX_PAGE_SIZE);
        pagesPerSync = Math.max(1, pagesPerSync);
        requestTimeout = requestTimeout == null ? Duration.ofSeconds(30) : requestTimeout;
        rateLimitReserve = Math.max(0, rateLimitReserve);
        token = (token == null || token.isBlank()) ? null : token.trim();
        type = (type == null || type.isBlank()) ? null : type.trim();
    }

    /** Defaults with no token — the shape a deployment that never configured GHSA gets. */
    public static GhsaProperties defaults() {
        return new GhsaProperties(
                true, null, DEFAULT_BASE_URL, 100, 5, "reviewed", Duration.ofSeconds(30), 0);
    }

    /** Whether a usable token is configured. */
    public boolean hasToken() {
        return token != null;
    }

    @Override
    public String toString() {
        return "GhsaProperties[enabled=%s, token=%s, baseUrl=%s, pageSize=%d, pagesPerSync=%d, "
                        .formatted(enabled, hasToken() ? "<set>" : "<unset>", baseUrl, pageSize, pagesPerSync)
                + "type=%s, requestTimeout=%s, rateLimitReserve=%d]"
                        .formatted(type, requestTimeout, rateLimitReserve);
    }
}
