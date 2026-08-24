package de.bsnsoft.megarepo.repository.advisory;

import de.bsnsoft.megarepo.repository.advisory.CpePurlTranslator.CpeMatch;
import de.bsnsoft.megarepo.repository.firewall.identity.VersionRange;
import de.bsnsoft.megarepo.repository.firewall.identity.VersionSchemes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The CPE → purl translation, including — deliberately — the cases where it
 * cannot succeed.
 *
 * <p>Half of these tests assert what the translation refuses to do. That is the
 * point of the class: the customer's complaint was invented precision, so the
 * limits are as much a part of the specification as the mapping itself.
 *
 * <p>No database, no network.
 */
class CpePurlTranslatorTest {

    @Nested
    @DisplayName("what the translation produces")
    class Mapping {

        @Test
        @DisplayName("vendor and product become namespace and name under the reserved cpe type")
        void vendorAndProductAreKept() {
            NormalizedAffected affected = translate(
                    new CpeMatch("apache", "log4j", null, "2.0", null, null, "2.15.0"));

            assertThat(affected.purlType()).isEqualTo("cpe");
            assertThat(affected.purlNamespace()).isEqualTo("apache");
            assertThat(affected.purlName()).isEqualTo("log4j");
        }

        @Test
        @DisplayName("the reserved type is not a purl type — a cpe row can never be mistaken for a purl")
        void cpeTypeIsNotAPurlType() {
            assertThat(VersionSchemes.isKnownPurlType(CpePurlTranslator.PURL_TYPE))
                    .as("'cpe' must not resolve to an ecosystem scheme; a CPE names no ecosystem")
                    .isFalse();
            assertThat(CpePurlTranslator.isCpeDerived("cpe")).isTrue();
            assertThat(CpePurlTranslator.isCpeDerived("maven")).isFalse();
        }

        @Test
        @DisplayName("every CPE-derived row can only support a heuristic finding")
        void cpeRowsAreHeuristic() {
            assertThat(CpePurlTranslator.confidenceFor("cpe")).isEqualTo(MatchConfidence.HEURISTIC);
            assertThat(CpePurlTranslator.confidenceFor("maven")).isEqualTo(MatchConfidence.EXACT);
            assertThat(CpePurlTranslator.confidenceFor(null)).isEqualTo(MatchConfidence.EXACT);
        }

        @Test
        @DisplayName("vendor and product are case-folded, because CPE names are case-insensitive")
        void vendorAndProductAreLowerCased() {
            NormalizedAffected affected =
                    translate(CpeMatch.allVersions("  Apache  ", "Log4J"));

            assertThat(affected.purlNamespace()).isEqualTo("apache");
            assertThat(affected.purlName()).isEqualTo("log4j");
        }

        @Test
        @DisplayName("a CPE without a product cannot be translated at all")
        void productIsRequired() {
            assertThat(CpePurlTranslator.translate(CpeMatch.allVersions("apache", null))).isEmpty();
            assertThat(CpePurlTranslator.translate(CpeMatch.allVersions("apache", "   "))).isEmpty();
            assertThat(CpePurlTranslator.translate(null)).isEmpty();
        }

        @Test
        @DisplayName("a missing vendor is tolerated — it is never used for matching anyway")
        void vendorIsOptional() {
            NormalizedAffected affected = translate(CpeMatch.allVersions(null, "log4j"));

            assertThat(affected.purlNamespace()).isNull();
            assertThat(affected.purlName()).isEqualTo("log4j");
        }
    }

    @Nested
    @DisplayName("version bounds")
    class Bounds {

        @Test
        @DisplayName("start-including and end-excluding map without loss")
        void halfOpenRangeMapsExactly() {
            NormalizedAffected affected = translate(
                    new CpeMatch("apache", "log4j", null, "2.0", null, null, "2.15.0"));

            assertThat(affected.introduced()).isEqualTo("2.0");
            assertThat(affected.fixed()).isEqualTo("2.15.0");
            assertThat(affected.lastAffected()).isNull();
        }

        @Test
        @DisplayName("end-including maps to last_affected, not to fixed")
        void inclusiveUpperBoundMapsToLastAffected() {
            NormalizedAffected affected = translate(
                    new CpeMatch("apache", "log4j", null, "2.0", null, "2.14.1", null));

            assertThat(affected.lastAffected()).isEqualTo("2.14.1");
            assertThat(affected.fixed()).isNull();

            // 2.14.1 is still vulnerable, 2.15.0 is not.
            VersionRange range = new VersionRange(
                    affected.introduced(), affected.fixed(), affected.lastAffected());
            assertThat(VersionSchemes.MAVEN.contains(range, "2.14.1")).isTrue();
            assertThat(VersionSchemes.MAVEN.contains(range, "2.15.0")).isFalse();
        }

        @Test
        @DisplayName("an exact version becomes the closed range [v, v]")
        void exactVersionBecomesAClosedRange() {
            NormalizedAffected affected = translate(
                    new CpeMatch("apache", "log4j", "2.14.1", null, null, null, null));

            assertThat(affected.introduced()).isEqualTo("2.14.1");
            assertThat(affected.lastAffected()).isEqualTo("2.14.1");
            assertThat(affected.fixed()).isNull();

            VersionRange range = new VersionRange(
                    affected.introduced(), affected.fixed(), affected.lastAffected());
            assertThat(VersionSchemes.MAVEN.contains(range, "2.14.1")).isTrue();
            assertThat(VersionSchemes.MAVEN.contains(range, "2.14.0")).isFalse();
            assertThat(VersionSchemes.MAVEN.contains(range, "2.14.2")).isFalse();
        }

        @Test
        @DisplayName("a wildcarded CPE becomes an unbounded range — every version is affected")
        void wildcardBecomesUnbounded() {
            NormalizedAffected affected = translate(CpeMatch.allVersions("apache", "log4j"));

            assertThat(affected.introduced()).isNull();
            assertThat(affected.fixed()).isNull();
            assertThat(affected.lastAffected()).isNull();
            assertThat(new VersionRange(null, null, null).isUnbounded()).isTrue();
        }

        @Test
        @DisplayName("LOSSY: an exclusive lower bound is widened to inclusive, over-reporting one version")
        void exclusiveLowerBoundIsWidened() {
            // OSV's `introduced` is inclusive and has no exclusive counterpart,
            // so ">2.0" cannot be expressed. It is widened to ">=2.0": one false
            // positive on the boundary version, never a false negative.
            NormalizedAffected affected = translate(
                    new CpeMatch("apache", "log4j", null, null, "2.0", null, "2.15.0"));

            assertThat(affected.introduced()).isEqualTo("2.0");

            VersionRange range = new VersionRange(
                    affected.introduced(), affected.fixed(), affected.lastAffected());
            assertThat(VersionSchemes.MAVEN.contains(range, "2.0"))
                    .as("2.0 is reported as affected although the CPE excluded it — the documented widening")
                    .isTrue();

            // ...and the original bound survives verbatim for auditing.
            assertThat(affected.versionRange()).contains(">2.0");
        }

        @Test
        @DisplayName("an inclusive lower bound wins over an exclusive one when a feed publishes both")
        void inclusiveLowerBoundWins() {
            NormalizedAffected affected = translate(
                    new CpeMatch("apache", "log4j", null, "2.1", "2.0", null, null));

            assertThat(affected.introduced()).isEqualTo("2.1");
        }
    }

    @Nested
    @DisplayName("the range description kept for auditing")
    class Description {

        @Test
        void rendersBothBounds() {
            assertThat(CpePurlTranslator.describe(
                            new CpeMatch("apache", "log4j", null, "2.0", null, null, "2.15.0")))
                    .isEqualTo("cpe apache:log4j >=2.0, <2.15.0");
        }

        @Test
        void rendersAnExactVersion() {
            assertThat(CpePurlTranslator.describe(
                            new CpeMatch("apache", "log4j", "2.14.1", null, null, null, null)))
                    .isEqualTo("cpe apache:log4j =2.14.1");
        }

        @Test
        void rendersAWildcard() {
            assertThat(CpePurlTranslator.describe(CpeMatch.allVersions(null, "log4j")))
                    .isEqualTo("cpe *:log4j *");
        }
    }

    @Nested
    @DisplayName("product candidates: what the lookup is allowed to guess")
    class ProductCandidates {

        @Test
        @DisplayName("dashes and dots are normalised to underscores, the way the CPE dictionary does")
        void separatorsAreNormalised() {
            assertThat(CpePurlTranslator.productCandidatesFor("commons-text"))
                    .contains("commons-text", "commons_text");
            assertThat(CpePurlTranslator.productCandidatesFor("log4j.core"))
                    .contains("log4j.core", "log4j_core");
        }

        @Test
        @DisplayName("names are case-folded")
        void namesAreLowerCased() {
            assertThat(CpePurlTranslator.productCandidatesFor("Django")).contains("django");
        }

        @Test
        @DisplayName("REGRESSION: a sub-artifact is never folded onto its base product")
        void subArtifactsAreNotFoldedOntoTheBaseProduct() {
            // The legacy NvdCveLookupService generated "log4j" for "log4j-api",
            // so log4j-api inherited every CVE ever filed against log4j-core.
            // CpeGuessingVsPurlIdentityTest pins that defect; this pins its
            // absence in the replacement.
            Set<String> candidates = CpePurlTranslator.productCandidatesFor("log4j-api");

            assertThat(candidates).doesNotContain("log4j");
            assertThat(candidates).containsExactlyInAnyOrder("log4j-api", "log4j_api");
        }

        @Test
        @DisplayName("a blank name produces no candidates rather than matching everything")
        void blankNameProducesNothing() {
            assertThat(CpePurlTranslator.productCandidatesFor(null)).isEmpty();
            assertThat(CpePurlTranslator.productCandidatesFor("   ")).isEmpty();
        }

        @Test
        @DisplayName("LOSSY: two unrelated packages sharing a name share every CPE candidate")
        void unrelatedPackagesCollapseOntoTheSameProduct() {
            // This is the limit that cannot be engineered away: a CPE vendor is
            // an organisation name, a purl namespace is an ecosystem coordinate,
            // and nothing maps one onto the other. com.acme:util and
            // org.other:util are therefore indistinguishable to the CPE-derived
            // pass — which is exactly why its findings are labelled HEURISTIC
            // instead of being treated as identity matches.
            assertThat(CpePurlTranslator.productCandidatesFor("util"))
                    .isEqualTo(CpePurlTranslator.productCandidatesFor("util"));

            NormalizedAffected acme = translate(CpeMatch.allVersions("acme", "util"));
            NormalizedAffected other = translate(CpeMatch.allVersions("other_corp", "util"));

            assertThat(acme.purlName()).isEqualTo(other.purlName());
            assertThat(acme.purlNamespace())
                    .as("the vendor is retained for display, so a report can still tell them apart")
                    .isNotEqualTo(other.purlNamespace());
        }
    }

    private static NormalizedAffected translate(CpeMatch match) {
        Optional<NormalizedAffected> affected = CpePurlTranslator.translate(match);
        assertThat(affected).isPresent();
        return affected.orElseThrow();
    }
}
