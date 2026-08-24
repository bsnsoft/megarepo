package de.bsnsoft.megarepo.repository.advisory.ghsa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.bsnsoft.megarepo.repository.advisory.NormalizedAdvisory;
import de.bsnsoft.megarepo.repository.advisory.NormalizedAffected;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Turns one page of GitHub's advisories JSON into {@link NormalizedAdvisory} records.
 *
 * <p>Robustness rule of the whole class: a page that is not a JSON array is a protocol
 * failure and propagates, but a single unusable entry inside a good page is skipped and
 * counted. GHSA has ~20 000 advisories curated by many hands; one of them being odd must
 * not cost the other 19 999.
 *
 * <p>Field mapping notes:
 * <ul>
 *   <li>The id is the {@code ghsa_id}, not the CVE. A vulnerability known to both NVD and
 *       GHSA deliberately yields two rows — collapsing them is the merge step's job.</li>
 *   <li>{@code severity} is passed through as published, only upper-cased. GHSA's
 *       vocabulary is CRITICAL / HIGH / MODERATE / LOW; note that its MODERATE is NVD's
 *       MEDIUM. Translating vocabularies here would be policy, and the contract asks for
 *       the severity "as published".</li>
 *   <li>{@code cvssScore} stays null when GitHub publishes none (malware advisories,
 *       unreviewed entries) — 0.0 would be indistinguishable from a genuine 0.0.</li>
 *   <li>An advisory whose affected packages all fall outside MegaRepo's four ecosystems
 *       is dropped entirely: with no {@code advisory_affected} row it could never match a
 *       component, and the firewall's lookup table stays free of Go and Rust noise.</li>
 * </ul>
 */
class GhsaAdvisoryParser {

    private static final Logger log = LoggerFactory.getLogger(GhsaAdvisoryParser.class);

    /** Column widths from V12 — values that cannot be stored are dropped, not truncated. */
    private static final int MAX_ID = 100;
    private static final int MAX_SEVERITY = 20;
    private static final int MAX_VECTOR = 200;
    private static final int MAX_PURL_NAMESPACE = 500;
    private static final int MAX_PURL_NAME = 500;
    private static final int MAX_VERSION_RANGE = 1000;
    private static final int MAX_VERSION = 200;

    private final ObjectMapper objectMapper;
    private final String sourceId;

    GhsaAdvisoryParser(ObjectMapper objectMapper, String sourceId) {
        this.objectMapper = objectMapper;
        this.sourceId = sourceId;
    }

    /** Counters for one sync run, logged as a summary so silent data loss stays visible. */
    static final class Stats {
        int advisories;
        int skippedMalformed;
        int skippedForeignEcosystem;
        int skippedUnusablePackage;
        int skippedNothingAffected;

        @Override
        public String toString() {
            return "%d advisories, skipped: %d malformed, %d outside our ecosystems, "
                            .formatted(advisories, skippedMalformed, skippedForeignEcosystem)
                    + "%d unusable package names, %d without affected packages"
                            .formatted(skippedUnusablePackage, skippedNothingAffected);
        }
    }

    /**
     * @param body the raw response body, expected to be a JSON array of advisories
     * @throws IOException when the body is not parseable JSON or not an array — that is
     *     upstream answering with something unusable, not a bad single record
     */
    List<NormalizedAdvisory> parsePage(String body, Stats stats) throws IOException {
        JsonNode root = objectMapper.readTree(body);
        if (!root.isArray()) {
            throw new IOException("Expected a JSON array of advisories, got " + root.getNodeType());
        }
        List<NormalizedAdvisory> advisories = new ArrayList<>();
        for (JsonNode node : root) {
            try {
                parseAdvisory(node, stats).ifPresent(advisories::add);
            } catch (RuntimeException e) {
                stats.skippedMalformed++;
                log.debug("Skipping unparseable GHSA entry: {}", e.toString());
            }
        }
        stats.advisories += advisories.size();
        return advisories;
    }

    private Optional<NormalizedAdvisory> parseAdvisory(JsonNode node, Stats stats) {
        String id = fit(text(node, "ghsa_id"), MAX_ID);
        if (id == null) {
            stats.skippedMalformed++;
            return Optional.empty();
        }

        List<NormalizedAffected> affected = parseAffected(node.path("vulnerabilities"), stats);
        if (affected.isEmpty()) {
            stats.skippedNothingAffected++;
            return Optional.empty();
        }

        JsonNode cvss = resolveCvss(node);
        Double score = null;
        JsonNode scoreNode = cvss.path("score");
        if (scoreNode.isNumber()) {
            score = scoreNode.doubleValue();
        }

        String severity = text(node, "severity");
        return Optional.of(new NormalizedAdvisory(
                id,
                sourceId,
                text(node, "summary"),
                fit(severity == null ? null : severity.toUpperCase(Locale.ROOT), MAX_SEVERITY),
                score,
                fit(text(cvss, "vector_string"), MAX_VECTOR),
                instant(node, "published_at"),
                instant(node, "updated_at"),
                instant(node, "withdrawn_at"),
                affected));
    }

    /**
     * GitHub publishes the primary score under {@code cvss} and, since the CVSS 4.0
     * rollout, the per-version breakdown under {@code cvss_severities}. Older entries
     * carry only the former, some newer ones only the latter.
     */
    private static JsonNode resolveCvss(JsonNode node) {
        JsonNode cvss = node.path("cvss");
        if (cvss.path("score").isNumber()) {
            return cvss;
        }
        JsonNode severities = node.path("cvss_severities");
        for (String key : new String[] {"cvss_v3", "cvss_v4"}) {
            JsonNode candidate = severities.path(key);
            if (candidate.path("score").isNumber()) {
                return candidate;
            }
        }
        return cvss;
    }

    private List<NormalizedAffected> parseAffected(JsonNode vulnerabilities, Stats stats) {
        if (!vulnerabilities.isArray()) {
            return List.of();
        }
        List<NormalizedAffected> affected = new ArrayList<>();
        for (JsonNode vulnerability : vulnerabilities) {
            JsonNode pkg = vulnerability.path("package");
            String ecosystem = text(pkg, "ecosystem");
            String name = text(pkg, "name");

            Optional<GhsaPackages.Coordinates> coordinates = GhsaPackages.map(ecosystem, name);
            if (coordinates.isEmpty()) {
                if (GhsaPackages.supports(ecosystem)) {
                    // One of our ecosystems, so it is the name we could not use — most
                    // often a Maven entry without the groupId.
                    stats.skippedUnusablePackage++;
                } else {
                    stats.skippedForeignEcosystem++;
                }
                continue;
            }
            GhsaPackages.Coordinates c = coordinates.get();
            if (tooLong(c.namespace(), MAX_PURL_NAMESPACE) || tooLong(c.name(), MAX_PURL_NAME)) {
                stats.skippedUnusablePackage++;
                continue;
            }

            String rawRange = text(vulnerability, "vulnerable_version_range");
            String firstPatched = firstPatchedVersion(vulnerability);
            GhsaRanges.Bounds bounds = GhsaRanges.parse(rawRange, firstPatched);

            affected.add(new NormalizedAffected(
                    c.purlType(),
                    c.namespace(),
                    c.name(),
                    fit(rawRange, MAX_VERSION_RANGE),
                    fit(bounds.introduced(), MAX_VERSION),
                    fit(bounds.fixed(), MAX_VERSION),
                    fit(bounds.lastAffected(), MAX_VERSION)));
        }
        return affected;
    }

    /**
     * {@code first_patched_version} is a plain string on the global advisories endpoint
     * and an object with an {@code identifier} on the repository-advisory and GraphQL
     * shapes. Both are accepted so a shape change upstream cannot silently cost us every
     * fix version.
     */
    private static String firstPatchedVersion(JsonNode vulnerability) {
        JsonNode node = vulnerability.path("first_patched_version");
        if (node.isTextual()) {
            return trimToNull(node.asText());
        }
        if (node.isObject()) {
            return trimToNull(node.path("identifier").asText(null));
        }
        return null;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? trimToNull(value.asText()) : null;
    }

    private static Instant instant(JsonNode node, String field) {
        String raw = text(node, field);
        if (raw == null) {
            return null;
        }
        try {
            return Instant.parse(raw);
        } catch (RuntimeException e) {
            log.debug("Unparseable GHSA timestamp in {}: {}", field, raw);
            return null;
        }
    }

    private static String fit(String value, int max) {
        return tooLong(value, max) ? null : value;
    }

    private static boolean tooLong(String value, int max) {
        return value != null && value.length() > max;
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String trimmed = s.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
