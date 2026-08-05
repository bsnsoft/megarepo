package de.bsnsoft.megarepo.repository.firewall.report;

import java.util.SortedMap;
import java.util.TreeMap;

/**
 * The aggregate numbers.
 *
 * <p>Two units are counted and they are kept apart on purpose. <b>Component</b>
 * counts answer "how many artifacts are affected by the change" and the four
 * verdict counts partition {@link #componentsScanned()}. <b>Finding</b> counts
 * answer "how many vulnerability reports change" and the five kind counts
 * partition the total number of vulnerabilities observed. Mixing the two is the
 * standard way to make a security-tooling comparison say whatever the author
 * wants, so neither number is presented without its unit.
 *
 * <p>The {@code componentsWith*} counts are <em>not</em> a partition — a
 * component can be a false positive for one CVE and a miss for another. They are
 * labelled as overlapping wherever they are rendered.
 */
public record ComparisonSummary(
        long componentsScanned,
        long componentsUnidentified,
        long componentsBothClean,
        long componentsInAgreement,
        long componentsDivergent,
        long componentsWithCpeOnly,
        long componentsWithPurlOnly,
        long componentsWithVersionDisagreement,
        long findingsAgreed,
        long findingsCpeOnly,
        long findingsPurlOnly,
        long findingsVersionOnlyCpe,
        long findingsVersionOnlyPurl,
        long findingsCpeOnlyStillReportedAsHeuristic,
        SortedMap<String, Long> unidentifiedByFormat,
        AdvisoryStoreState storeState) {

    public ComparisonSummary {
        unidentifiedByFormat =
                unidentifiedByFormat == null ? new TreeMap<>() : new TreeMap<>(unidentifiedByFormat);
    }

    /** Every vulnerability the legacy CPE path reported, across all components. */
    public long legacyFindingsTotal() {
        return findingsAgreed + findingsCpeOnly + findingsVersionOnlyCpe;
    }

    /** Every vulnerability purl matching reported, across all components. */
    public long purlFindingsTotal() {
        return findingsAgreed + findingsPurlOnly + findingsVersionOnlyPurl;
    }

    /**
     * Share of the legacy path's reports that purl matching does not reproduce,
     * as a fraction of {@link #legacyFindingsTotal()}.
     *
     * <p>This is the "false positive reduction" number, and it is an upper
     * bound, not a measurement of truth: it counts reports that disappear, and a
     * disappearing report is only a false positive if the component really was
     * not affected. The per-case assessments are what carry that argument;
     * this number alone does not.
     *
     * @return {@code 0.0} when the legacy path reported nothing
     */
    public double legacyReportsNotReproduced() {
        long total = legacyFindingsTotal();
        return total == 0 ? 0.0 : (double) (findingsCpeOnly + findingsVersionOnlyCpe) / total;
    }
}
