package de.bsnsoft.megarepo.repository.firewall.report;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * The Phase 1 deliverable: current CPE matching measured against purl matching
 * over real repository data.
 *
 * <p>Serialised as JSON by the admin endpoint and rendered to Markdown by
 * {@link ComparisonReportMarkdown}. Both come from this one value, so the
 * machine-readable and the human-readable form cannot drift apart.
 *
 * @param generatedAt when the scan ran
 * @param duration how long it took, so the operator can judge what a rerun costs
 * @param request the parameters this ran with, echoed back — a report whose
 *     scope cannot be reconstructed is not reproducible
 * @param synthetic {@code true} when the run was over a fixture rather than real
 *     data; the renderer states this before any number
 * @param truncated {@code true} when {@link ComparisonReportRequest#maxComponents()}
 *     stopped the scan before the last component
 * @param summary the aggregate counts
 * @param samples worked examples, capped per {@link DeltaKind}
 * @param notes caveats generated from the data, not boilerplate
 */
public record CpePurlComparisonReport(
        Instant generatedAt,
        Duration duration,
        ComparisonReportRequest request,
        boolean synthetic,
        boolean truncated,
        ComparisonSummary summary,
        List<ComponentComparison> samples,
        List<String> notes) {

    public CpePurlComparisonReport {
        samples = samples == null ? List.of() : List.copyOf(samples);
        notes = notes == null ? List.of() : List.copyOf(notes);
    }

    /** The samples that contain at least one delta of the given kind. */
    public List<ComponentComparison> samplesOf(DeltaKind kind) {
        return samples.stream().filter(sample -> sample.has(kind)).toList();
    }
}
