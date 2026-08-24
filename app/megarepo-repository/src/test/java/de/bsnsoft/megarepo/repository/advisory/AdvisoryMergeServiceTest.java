package de.bsnsoft.megarepo.repository.advisory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deduplication of advisories that describe the same vulnerability.
 *
 * <p>Two resolvers are exercised: the production {@link IdentityAliasResolver},
 * which is all the frozen Phase 1 ingest contract can support, and a stub that
 * knows real cross-id aliases. The stub is not padding — it is how the merge
 * logic itself is verified, so that adding an {@code advisory_alias} table later
 * is a change of resolver rather than of this algorithm.
 */
class AdvisoryMergeServiceTest {

    private static final Instant PUBLISHED = Instant.parse("2021-12-10T00:00:00Z");

    private final AdvisoryMergeService identityMerge =
            new AdvisoryMergeService(new IdentityAliasResolver());

    @Test
    @DisplayName("nothing to merge is returned unchanged")
    void emptyAndSingletonAreUntouched() {
        assertThat(identityMerge.merge(List.of())).isEmpty();
        assertThat(identityMerge.merge(null)).isEmpty();

        AdvisoryFinding only = finding("CVE-1", 7.5, match("CVE-1", "NVD", MatchConfidence.HEURISTIC));
        assertThat(identityMerge.merge(List.of(only))).containsExactly(only);
    }

    @Test
    @DisplayName("the same id from two feeds becomes one finding carrying both sources")
    void sameIdIsMergedAndKeepsBothSources() {
        List<AdvisoryFinding> merged = identityMerge.merge(List.of(
                finding("CVE-2021-44228", 10.0, match("CVE-2021-44228", "OSV", MatchConfidence.EXACT)),
                finding("cve-2021-44228", 10.0, match("cve-2021-44228", "NVD", MatchConfidence.HEURISTIC))));

        assertThat(merged).hasSize(1);
        AdvisoryFinding finding = merged.get(0);
        assertThat(finding.sources()).containsExactlyInAnyOrder("OSV", "NVD");
        assertThat(finding.matches()).hasSize(2);
        assertThat(finding.isMerged()).isTrue();
    }

    @Test
    @DisplayName("the purl-native advisory supplies the metadata, not whichever synced last")
    void exactMatchWinsTheMetadata() {
        AdvisoryFinding cpeDerived = new AdvisoryFinding(
                "CVE-2021-44228", "vague NVD description", "CRITICAL", 10.0, null, PUBLISHED, PUBLISHED,
                List.of(match("CVE-2021-44228", "NVD", MatchConfidence.HEURISTIC)));
        AdvisoryFinding purlNative = new AdvisoryFinding(
                "cve-2021-44228", "RCE in log4j-core", "CRITICAL", 10.0, "CVSS:3.1/AV:N", PUBLISHED, PUBLISHED,
                List.of(match("cve-2021-44228", "OSV", MatchConfidence.EXACT)));

        // Both orders must give the same answer — that is the point.
        for (List<AdvisoryFinding> input : List.of(
                List.of(cpeDerived, purlNative), List.of(purlNative, cpeDerived))) {
            AdvisoryFinding merged = identityMerge.merge(input).get(0);

            assertThat(merged.summary()).isEqualTo("RCE in log4j-core");
            assertThat(merged.cvssVector()).isEqualTo("CVSS:3.1/AV:N");
            assertThat(merged.advisoryId()).isEqualTo("cve-2021-44228");
            assertThat(merged.confidence()).isEqualTo(MatchConfidence.EXACT);
        }
    }

    @Test
    @DisplayName("one exact match makes the merged finding exact")
    void confidenceIsTheStrongestOfTheGroup() {
        AdvisoryFinding merged = identityMerge.merge(List.of(
                        finding("CVE-1", 5.0, match("CVE-1", "NVD", MatchConfidence.HEURISTIC)),
                        finding("cve-1", 5.0, match("cve-1", "GHSA", MatchConfidence.EXACT))))
                .get(0);

        assertThat(merged.confidence()).isEqualTo(MatchConfidence.EXACT);
    }

    @Test
    @DisplayName("a score from any contributing advisory survives, even if the primary has none")
    void scoreIsTakenFromWhoeverPublishedOne() {
        AdvisoryFinding unscoredButExact = new AdvisoryFinding(
                "GHSA-x", "malicious package", "CRITICAL", null, null, PUBLISHED, PUBLISHED,
                List.of(match("GHSA-x", "GHSA", MatchConfidence.EXACT)));
        AdvisoryFinding scoredButHeuristic = new AdvisoryFinding(
                "ghsa-x", "same thing via NVD", "CRITICAL", 9.8, null, PUBLISHED, PUBLISHED,
                List.of(match("ghsa-x", "NVD", MatchConfidence.HEURISTIC)));

        AdvisoryFinding merged = identityMerge.merge(List.of(unscoredButExact, scoredButHeuristic)).get(0);

        assertThat(merged.advisoryId()).isEqualTo("GHSA-x");
        assertThat(merged.cvssScore())
                .as("a missing score is not evidence of harmlessness")
                .isEqualTo(9.8);
    }

    @Test
    @DisplayName("different vulnerabilities are never merged")
    void distinctAdvisoriesStaySeparate() {
        List<AdvisoryFinding> merged = identityMerge.merge(List.of(
                finding("CVE-2021-44228", 10.0, match("CVE-2021-44228", "OSV", MatchConfidence.EXACT)),
                finding("CVE-2021-45046", 9.0, match("CVE-2021-45046", "OSV", MatchConfidence.EXACT))));

        assertThat(merged).hasSize(2);
    }

    @Test
    @DisplayName("findings come back most severe first, exact ahead of heuristic at equal severity")
    void resultsArePresentedInSeverityOrder() {
        List<AdvisoryFinding> merged = identityMerge.merge(List.of(
                finding("CVE-B", 7.5, match("CVE-B", "OSV", MatchConfidence.EXACT)),
                finding("CVE-C", null, match("CVE-C", "OSV", MatchConfidence.EXACT)),
                finding("CVE-A", 9.8, match("CVE-A", "NVD", MatchConfidence.HEURISTIC)),
                finding("CVE-D", 7.5, match("CVE-D", "NVD", MatchConfidence.HEURISTIC))));

        assertThat(merged).extracting(AdvisoryFinding::advisoryId)
                .containsExactly("CVE-A", "CVE-B", "CVE-D", "CVE-C");
    }

    @Test
    @DisplayName("the same advisory reached through two ranges is not reported twice")
    void duplicateEvidenceCollapses() {
        AdvisoryMatch identical = match("CVE-1", "NVD", MatchConfidence.HEURISTIC);
        List<AdvisoryFinding> merged = identityMerge.merge(List.of(
                new AdvisoryFinding("CVE-1", null, null, 5.0, null, null, null, List.of(identical)),
                new AdvisoryFinding("cve-1", null, null, 5.0, null, null, null, List.of(identical))));

        assertThat(merged.get(0).matches()).hasSize(1);
    }

    @Test
    @DisplayName("with a resolver that knows aliases, a GHSA and its CVE become one finding")
    void aliasResolverMergesAcrossIdSpaces() {
        // What the merge does once NormalizedAdvisory carries `aliases` and an
        // advisory_alias table backs the resolver. The algorithm is complete
        // today; only the data to feed it is missing.
        AdvisoryMergeService merge = new AdvisoryMergeService(
                new StubAliasResolver(Map.of("GHSA-jfh8-c2jp-5v3q", "CVE-2021-44228")));

        List<AdvisoryFinding> merged = merge.merge(List.of(
                finding("CVE-2021-44228", 10.0, match("CVE-2021-44228", "NVD", MatchConfidence.HEURISTIC)),
                finding("GHSA-jfh8-c2jp-5v3q", 10.0,
                        match("GHSA-jfh8-c2jp-5v3q", "GHSA", MatchConfidence.EXACT))));

        assertThat(merged).hasSize(1);
        AdvisoryFinding finding = merged.get(0);
        assertThat(finding.advisoryIds())
                .containsExactlyInAnyOrder("CVE-2021-44228", "GHSA-jfh8-c2jp-5v3q");
        assertThat(finding.sources()).containsExactlyInAnyOrder("NVD", "GHSA");
        assertThat(finding.advisoryId())
                .as("the purl-native advisory becomes the primary")
                .isEqualTo("GHSA-jfh8-c2jp-5v3q");
        assertThat(finding.confidence()).isEqualTo(MatchConfidence.EXACT);
    }

    @Test
    @DisplayName("the production resolver deliberately does not merge across id spaces")
    void identityResolverLeavesCrossIdAliasesAlone() {
        List<AdvisoryFinding> merged = identityMerge.merge(List.of(
                finding("CVE-2021-44228", 10.0, match("CVE-2021-44228", "NVD", MatchConfidence.HEURISTIC)),
                finding("GHSA-jfh8-c2jp-5v3q", 10.0,
                        match("GHSA-jfh8-c2jp-5v3q", "GHSA", MatchConfidence.EXACT))));

        assertThat(merged)
                .as("no source can supply aliases under the Phase 1 contract, so none are claimed")
                .hasSize(2);
    }

    private static AdvisoryFinding finding(String id, Double score, AdvisoryMatch... matches) {
        return new AdvisoryFinding(
                id, "summary of " + id, "HIGH", score, null, PUBLISHED, PUBLISHED, List.of(matches));
    }

    private static AdvisoryMatch match(String id, String source, MatchConfidence confidence) {
        return new AdvisoryMatch(id, source, confidence, "[1.0, 2.0)");
    }

    /** Stands in for the database-backed resolver an {@code advisory_alias} table will provide. */
    private record StubAliasResolver(Map<String, String> aliases) implements AdvisoryAliasResolver {

        @Override
        public Map<String, String> canonicalIds(Collection<String> advisoryIds) {
            Map<String, String> canonical = new HashMap<>();
            for (String id : advisoryIds) {
                String group = aliases.getOrDefault(id, id).toUpperCase(Locale.ROOT);
                canonical.put(id, group);
            }
            return canonical;
        }
    }
}
