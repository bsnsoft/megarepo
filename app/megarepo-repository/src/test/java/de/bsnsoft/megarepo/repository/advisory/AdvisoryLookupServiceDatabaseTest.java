package de.bsnsoft.megarepo.repository.advisory;

import com.github.packageurl.PackageURL;
import de.bsnsoft.megarepo.database.entity.AdvisoryAffectedEntity;
import de.bsnsoft.megarepo.database.entity.AdvisoryEntity;
import de.bsnsoft.megarepo.database.repository.AdvisoryAffectedJpaRepository;
import de.bsnsoft.megarepo.database.repository.AdvisoryJpaRepository;
import de.bsnsoft.megarepo.repository.firewall.identity.ComponentIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The request-path advisory lookup against the real migrated schema.
 *
 * <p>The cases that matter are the ones a mocked repository cannot show: that a
 * vulnerable version matches and the fixed one does not, that the CPE-derived
 * pass finds NVD data without claiming it is an identity match, and that a
 * namespace collision no longer produces a false positive.
 */
class AdvisoryLookupServiceDatabaseTest extends AdvisoryDatabaseTest {

    private static final Instant PUBLISHED = Instant.parse("2021-12-10T00:00:00Z");

    @Autowired private AdvisoryLookupService lookup;
    @Autowired private AdvisoryJpaRepository advisories;
    @Autowired private AdvisoryAffectedJpaRepository affected;

    @BeforeEach
    void reset() {
        affected.deleteAllInBatch();
        advisories.deleteAllInBatch();
    }

    @Test
    @DisplayName("the affected version is reported and the fixed one is clean")
    void versionRangeSeparatesAffectedFromFixed() throws Exception {
        givenAdvisory("GHSA-jfh8-c2jp-5v3q", "GHSA", 10.0, "CRITICAL", "RCE in log4j-core");
        givenPurlRange("GHSA-jfh8-c2jp-5v3q", "maven", "org.apache.logging.log4j", "log4j-core",
                ">=2.0-beta9, <2.15.0", "2.0-beta9", "2.15.0", null);

        assertThat(lookup.findAdvisories(purl("2.14.1")))
                .extracting(AdvisoryFinding::advisoryId)
                .containsExactly("GHSA-jfh8-c2jp-5v3q");

        assertThat(lookup.findAdvisories(purl("2.15.0")))
                .as("the fixed bound is exclusive — the version carrying the fix is not affected")
                .isEmpty();

        assertThat(lookup.findAdvisories(purl("1.2.17")))
                .as("below the introduced bound")
                .isEmpty();
    }

    @Test
    @DisplayName("Maven's own ordering decides, not lexicographic string comparison")
    void rangesUseTheEcosystemVersionScheme() throws Exception {
        givenAdvisory("GHSA-range", "GHSA", 7.5, "HIGH", "affects 2.0 up to 2.10");
        givenPurlRange("GHSA-range", "maven", "org.apache.logging.log4j", "log4j-core",
                ">=2.0, <2.10", "2.0", "2.10", null);

        // "2.9" > "2.10" lexicographically, but 2.9 < 2.10 under Maven ordering.
        assertThat(lookup.findAdvisories(purl("2.9"))).hasSize(1);
        assertThat(lookup.findAdvisories(purl("2.10"))).isEmpty();
    }

    @Test
    @DisplayName("last_affected is inclusive where fixed is exclusive")
    void lastAffectedIsInclusive() throws Exception {
        givenAdvisory("GHSA-last", "GHSA", 7.5, "HIGH", "up to and including 2.14.1");
        givenPurlRange("GHSA-last", "maven", "org.apache.logging.log4j", "log4j-core",
                "<=2.14.1", null, null, "2.14.1");

        assertThat(lookup.findAdvisories(purl("2.14.1"))).hasSize(1);
        assertThat(lookup.findAdvisories(purl("2.14.2"))).isEmpty();
    }

    @Test
    @DisplayName("the namespace is part of the identity, so a same-named package elsewhere is clean")
    void namespaceIsPartOfTheIdentity() throws Exception {
        givenAdvisory("GHSA-util", "GHSA", 9.0, "CRITICAL", "flaw in com.acme:util");
        givenPurlRange("GHSA-util", "maven", "com.acme", "util", "<2.0", null, "2.0", null);

        assertThat(lookup.findAdvisories(new PackageURL("maven", "com.acme", "util", "1.0", null, null)))
                .hasSize(1);
        assertThat(lookup.findAdvisories(new PackageURL("maven", "org.other", "util", "1.0", null, null)))
                .as("the defect the customer reported: two unrelated packages sharing a name")
                .isEmpty();
    }

    @Test
    @DisplayName("an unscoped npm package matches the NULL-namespace row")
    void unscopedPackagesMatchNullNamespace() throws Exception {
        givenAdvisory("GHSA-eventstream", "GHSA", 8.0, "HIGH", "malicious flatmap-stream dependency");
        givenPurlRange("GHSA-eventstream", "npm", null, "event-stream", ">=3.3.6", "3.3.6", null, null);

        assertThat(lookup.findAdvisories(new PackageURL("npm", null, "event-stream", "3.3.6", null, null)))
                .hasSize(1);
    }

    @Test
    @DisplayName("NVD data is found through the CPE-derived pass and labelled HEURISTIC")
    void cpeDerivedRowsAreFoundAndLabelled() throws Exception {
        givenAdvisory("CVE-2021-44228", "NVD", 10.0, "CRITICAL", "Log4Shell");
        givenPurlRange("CVE-2021-44228", CpePurlTranslator.PURL_TYPE, "apache", "log4j",
                "cpe apache:log4j >=2.0, <2.15.0", "2.0", "2.15.0", null);

        // The component is pkg:maven/org.apache.logging.log4j/log4j@2.14.1 — the
        // artifact name happens to equal the CPE product, which is the only
        // bridge that exists between the two naming schemes.
        List<AdvisoryFinding> findings =
                lookup.findAdvisories(new PackageURL("maven", "org.apache.logging.log4j", "log4j", "2.14.1", null, null));

        assertThat(findings).hasSize(1);
        AdvisoryFinding finding = findings.get(0);
        assertThat(finding.confidence()).isEqualTo(MatchConfidence.HEURISTIC);
        assertThat(finding.sources()).containsExactly("NVD");
        assertThat(finding.matches()).singleElement()
                .satisfies(match -> assertThat(match.matchedRange()).isEqualTo("cpe apache:log4j >=2.0, <2.15.0"));
    }

    @Test
    @DisplayName("CPE-derived rows are still version-filtered, using the component's ecosystem")
    void cpeDerivedRowsAreVersionFiltered() throws Exception {
        givenAdvisory("CVE-2021-44228", "NVD", 10.0, "CRITICAL", "Log4Shell");
        givenPurlRange("CVE-2021-44228", CpePurlTranslator.PURL_TYPE, "apache", "log4j",
                "cpe apache:log4j >=2.0, <2.15.0", "2.0", "2.15.0", null);

        assertThat(lookup.findAdvisories(
                        new PackageURL("maven", "org.apache.logging.log4j", "log4j", "2.15.0", null, null)))
                .isEmpty();
    }

    @Test
    @DisplayName("REGRESSION: a CPE product is not matched against a differently named artifact")
    void cpeProductIsNotFoldedOntoSubArtifacts() throws Exception {
        givenAdvisory("CVE-2021-44228", "NVD", 10.0, "CRITICAL", "Log4Shell");
        givenPurlRange("CVE-2021-44228", CpePurlTranslator.PURL_TYPE, "apache", "log4j",
                "cpe apache:log4j >=2.0, <2.15.0", "2.0", "2.15.0", null);

        // The legacy lookup folded log4j-api onto the product log4j and flagged
        // it. The CPE-derived pass only normalises separators, so it does not.
        assertThat(lookup.findAdvisories(
                        new PackageURL("maven", "org.apache.logging.log4j", "log4j-api", "2.14.1", null, null)))
                .isEmpty();
    }

    @Test
    @DisplayName("a dashed artifact still finds its underscored CPE product")
    void separatorNormalisationBridgesTheNamingSchemes() throws Exception {
        givenAdvisory("CVE-2022-42889", "NVD", 9.8, "CRITICAL", "Text4Shell");
        givenPurlRange("CVE-2022-42889", CpePurlTranslator.PURL_TYPE, "apache", "commons_text",
                "cpe apache:commons_text >=1.5, <1.10.0", "1.5", "1.10.0", null);

        assertThat(lookup.findAdvisories(
                        new PackageURL("maven", "org.apache.commons", "commons-text", "1.9", null, null)))
                .extracting(AdvisoryFinding::advisoryId)
                .containsExactly("CVE-2022-42889");
    }

    @Test
    @DisplayName("the CPE-derived pass can be switched off for a purl-native-only answer")
    void cpeDerivedPassIsOptional() {
        givenAdvisory("CVE-2021-44228", "NVD", 10.0, "CRITICAL", "Log4Shell");
        givenPurlRange("CVE-2021-44228", CpePurlTranslator.PURL_TYPE, "apache", "log4j",
                "cpe apache:log4j >=2.0, <2.15.0", "2.0", "2.15.0", null);

        assertThat(lookup.findAdvisories("maven", "org.apache.logging.log4j", "log4j", "2.14.1", false))
                .isEmpty();
        assertThat(lookup.findAdvisories("maven", "org.apache.logging.log4j", "log4j", "2.14.1", true))
                .hasSize(1);
    }

    @Test
    @DisplayName("one vulnerability reported by two feeds is one finding with two labels")
    void duplicateAcrossSourcesIsMergedButKeepsBothLabels() throws Exception {
        // Both feeds publish the same CVE id — NVD as a CPE, OSV as a purl. The
        // primary key collapses them to one row; this pins that the lookup
        // reports the vulnerability once and still names its origin.
        givenAdvisory("CVE-2022-42889", "OSV", 9.8, "CRITICAL", "Text4Shell");
        givenPurlRange("CVE-2022-42889", "maven", "org.apache.commons", "commons-text",
                ">=1.5, <1.10.0", "1.5", "1.10.0", null);
        givenPurlRange("CVE-2022-42889", CpePurlTranslator.PURL_TYPE, "apache", "commons_text",
                "cpe apache:commons_text >=1.5, <1.10.0", "1.5", "1.10.0", null);

        List<AdvisoryFinding> findings = lookup.findAdvisories(
                new PackageURL("maven", "org.apache.commons", "commons-text", "1.9", null, null));

        assertThat(findings).hasSize(1);
        AdvisoryFinding finding = findings.get(0);
        assertThat(finding.matches()).hasSize(2);
        assertThat(finding.matches()).extracting(AdvisoryMatch::confidence)
                .containsExactlyInAnyOrder(MatchConfidence.EXACT, MatchConfidence.HEURISTIC);
        assertThat(finding.confidence())
                .as("one purl-native match is enough to identify the component")
                .isEqualTo(MatchConfidence.EXACT);
    }

    @Test
    @DisplayName("a withdrawn advisory clears the component instead of flagging it forever")
    void withdrawnAdvisoriesAreNotReported() throws Exception {
        AdvisoryEntity advisory = givenAdvisory("GHSA-withdrawn", "GHSA", 9.0, "CRITICAL", "retracted");
        advisory.setWithdrawnAt(Instant.now());
        advisories.saveAndFlush(advisory);
        givenPurlRange("GHSA-withdrawn", "maven", "org.apache.logging.log4j", "log4j-core",
                "<2.15.0", null, "2.15.0", null);

        assertThat(lookup.findAdvisories(purl("2.14.1"))).isEmpty();
    }

    @Test
    @DisplayName("results come back most severe first")
    void findingsAreOrderedBySeverity() throws Exception {
        givenAdvisory("GHSA-high", "GHSA", 7.5, "HIGH", "high");
        givenPurlRange("GHSA-high", "maven", "org.apache.logging.log4j", "log4j-core",
                "<3.0", null, "3.0", null);
        givenAdvisory("GHSA-critical", "GHSA", 10.0, "CRITICAL", "critical");
        givenPurlRange("GHSA-critical", "maven", "org.apache.logging.log4j", "log4j-core",
                "<3.0", null, "3.0", null);

        assertThat(lookup.findAdvisories(purl("2.14.1")))
                .extracting(AdvisoryFinding::advisoryId)
                .containsExactly("GHSA-critical", "GHSA-high");
    }

    @Test
    @DisplayName("a component with no advisories, and one that cannot be named, both answer empty")
    void unmatchedAndUnresolvableComponents() throws Exception {
        givenAdvisory("GHSA-jfh8-c2jp-5v3q", "GHSA", 10.0, "CRITICAL", "RCE in log4j-core");
        givenPurlRange("GHSA-jfh8-c2jp-5v3q", "maven", "org.apache.logging.log4j", "log4j-core",
                "<2.15.0", null, "2.15.0", null);

        assertThat(lookup.findAdvisories(new PackageURL("maven", "com.acme", "unrelated", "1.0", null, null)))
                .isEmpty();
        assertThat(lookup.findAdvisories(ComponentIdentity.Hash.sha256("abc123")))
                .as("a content hash names bytes; no advisory feed indexes those")
                .isEmpty();
        assertThat(lookup.findAdvisories(new ComponentIdentity.Unidentified("raw", null, "blob", "1")))
                .isEmpty();
        assertThat(lookup.findAdvisories((PackageURL) null)).isEmpty();
    }

    @Test
    @DisplayName("without a version, only advisories affecting every version apply")
    void versionlessLookupReturnsOnlyUnboundedRanges() {
        givenAdvisory("GHSA-all", "GHSA", 9.0, "CRITICAL", "every version is affected");
        givenPurlRange("GHSA-all", "maven", "com.acme", "util", "*", null, null, null);
        givenAdvisory("GHSA-bounded", "GHSA", 9.0, "CRITICAL", "only below 2.0");
        givenPurlRange("GHSA-bounded", "maven", "com.acme", "util", "<2.0", null, "2.0", null);

        assertThat(lookup.findAdvisories("maven", "com.acme", "util", null))
                .extracting(AdvisoryFinding::advisoryId)
                .containsExactly("GHSA-all");
    }

    @Test
    @DisplayName("the OSV \"0\" sentinel means unbounded, not the literal version 0")
    void osvBeginningSentinelIsTreatedAsUnbounded() throws Exception {
        givenAdvisory("GHSA-zero", "GHSA", 9.0, "CRITICAL", "affected since the beginning");
        givenPurlRange("GHSA-zero", "maven", "org.apache.logging.log4j", "log4j-core",
                ">=0, <2.15.0", "0", "2.15.0", null);

        assertThat(lookup.findAdvisories(purl("1.2.17"))).hasSize(1);
    }

    private static PackageURL purl(String version) throws Exception {
        return new PackageURL(
                "maven", "org.apache.logging.log4j", "log4j-core", version, null, null);
    }

    private AdvisoryEntity givenAdvisory(
            String id, String source, Double score, String severity, String summary) {
        AdvisoryEntity advisory = new AdvisoryEntity();
        advisory.setId(id);
        advisory.setSource(source);
        advisory.setSeverity(severity);
        advisory.setCvssScore(score);
        advisory.setSummary(summary);
        advisory.setPublished(PUBLISHED);
        advisory.setModified(PUBLISHED);
        return advisories.saveAndFlush(advisory);
    }

    private void givenPurlRange(
            String advisoryId,
            String purlType,
            String namespace,
            String name,
            String versionRange,
            String introduced,
            String fixed,
            String lastAffected) {
        AdvisoryAffectedEntity entity = new AdvisoryAffectedEntity();
        entity.setAdvisoryId(advisoryId);
        entity.setPurlType(purlType);
        entity.setPurlNamespace(namespace);
        entity.setPurlName(name);
        entity.setVersionRange(versionRange);
        entity.setIntroduced(introduced);
        entity.setFixed(fixed);
        entity.setLastAffected(lastAffected);
        affected.saveAndFlush(entity);
    }
}
