package de.bsnsoft.megarepo.repository.advisory.osv;

import com.fasterxml.jackson.databind.JsonNode;
import de.bsnsoft.megarepo.repository.advisory.NormalizedAdvisory;
import de.bsnsoft.megarepo.repository.advisory.NormalizedAffected;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Turns one OSV record into a {@link NormalizedAdvisory}, or into nothing.
 *
 * <p>Stateless and free of I/O; the archive reading lives in {@link OsvAdvisorySource}.
 *
 * <p><b>Nothing here throws on bad input.</b> Every rejection is a counter on
 * {@link OsvSyncStats} and an empty result. That is the whole point: OSV is a feed of
 * community-contributed JSON, and one record with an unpaired range event must cost one
 * record, not the day's sync.
 *
 * <p>Field-length limits from the V12 schema are enforced here rather than left to the
 * database, so a pathological upstream value is a counted skip instead of a constraint
 * violation that aborts the caller's transaction. Version bounds are <em>dropped, never
 * truncated</em> — a truncated version string compares wrong, which is worse than a
 * missing range.
 */
public class OsvRecordParser {

    private static final Logger log = LoggerFactory.getLogger(OsvRecordParser.class);

    /** {@code advisory.id VARCHAR(100)}. */
    static final int MAX_ID = 100;

    /** {@code advisory.severity VARCHAR(20)}. */
    static final int MAX_SEVERITY = 20;

    /** {@code advisory.cvss_vector VARCHAR(200)}. */
    static final int MAX_VECTOR = 200;

    /** {@code advisory_affected.purl_namespace/purl_name VARCHAR(500)}. */
    static final int MAX_PURL_PART = 500;

    /** {@code advisory_affected.introduced/fixed/last_affected VARCHAR(200)}. */
    static final int MAX_BOUND = 200;

    /** {@code advisory_affected.version_range VARCHAR(1000)}. */
    static final int MAX_RANGE = 1000;

    /** How much of {@code details} is kept when a record has no {@code summary}. */
    static final int SUMMARY_FALLBACK_CHARS = 500;

    /**
     * Cap on {@code affected[].versions[]} expanded into rows. Enumerations that long are
     * an upstream accident; a package with 500 individually-listed affected versions is
     * already fully covered for any practical lookup.
     */
    static final int MAX_ENUMERATED_VERSIONS = 500;

    /** OSV's "from the very first release" sentinel in an {@code introduced} event. */
    private static final String INTRODUCED_BEGINNING = "0";

    private final String sourceId;

    public OsvRecordParser(String sourceId) {
        this.sourceId = sourceId;
    }

    /**
     * @param root a parsed OSV record
     * @param stats counters for everything this record contributes or loses
     * @return the normalised advisory, or empty when the record carries nothing MegaRepo
     *     can store
     */
    public Optional<NormalizedAdvisory> parse(JsonNode root, OsvSyncStats stats) {
        if (root == null || !root.isObject()) {
            stats.unusableRecord++;
            return Optional.empty();
        }

        String id = trimToNull(root.path("id").asText(null));
        if (id == null || id.length() > MAX_ID) {
            stats.unusableRecord++;
            return Optional.empty();
        }

        List<NormalizedAffected> affected = parseAffected(root, stats);
        if (affected.isEmpty()) {
            // Either every affected entry belonged to a foreign ecosystem, or the record
            // describes something MegaRepo cannot host at all (an OSS-Fuzz finding, a
            // kernel CVE). Storing an advisory with no package to match is dead weight.
            stats.noAffectedRanges++;
            return Optional.empty();
        }

        Severity severity = parseSeverity(root);

        return Optional.of(new NormalizedAdvisory(
                id,
                sourceId,
                summaryOf(root),
                severity.text(),
                severity.score(),
                severity.vector(),
                parseInstant(root.path("published").asText(null)),
                parseInstant(root.path("modified").asText(null)),
                parseInstant(root.path("withdrawn").asText(null)),
                affected));
    }

    // ---------------------------------------------------------------- summary

    private static String summaryOf(JsonNode root) {
        String summary = trimToNull(root.path("summary").asText(null));
        if (summary != null) {
            return summary;
        }
        // MAL- records frequently carry only `details` (the malware analysis write-up).
        String details = trimToNull(root.path("details").asText(null));
        if (details == null) {
            return null;
        }
        String firstParagraph = details.split("\\R\\R", 2)[0].trim();
        String candidate = firstParagraph.isEmpty() ? details : firstParagraph;
        return candidate.length() <= SUMMARY_FALLBACK_CHARS
                ? candidate
                : candidate.substring(0, SUMMARY_FALLBACK_CHARS).trim() + "…";
    }

    // --------------------------------------------------------------- severity

    /** What could be recovered about how bad an advisory is. Any part may be null. */
    private record Severity(String text, Double score, String vector) {}

    /**
     * OSV splits severity across two places and neither is guaranteed. {@code severity[]}
     * holds CVSS <em>vectors</em> keyed by type; {@code database_specific} holds whatever
     * the contributing database had, which for GHSA-derived records is a textual
     * {@code severity} and occasionally a numeric score.
     *
     * <p>A v3 vector yields a computed score (see {@link Cvss3BaseScore}). A v4 or v2
     * vector is stored but not scored. When no vector scores and no number is published,
     * the score stays null — {@code MAL-} records have none, and a defaulted 0.0 would
     * read to a {@code CVSS_THRESHOLD} rule as "measured, and harmless".
     */
    private static Severity parseSeverity(JsonNode root) {
        String v3Vector = null;
        String otherVector = null;
        Double publishedScore = null;

        JsonNode severities = root.path("severity");
        if (severities.isArray()) {
            for (JsonNode entry : severities) {
                String value = trimToNull(entry.path("score").asText(null));
                if (value == null) {
                    continue;
                }
                if (value.regionMatches(true, 0, "CVSS:3.", 0, 7)) {
                    if (v3Vector == null) {
                        v3Vector = value;
                    }
                } else if (value.regionMatches(true, 0, "CVSS:", 0, 5) || value.contains("/AV:")) {
                    if (otherVector == null) {
                        otherVector = value;
                    }
                } else if (publishedScore == null) {
                    // Some contributors publish a bare number instead of a vector.
                    publishedScore = parseDouble(value);
                }
            }
        }

        String vector = v3Vector != null ? v3Vector : otherVector;
        Double score = Cvss3BaseScore.fromVector(v3Vector);
        if (score == null) {
            score = publishedScore;
        }

        JsonNode dbSpecific = root.path("database_specific");
        if (score == null) {
            score = numericScore(dbSpecific.path("cvss"));
            if (score == null) {
                score = parseDouble(dbSpecific.path("cvss_score").asText(null));
            }
        }
        if (vector == null) {
            vector = trimToNull(dbSpecific.path("cvss").path("vectorString").asText(null));
            if (vector == null) {
                String cvssScoreField = trimToNull(dbSpecific.path("cvss").path("score").asText(null));
                if (cvssScoreField != null && cvssScoreField.startsWith("CVSS:")) {
                    vector = cvssScoreField;
                }
            }
        }
        if (score == null && vector != null) {
            score = Cvss3BaseScore.fromVector(vector);
        }
        if (score != null && (score < 0 || score > 10)) {
            score = null;
        }

        String text = trimToNull(dbSpecific.path("severity").asText(null));
        if (text == null) {
            text = trimToNull(dbSpecific.path("cvss").path("severity").asText(null));
        }
        if (text == null) {
            // Only ever derived from a score we actually have; never from a guess.
            text = Cvss3BaseScore.severityBand(score);
        }
        if (text != null) {
            text = text.toUpperCase(Locale.ROOT);
            if (text.length() > MAX_SEVERITY) {
                text = null;
            }
        }
        if (vector != null && vector.length() > MAX_VECTOR) {
            vector = null;
        }

        return new Severity(text, score, vector);
    }

    private static Double numericScore(JsonNode cvss) {
        if (cvss.isObject()) {
            JsonNode score = cvss.path("score");
            if (score.isNumber()) {
                return score.asDouble();
            }
            return parseDouble(trimToNull(score.asText(null)));
        }
        return null;
    }

    // --------------------------------------------------------------- affected

    private List<NormalizedAffected> parseAffected(JsonNode root, OsvSyncStats stats) {
        List<NormalizedAffected> result = new ArrayList<>();
        JsonNode affectedNodes = root.path("affected");
        if (!affectedNodes.isArray()) {
            return result;
        }

        for (JsonNode affected : affectedNodes) {
            JsonNode pkg = affected.path("package");
            Optional<OsvEcosystem> ecosystem =
                    OsvEcosystem.fromOsvName(pkg.path("ecosystem").asText(null));
            if (ecosystem.isEmpty()) {
                stats.skippedForeignEcosystem++;
                continue;
            }
            Optional<OsvEcosystem.PurlName> purlName =
                    ecosystem.get().splitPackageName(pkg.path("name").asText(null));
            if (purlName.isEmpty()) {
                stats.skippedUnusablePackageName++;
                continue;
            }
            OsvEcosystem.PurlName name = purlName.get();
            if (tooLong(name.namespace(), MAX_PURL_PART) || tooLong(name.name(), MAX_PURL_PART)) {
                stats.skippedUnusablePackageName++;
                continue;
            }

            String publishedRange = rangeExpression(affected);
            int before = result.size();
            boolean sawRange = collectRanges(
                    affected, ecosystem.get(), name, publishedRange, result, stats);

            if (result.size() > before) {
                continue;
            }
            if (collectEnumeratedVersions(
                    affected, ecosystem.get(), name, publishedRange, result, stats)) {
                continue;
            }
            if (sawRange) {
                // Ranges existed but none survived (GIT-only, or every bound unusable).
                // Emitting an unbounded row here would blanket-block a package on the
                // strength of a commit range we cannot evaluate.
                stats.skippedUnusableRanges++;
                continue;
            }
            // No ranges and no versions: OSV's way of saying "every version of this
            // package". This is the shape of nearly every MAL- record, and getting it
            // wrong would make malicious-package blocking silently do nothing.
            addRow(result, ecosystem.get(), name, publishedRange, null, null, null, stats);
        }
        return result;
    }

    /**
     * Walks {@code ranges[].events[]} into intervals.
     *
     * <p>Events are ordered: an {@code introduced} opens an interval, the following
     * {@code fixed} (exclusive) or {@code last_affected} (inclusive) closes it. An
     * interval left open at the end of the range — or cut short by the next
     * {@code introduced} — is still affected, with no known fix.
     *
     * @return whether the entry carried any {@code ranges} at all, usable or not
     */
    private boolean collectRanges(
            JsonNode affected,
            OsvEcosystem ecosystem,
            OsvEcosystem.PurlName name,
            String publishedRange,
            List<NormalizedAffected> out,
            OsvSyncStats stats) {

        JsonNode ranges = affected.path("ranges");
        if (!ranges.isArray() || ranges.isEmpty()) {
            return false;
        }

        boolean sawRange = false;
        for (JsonNode range : ranges) {
            String type = range.path("type").asText("");
            if ("GIT".equalsIgnoreCase(type)) {
                // Commit hashes; no VersionScheme orders them and no purl carries them.
                stats.skippedGitRanges++;
                sawRange = true;
                continue;
            }
            JsonNode events = range.path("events");
            if (!events.isArray() || events.isEmpty()) {
                continue;
            }
            sawRange = true;

            String introduced = null;
            boolean open = false;
            for (JsonNode event : events) {
                if (event.has("introduced")) {
                    if (open) {
                        addRow(out, ecosystem, name, publishedRange, introduced, null, null, stats);
                    }
                    introduced = normaliseIntroduced(event.path("introduced").asText(null));
                    open = true;
                } else if (event.has("fixed")) {
                    addRow(
                            out,
                            ecosystem,
                            name,
                            publishedRange,
                            introduced,
                            trimToNull(event.path("fixed").asText(null)),
                            null,
                            stats);
                    introduced = null;
                    open = false;
                } else if (event.has("last_affected")) {
                    addRow(
                            out,
                            ecosystem,
                            name,
                            publishedRange,
                            introduced,
                            null,
                            trimToNull(event.path("last_affected").asText(null)),
                            stats);
                    introduced = null;
                    open = false;
                }
                // "limit" only occurs on GIT ranges, which never reach here.
            }
            if (open) {
                addRow(out, ecosystem, name, publishedRange, introduced, null, null, stats);
            }
        }
        return sawRange;
    }

    /**
     * Expands {@code affected[].versions[]} into one closed single-version interval each.
     *
     * <p>Only reached when the entry produced no usable range. OSV emits {@code versions}
     * as the enumeration of what the ranges already match, so using both would double
     * every row for no gain.
     *
     * @return whether anything was added
     */
    private boolean collectEnumeratedVersions(
            JsonNode affected,
            OsvEcosystem ecosystem,
            OsvEcosystem.PurlName name,
            String publishedRange,
            List<NormalizedAffected> out,
            OsvSyncStats stats) {

        JsonNode versions = affected.path("versions");
        if (!versions.isArray() || versions.isEmpty()) {
            return false;
        }
        int added = 0;
        for (JsonNode version : versions) {
            if (added >= MAX_ENUMERATED_VERSIONS) {
                stats.truncatedVersionEnumerations++;
                log.debug(
                        "OSV affected entry for {}/{} enumerates more than {} versions — truncated",
                        ecosystem.purlType(),
                        name.name(),
                        MAX_ENUMERATED_VERSIONS);
                break;
            }
            String value = trimToNull(version.asText(null));
            if (value == null) {
                continue;
            }
            int before = out.size();
            addRow(out, ecosystem, name, publishedRange, value, null, value, stats);
            if (out.size() > before) {
                added++;
            }
        }
        return added > 0;
    }

    /** Reads the one genuinely published range expression OSV records carry, if any. */
    private static String rangeExpression(JsonNode affected) {
        String expression = trimToNull(affected
                .path("database_specific")
                .path("last_known_affected_version_range")
                .asText(null));
        return expression == null || expression.length() > MAX_RANGE ? null : expression;
    }

    private static void addRow(
            List<NormalizedAffected> out,
            OsvEcosystem ecosystem,
            OsvEcosystem.PurlName name,
            String publishedRange,
            String introduced,
            String fixed,
            String lastAffected,
            OsvSyncStats stats) {

        if (tooLong(introduced, MAX_BOUND) || tooLong(fixed, MAX_BOUND) || tooLong(lastAffected, MAX_BOUND)) {
            stats.skippedOverlongBounds++;
            return;
        }
        out.add(new NormalizedAffected(
                ecosystem.purlType(),
                name.namespace(),
                name.name(),
                publishedRange,
                introduced,
                fixed,
                lastAffected));
    }

    /** {@code introduced: "0"} is OSV for "since the first release", i.e. no lower bound. */
    private static String normaliseIntroduced(String value) {
        String trimmed = trimToNull(value);
        return INTRODUCED_BEGINNING.equals(trimmed) ? null : trimmed;
    }

    // ----------------------------------------------------------------- shared

    /** RFC 3339, which is what the OSV schema mandates for every timestamp. */
    static Instant parseInstant(String value) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            return null;
        }
        try {
            return Instant.parse(trimmed);
        } catch (DateTimeParseException ignored) {
            // Offsets other than Z, and the occasional missing-Z record.
        }
        try {
            return OffsetDateTime.parse(trimmed).toInstant();
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        try {
            return Instant.parse(trimmed + "Z");
        } catch (DateTimeParseException e) {
            log.debug("Unparseable OSV timestamp: {}", trimmed);
            return null;
        }
    }

    private static Double parseDouble(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Double.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean tooLong(String value, int max) {
        return value != null && value.length() > max;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
