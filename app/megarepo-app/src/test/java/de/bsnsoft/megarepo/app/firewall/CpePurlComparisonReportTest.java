package de.bsnsoft.megarepo.app.firewall;

import de.bsnsoft.megarepo.database.repository.AdvisoryAffectedJpaRepository;
import de.bsnsoft.megarepo.database.repository.AdvisoryJpaRepository;
import de.bsnsoft.megarepo.database.repository.ComponentJpaRepository;
import de.bsnsoft.megarepo.database.repository.CveAffectedProductJpaRepository;
import de.bsnsoft.megarepo.database.repository.CveEntryJpaRepository;
import de.bsnsoft.megarepo.database.repository.RepositoryJpaRepository;
import de.bsnsoft.megarepo.repository.firewall.report.ComparisonReportMarkdown;
import de.bsnsoft.megarepo.repository.firewall.report.ComparisonReportRequest;
import de.bsnsoft.megarepo.repository.firewall.report.ComparisonSummary;
import de.bsnsoft.megarepo.repository.firewall.report.ComparisonVerdict;
import de.bsnsoft.megarepo.repository.firewall.report.ComponentComparison;
import de.bsnsoft.megarepo.repository.firewall.report.CpePurlComparisonReport;
import de.bsnsoft.megarepo.repository.firewall.report.CpePurlComparisonService;
import de.bsnsoft.megarepo.repository.firewall.report.DeltaKind;
import de.bsnsoft.megarepo.repository.firewall.report.VulnerabilityDelta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The comparison report over the {@link SyntheticComparisonFixture}.
 *
 * <p>Two things are proven here. First, that every case class the report
 * distinguishes is recognised from real rows in a real schema — the counts are
 * asserted exactly, so a classification that silently moves a case from
 * "false positive" to "agreement" fails the build. Second, that running the
 * report changes nothing: every table it reads is counted before and after.
 *
 * <p>The rendered Markdown is written to {@code build/reports/firewall/} so the
 * output can be looked at without running a server. It is not committed —
 * the report that matters is the one produced over the customer's data.
 */
class CpePurlComparisonReportTest extends ComparisonReportDatabaseTest {

    @Autowired private CpePurlComparisonService service;
    @Autowired private RepositoryJpaRepository repositories;
    @Autowired private ComponentJpaRepository components;
    @Autowired private CveEntryJpaRepository cveEntries;
    @Autowired private CveAffectedProductJpaRepository cveAffected;
    @Autowired private AdvisoryJpaRepository advisories;
    @Autowired private AdvisoryAffectedJpaRepository advisoryAffected;

    private SyntheticComparisonFixture fixture;

    @BeforeEach
    void loadFixture() {
        fixture = new SyntheticComparisonFixture(
                repositories, components, cveEntries, cveAffected, advisories, advisoryAffected);
        fixture.clear();
        fixture.load();
    }

    @Test
    @DisplayName("every case class is counted exactly once, in the right bucket")
    void classifiesEveryCase() {
        ComparisonSummary summary = report().summary();

        assertThat(summary.componentsScanned()).isEqualTo(SyntheticComparisonFixture.COMPONENT_COUNT);

        assertThat(summary.findingsAgreed())
                .as("com.acme:util and log4j-core — both methods report the same vulnerability")
                .isEqualTo(2);
        assertThat(summary.findingsCpeOnly())
                .as("org.other:util, log4j-api, commons-text (CVE side), raw struts2")
                .isEqualTo(4);
        assertThat(summary.findingsPurlOnly())
                .as("commons-text (GHSA side) and orphan-lib")
                .isEqualTo(2);
        assertThat(summary.findingsVersionOnlyCpe())
                .as("commons-compress 1.21-sp1 and pillow 9.0.1.post1")
                .isEqualTo(2);
        assertThat(summary.findingsVersionOnlyPurl())
                .as("widget 1.0-alpha10 — the current firewall lets this through")
                .isEqualTo(1);

        assertThat(summary.legacyFindingsTotal()).isEqualTo(8);
        assertThat(summary.purlFindingsTotal()).isEqualTo(5);

        assertThat(summary.componentsBothClean()).isEqualTo(1);
        assertThat(summary.componentsInAgreement()).isEqualTo(2);
        assertThat(summary.componentsUnidentified()).isEqualTo(1);
        assertThat(summary.componentsDivergent()).isEqualTo(7);
        assertThat(summary.componentsBothClean()
                        + summary.componentsInAgreement()
                        + summary.componentsUnidentified()
                        + summary.componentsDivergent())
                .as("the four verdicts partition the scan")
                .isEqualTo(summary.componentsScanned());

        assertThat(summary.componentsWithCpeOnly()).isEqualTo(4);
        assertThat(summary.componentsWithPurlOnly()).isEqualTo(2);
        assertThat(summary.componentsWithVersionDisagreement()).isEqualTo(3);
        assertThat(summary.unidentifiedByFormat()).containsExactly(java.util.Map.entry("raw", 1L));
    }

    @Test
    @DisplayName("the namespace collision is reported as a false positive, with the CPE product named")
    void namespaceCollisionIsEvidenced() {
        VulnerabilityDelta delta = deltaFor("util", "1.0", "org.other", DeltaKind.CPE_ONLY);

        assertThat(delta.vulnerabilityId()).isEqualTo("CVE-2024-11001");
        assertThat(delta.cpeEvidence()).isEqualTo("cpe acme:util <2.0");
        assertThat(delta.purlEvidence()).isNull();
        assertThat(delta.assessment())
                .contains("vendor 'acme'")
                .contains("org.other")
                .contains("never reads the namespace");
        assertThat(delta.alsoReportedAsHeuristic())
                .as("the CPE-derived pass still matches the product name, so this is a downgrade")
                .isTrue();
    }

    @Test
    @DisplayName("the log4j-api folding is reported as a false positive and is genuinely dropped")
    void subArtifactFoldingIsEvidenced() {
        VulnerabilityDelta delta =
                deltaFor("log4j-api", "2.14.1", "org.apache.logging.log4j", DeltaKind.CPE_ONLY);

        assertThat(delta.vulnerabilityId()).isEqualTo("CVE-2021-44228");
        assertThat(delta.cpeEvidence()).isEqualTo("cpe apache:log4j >=2.0, <2.15.0");
        assertThat(delta.assessment())
                .contains("folded the artifact name 'log4j-api'")
                .contains("onto the coarser CPE product 'log4j'");
        assertThat(delta.alsoReportedAsHeuristic())
                .as("candidate generation for the new pass does not truncate, so nothing matches")
                .isFalse();
    }

    @Test
    @DisplayName("a Maven service pack of the fixed release is a version disagreement, not a package one")
    void mavenServicePackIsAVersionDisagreement() {
        VulnerabilityDelta delta = deltaFor(
                "commons-compress", "1.21-sp1", "org.apache.commons", DeltaKind.VERSION_ONLY_CPE);

        assertThat(delta.vulnerabilityId()).isEqualTo("CVE-2024-11006");
        assertThat(delta.cpeEvidence()).isEqualTo("cpe apache:commons_compress <1.21");
        assertThat(delta.purlEvidence())
                .isEqualTo("pkg:maven/org.apache.commons/commons-compress@1.21-sp1 affected <1.21");
        assertThat(delta.assessment())
                .contains("identify the same package")
                .contains("maven");
    }

    @Test
    @DisplayName("a PEP 440 post release of the fix is a version disagreement too")
    void pep440PostReleaseIsAVersionDisagreement() {
        VulnerabilityDelta delta =
                deltaFor("pillow", "9.0.1.post1", null, DeltaKind.VERSION_ONLY_CPE);

        assertThat(delta.vulnerabilityId()).isEqualTo("CVE-2024-11007");
        assertThat(delta.assessment()).contains("pep440");
    }

    @Test
    @DisplayName("purl matching also flags what the legacy ordering waves through")
    void purlOrderingCatchesWhatTheLegacyComparatorMisses() {
        VulnerabilityDelta delta =
                deltaFor("widget", "1.0-alpha10", "com.acme", DeltaKind.VERSION_ONLY_PURL);

        assertThat(delta.vulnerabilityId()).isEqualTo("CVE-2024-11008");
        assertThat(delta.assessment())
                .contains("the legacy path does not report this vulnerability");
    }

    @Test
    @DisplayName("one vulnerability under two ids lands on both sides — Phase 1 has no alias table")
    void unalisedDuplicateShowsUpOnBothSides() {
        ComponentComparison text = comparisonFor("commons-text", "1.9", "org.apache.commons");

        assertThat(text.deltas()).hasSize(2);
        assertThat(text.deltas()).extracting(VulnerabilityDelta::kind)
                .containsExactlyInAnyOrder(DeltaKind.CPE_ONLY, DeltaKind.PURL_ONLY);
        assertThat(text.deltas()).extracting(VulnerabilityDelta::vulnerabilityId)
                .containsExactlyInAnyOrder("CVE-2022-42889", "GHSA-599f-7c49-w659");

        VulnerabilityDelta cpeSide = text.deltas().stream()
                .filter(delta -> delta.kind() == DeltaKind.CPE_ONLY)
                .findFirst()
                .orElseThrow();
        assertThat(cpeSide.assessment())
                .as("the report must not call this a false positive when purl matching knows the package")
                .contains("does know this package");
    }

    @Test
    @DisplayName("a raw component is reported as not identifiable, not as a false positive removed")
    void rawComponentIsReportedAsUnassessable() {
        ComponentComparison raw = comparisonFor("struts2", "1", "vendor-drops");

        assertThat(raw.verdict()).isEqualTo(ComparisonVerdict.UNIDENTIFIED);
        assertThat(raw.identified()).isFalse();
        assertThat(raw.identityKey()).startsWith("unidentified:raw/");
        assertThat(raw.deltas()).singleElement()
                .satisfies(delta -> {
                    assertThat(delta.kind()).isEqualTo(DeltaKind.CPE_ONLY);
                    assertThat(delta.assessment())
                            .contains("no package identity")
                            .contains("loss of coverage");
                });
    }

    @Test
    @DisplayName("the report writes nothing")
    void reportIsReadOnly() {
        long[] before = rowCounts();

        service.run(ComparisonReportRequest.over(SyntheticComparisonFixture.LABEL));
        service.run(new ComparisonReportRequest(
                List.of(fixture.repositoryId()), 2, 0, 100, true, SyntheticComparisonFixture.LABEL));

        assertThat(rowCounts())
                .as("components, both advisory stores and the CVE mirror are untouched")
                .containsExactly(before);
    }

    @Test
    @DisplayName("paging does not change the result")
    void resultIsIndependentOfPageSize() {
        ComparisonSummary wholePage = summaryWithPageSize(1_000);
        ComparisonSummary tinyPages = summaryWithPageSize(2);

        assertThat(tinyPages.componentsScanned()).isEqualTo(wholePage.componentsScanned());
        assertThat(tinyPages.findingsCpeOnly()).isEqualTo(wholePage.findingsCpeOnly());
        assertThat(tinyPages.findingsPurlOnly()).isEqualTo(wholePage.findingsPurlOnly());
        assertThat(tinyPages.findingsAgreed()).isEqualTo(wholePage.findingsAgreed());
    }

    @Test
    @DisplayName("the component limit truncates and says so")
    void maxComponentsTruncates() {
        CpePurlComparisonReport limited = service.run(new ComparisonReportRequest(
                List.of(), 4, 5, 25, false, SyntheticComparisonFixture.LABEL));

        assertThat(limited.summary().componentsScanned()).isEqualTo(5);
        assertThat(limited.truncated()).isTrue();
        assertThat(limited.notes()).anyMatch(note -> note.contains("stopped at the configured limit"));
    }

    @Test
    @DisplayName("an empty advisory store is called out instead of read as a clean bill of health")
    void emptyStoreIsCalledOut() {
        fixture.clear();

        CpePurlComparisonReport empty =
                service.run(ComparisonReportRequest.over(SyntheticComparisonFixture.LABEL));

        assertThat(empty.summary().storeState().isComparable()).isFalse();
        assertThat(empty.notes()).anyMatch(note -> note.contains("do not support any conclusion"));
        assertThat(ComparisonReportMarkdown.render(empty))
                .contains("One side has no data. Nothing below is a valid comparison.");
    }

    @Test
    @DisplayName("the rendered report declares the data synthetic before any number")
    void markdownDeclaresSyntheticData() throws IOException {
        CpePurlComparisonReport report = service.run(new ComparisonReportRequest(
                List.of(), 500, 0, 100, true, SyntheticComparisonFixture.LABEL));
        String markdown = ComparisonReportMarkdown.render(report);

        assertThat(report.synthetic()).isTrue();
        assertThat(markdown)
                .contains("**These numbers are from a synthetic fixture, not from real repository data.**");
        assertThat(markdown.indexOf("synthetic fixture"))
                .as("the banner comes before the summary")
                .isLessThan(markdown.indexOf("## Summary"));
        assertThat(markdown)
                .contains("CVE-2024-11001")
                .contains("cpe acme:util <2.0")
                .contains("pkg:maven/org.apache.commons/commons-text@1.9");

        Path out = Path.of("build", "reports", "firewall");
        Files.createDirectories(out);
        Files.writeString(out.resolve("cpe-purl-comparison-synthetic.md"), markdown);
        System.out.println(markdown);
    }

    // -------------------------------------------------------------- helpers

    private CpePurlComparisonReport report() {
        return service.run(new ComparisonReportRequest(
                List.of(), 500, 0, 100, true, SyntheticComparisonFixture.LABEL));
    }

    private ComparisonSummary summaryWithPageSize(int pageSize) {
        return service.run(new ComparisonReportRequest(
                        List.of(), pageSize, 0, 100, true, SyntheticComparisonFixture.LABEL))
                .summary();
    }

    private ComponentComparison comparisonFor(String name, String version, String namespace) {
        Optional<ComponentComparison> match = report().samples().stream()
                .filter(sample -> name.equals(sample.name()))
                .filter(sample -> version.equals(sample.version()))
                .filter(sample -> namespace == null
                        ? sample.namespace() == null
                        : namespace.equals(sample.namespace()))
                .findFirst();
        assertThat(match).as("sample for %s:%s@%s", namespace, name, version).isPresent();
        return match.orElseThrow();
    }

    private VulnerabilityDelta deltaFor(
            String name, String version, String namespace, DeltaKind kind) {
        Optional<VulnerabilityDelta> match =
                comparisonFor(name, version, namespace).deltas().stream()
                        .filter(delta -> delta.kind() == kind)
                        .findFirst();
        assertThat(match).as("%s delta for %s@%s", kind, name, version).isPresent();
        return match.orElseThrow();
    }

    private long[] rowCounts() {
        return new long[] {
            components.count(),
            cveEntries.count(),
            cveAffected.count(),
            advisories.count(),
            advisoryAffected.count(),
            repositories.count()
        };
    }
}
