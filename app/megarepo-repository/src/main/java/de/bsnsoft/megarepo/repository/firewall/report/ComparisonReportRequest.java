package de.bsnsoft.megarepo.repository.firewall.report;

import java.util.List;
import java.util.UUID;

/**
 * What to compare and how much of it to write down.
 *
 * <p>Every field is clamped in the compact constructor rather than validated,
 * because this is a diagnostic that an operator triggers by hand: a nonsensical
 * page size should produce a usable report, not a 400.
 *
 * @param repositoryIds restrict the scan to these repositories; empty means all
 * @param pageSize components loaded and compared per batch. Bounded because the
 *     report runs inside the live instance: each batch is one short read-only
 *     transaction, so a long scan never holds a transaction open across the
 *     whole run and never blocks autovacuum on {@code components}.
 * @param maxComponents hard stop on the number of components scanned. A report
 *     that runs away over a million-component instance is worse than a truncated
 *     one, and {@link CpePurlComparisonReport#truncated()} says which happened.
 * @param maxSamplesPerKind how many worked examples to keep per
 *     {@link DeltaKind}. Counts are always complete; only the examples are
 *     capped.
 * @param includeAgreementSamples whether to keep examples for
 *     {@link DeltaKind#AGREED} too. Off by default — agreement is the boring
 *     majority and its count is the interesting part.
 * @param datasetLabel free text naming the data this ran over, carried into the
 *     rendered report. The word "synthetic" anywhere in it flips the report into
 *     synthetic mode, which prints a banner instead of letting a fixture run be
 *     mistaken for a measurement over real data.
 */
public record ComparisonReportRequest(
        List<UUID> repositoryIds,
        int pageSize,
        int maxComponents,
        int maxSamplesPerKind,
        boolean includeAgreementSamples,
        String datasetLabel) {

    public static final int DEFAULT_PAGE_SIZE = 500;
    public static final int MAX_PAGE_SIZE = 2_000;
    public static final int DEFAULT_MAX_COMPONENTS = 100_000;
    public static final int DEFAULT_MAX_SAMPLES_PER_KIND = 25;
    public static final int MAX_SAMPLES_PER_KIND_LIMIT = 500;

    public ComparisonReportRequest {
        repositoryIds = repositoryIds == null ? List.of() : List.copyOf(repositoryIds);
        pageSize = clamp(pageSize, 1, MAX_PAGE_SIZE, DEFAULT_PAGE_SIZE);
        maxComponents = clamp(maxComponents, 1, Integer.MAX_VALUE, DEFAULT_MAX_COMPONENTS);
        maxSamplesPerKind =
                clamp(maxSamplesPerKind, 0, MAX_SAMPLES_PER_KIND_LIMIT, DEFAULT_MAX_SAMPLES_PER_KIND);
        datasetLabel = datasetLabel == null || datasetLabel.isBlank()
                ? "live repository data of this MegaRepo instance"
                : datasetLabel.trim();
    }

    /** Everything, with the defaults. */
    public static ComparisonReportRequest defaults() {
        return new ComparisonReportRequest(
                List.of(),
                DEFAULT_PAGE_SIZE,
                DEFAULT_MAX_COMPONENTS,
                DEFAULT_MAX_SAMPLES_PER_KIND,
                false,
                null);
    }

    /** The same, over a named dataset. */
    public static ComparisonReportRequest over(String datasetLabel) {
        return new ComparisonReportRequest(
                List.of(),
                DEFAULT_PAGE_SIZE,
                DEFAULT_MAX_COMPONENTS,
                DEFAULT_MAX_SAMPLES_PER_KIND,
                false,
                datasetLabel);
    }

    /**
     * {@code true} when the dataset label declares itself synthetic. Matched on
     * the label rather than a separate flag so the two can never contradict each
     * other in the rendered output.
     */
    public boolean synthetic() {
        return datasetLabel.toLowerCase(java.util.Locale.ROOT).contains("synthetic");
    }

    private static int clamp(int value, int min, int max, int fallback) {
        if (value <= 0) {
            return fallback;
        }
        return Math.min(Math.max(value, min), max);
    }
}
