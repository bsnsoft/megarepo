package de.bsnsoft.megarepo.repository.advisory;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Collapses several advisories that describe the same vulnerability into one
 * finding, keeping every source and confidence label.
 *
 * <p>Deduplication is the customer's requirement — one Log4Shell in a jar must
 * not read as three problems just because three feeds carry it — but so is
 * provenance: <em>"label findings with source and confidence"</em>. Both are met
 * by merging the <em>advisories</em> and keeping the {@link AdvisoryMatch}es.
 *
 * <p>Which advisory supplies the scalar metadata of the merged finding is
 * decided by a fixed order, never by ingest or query order:
 *
 * <ol>
 *   <li>strongest {@link MatchConfidence} — a purl-native advisory describes the
 *       component better than a CPE-derived one;</li>
 *   <li>highest CVSS score, treating "no score published" as lowest;</li>
 *   <li>lowest advisory id, as a final tie-break.</li>
 * </ol>
 *
 * <p>Grouping is delegated to an {@link AdvisoryAliasResolver}, so widening the
 * merge from "same id" to "aliased ids" is a change of resolver, not of this
 * class.
 *
 * <p>Pure and free of I/O: it operates on values the caller has already loaded.
 */
@Service
public class AdvisoryMergeService {

    /**
     * Orders findings for presentation: most severe first, exact matches ahead
     * of heuristic ones at equal severity, id as the final tie-break so the
     * output is stable.
     */
    private static final Comparator<AdvisoryFinding> PRESENTATION_ORDER =
            Comparator.<AdvisoryFinding, Double>comparing(
                            finding -> finding.cvssScore() == null ? -1.0 : finding.cvssScore(),
                            Comparator.reverseOrder())
                    .thenComparing(AdvisoryFinding::confidence)
                    .thenComparing(AdvisoryFinding::advisoryId);

    /**
     * Orders the advisories inside one merge group; the first is the primary
     * whose metadata the merged finding carries.
     */
    private static final Comparator<AdvisoryFinding> PRIMARY_ORDER =
            Comparator.comparing(AdvisoryFinding::confidence)
                    .thenComparing(
                            finding -> finding.cvssScore() == null ? -1.0 : finding.cvssScore(),
                            Comparator.reverseOrder())
                    .thenComparing(AdvisoryFinding::advisoryId);

    private final AdvisoryAliasResolver aliasResolver;

    public AdvisoryMergeService(AdvisoryAliasResolver aliasResolver) {
        this.aliasResolver = aliasResolver;
    }

    /**
     * Merges per-advisory findings into per-vulnerability findings.
     *
     * @param findings one finding per matched advisory; each is expected to
     *     carry the matches of that advisory only
     * @return merged findings in presentation order, never null
     */
    public List<AdvisoryFinding> merge(List<AdvisoryFinding> findings) {
        if (findings == null || findings.isEmpty()) {
            return List.of();
        }
        if (findings.size() == 1) {
            return List.copyOf(findings);
        }

        List<String> ids = findings.stream().map(AdvisoryFinding::advisoryId).toList();
        Map<String, String> canonicalIds = aliasResolver.canonicalIds(ids);

        Map<String, List<AdvisoryFinding>> groups = new LinkedHashMap<>();
        for (AdvisoryFinding finding : findings) {
            String canonical = canonicalIds.getOrDefault(finding.advisoryId(), finding.advisoryId());
            groups.computeIfAbsent(canonical, key -> new ArrayList<>()).add(finding);
        }

        List<AdvisoryFinding> merged = new ArrayList<>(groups.size());
        for (List<AdvisoryFinding> group : groups.values()) {
            merged.add(group.size() == 1 ? group.get(0) : mergeGroup(group));
        }
        merged.sort(PRESENTATION_ORDER);
        return List.copyOf(merged);
    }

    private static AdvisoryFinding mergeGroup(List<AdvisoryFinding> group) {
        List<AdvisoryFinding> ordered = new ArrayList<>(group);
        ordered.sort(PRIMARY_ORDER);
        AdvisoryFinding primary = ordered.get(0);

        // LinkedHashSet: records give value equality for free, so an advisory
        // reached through both an exact and a CPE-derived range collapses, while
        // genuinely different evidence is kept and stays in primary-first order.
        LinkedHashSet<AdvisoryMatch> matches = new LinkedHashSet<>();
        for (AdvisoryFinding finding : ordered) {
            matches.addAll(finding.matches());
        }

        return new AdvisoryFinding(
                primary.advisoryId(),
                primary.summary(),
                primary.severity(),
                firstNonNullScore(ordered),
                primary.cvssVector(),
                primary.published(),
                primary.modified(),
                List.copyOf(matches));
    }

    /**
     * The primary's score, or the best score any group member published.
     *
     * <p>An advisory without a CVSS score is not evidence that the
     * vulnerability is harmless — GHSA entries frequently predate the CVE that
     * carries the score. Dropping to null just because the primary has none
     * would hide severity a sibling advisory already established.
     */
    private static Double firstNonNullScore(List<AdvisoryFinding> ordered) {
        Double best = null;
        for (AdvisoryFinding finding : ordered) {
            Double score = finding.cvssScore();
            if (score != null && (best == null || score > best)) {
                best = score;
            }
        }
        return best;
    }
}
