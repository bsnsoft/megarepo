package de.bsnsoft.megarepo.repository.firewall.report;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.TreeMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rendered report is the artefact the customer reads, so the properties that
 * make it honest are asserted here rather than left to review: that the counts
 * it prints are the counts it was given, that the caveats appear above the
 * numbers, and that a case with no examples says "None." instead of quietly
 * omitting its section.
 */
class ComparisonReportMarkdownTest {

    @Test
    @DisplayName("the percentage is derived from the counts, not restated")
    void reductionPercentageMatchesTheCounts() {
        // 3 agreed + 6 legacy-only + 1 version-only-cpe = 10 legacy reports,
        // 7 of which purl matching does not reproduce.
        ComparisonSummary summary = summary(3, 6, 2, 1, 0, 2);
        assertThat(summary.legacyFindingsTotal()).isEqualTo(10);
        assertThat(summary.legacyReportsNotReproduced()).isEqualTo(0.7);

        String markdown = ComparisonReportMarkdown.render(report(summary, List.of()));

        assertThat(markdown).contains("Of the 10 reports the current firewall produces, 7 are not"
                + " reproduced by purl matching (70.0 %)");
        assertThat(markdown).contains("2 of those are still surfaced by the replacement");
    }

    @Test
    @DisplayName("caveats are printed before the case sections")
    void notesComeBeforeTheEvidence() {
        String markdown =
                ComparisonReportMarkdown.render(report(summary(1, 1, 1, 0, 0, 0), List.of()));

        assertThat(markdown.indexOf("## Read this before the numbers"))
                .isLessThan(markdown.indexOf("## 1. Only the current CPE matching flags it"));
    }

    @Test
    @DisplayName("an empty case class prints None rather than disappearing")
    void emptySectionsAreStated() {
        String markdown =
                ComparisonReportMarkdown.render(report(summary(0, 0, 0, 0, 0, 0), List.of()));

        assertThat(markdown)
                .contains("## 1. Only the current CPE matching flags it")
                .contains("## 2. Only purl matching flags it")
                .contains("## 3. Same package, different version verdict")
                .contains("## 4. Not identifiable — no purl")
                .contains("None.");
    }

    @Test
    @DisplayName("a worked example carries both sides' evidence and the assessment")
    void samplesRenderBothSides() {
        ComponentComparison sample = new ComponentComparison(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "maven2",
                "org.other",
                "util",
                "1.0",
                "pkg:maven/org.other/util@1.0",
                true,
                ComparisonVerdict.DIVERGENT,
                List.of(new VulnerabilityDelta(
                        "CVE-2024-11001",
                        DeltaKind.CPE_ONLY,
                        "CRITICAL",
                        9.1,
                        "cpe acme:util <2.0",
                        null,
                        "The CPE product 'util' belongs to vendor 'acme'.",
                        true)));

        String markdown =
                ComparisonReportMarkdown.render(report(summary(0, 1, 0, 0, 0, 1), List.of(sample)));

        assertThat(markdown)
                .contains("### CVE-2024-11001 — `org.other:util@1.0`")
                .contains("`cpe acme:util <2.0`")
                .contains("| purl match | — no match |")
                .contains("| Still reported? | yes, by the CPE-derived pass, labelled `HEURISTIC` |")
                .contains("The CPE product 'util' belongs to vendor 'acme'.")
                .contains("Showing 1 of 1.");
    }

    @Test
    @DisplayName("a synthetic run is labelled as such before any number")
    void syntheticRunsAreLabelled() {
        CpePurlComparisonReport syntheticReport = new CpePurlComparisonReport(
                Instant.parse("2026-08-05T10:00:00Z"),
                Duration.ofMillis(1_500),
                ComparisonReportRequest.over("SYNTHETIC fixture"),
                true,
                false,
                summary(0, 0, 0, 0, 0, 0),
                List.of(),
                List.of());

        String markdown = ComparisonReportMarkdown.render(syntheticReport);

        assertThat(markdown).contains("**These numbers are from a synthetic fixture");
        assertThat(markdown.indexOf("synthetic fixture")).isLessThan(markdown.indexOf("## Summary"));
        assertThat(markdown).contains("1.5 s");
    }

    private static CpePurlComparisonReport report(
            ComparisonSummary summary, List<ComponentComparison> samples) {
        return new CpePurlComparisonReport(
                Instant.parse("2026-08-05T10:00:00Z"),
                Duration.ofMillis(250),
                ComparisonReportRequest.defaults(),
                false,
                false,
                summary,
                samples,
                List.of("A caveat that must be visible before the evidence."));
    }

    private static ComparisonSummary summary(
            long agreed,
            long cpeOnly,
            long purlOnly,
            long versionOnlyCpe,
            long versionOnlyPurl,
            long stillHeuristic) {
        return new ComparisonSummary(
                10,
                1,
                2,
                3,
                4,
                1,
                1,
                1,
                agreed,
                cpeOnly,
                purlOnly,
                versionOnlyCpe,
                versionOnlyPurl,
                stillHeuristic,
                new TreeMap<>(java.util.Map.of("raw", 1L)),
                new AdvisoryStoreState(100, 200, 300, 400, 350, 50));
    }
}
