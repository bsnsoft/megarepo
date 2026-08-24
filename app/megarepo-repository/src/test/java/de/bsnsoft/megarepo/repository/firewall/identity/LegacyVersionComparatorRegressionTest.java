package de.bsnsoft.megarepo.repository.firewall.identity;

import de.bsnsoft.megarepo.repository.nvd.VersionComparator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Evidence that the generic {@link VersionComparator} really does mis-order the
 * cases named in the customer's specification, and that the per-ecosystem
 * {@link VersionScheme}s fix them.
 *
 * <p>Every row asserts <strong>both</strong> directions: the new scheme gives
 * the ecosystem-correct answer, and the old comparator gives a different one.
 * Asserting only the new behaviour would leave the claim "the current
 * comparator is wrong" unproven; asserting only the old behaviour would let a
 * broken replacement pass.
 *
 * <p>The old comparator is not deleted by this change — other code paths still
 * call it — so these tests also serve as a tripwire: if someone ever repairs
 * {@code VersionComparator}, the affected rows fail loudly and can be retired
 * deliberately instead of rotting.
 *
 * <p>The four failure classes are exactly the ones the specification calls out:
 * Maven {@code -SNAPSHOT} and qualifiers, PEP 440 {@code rc}/{@code post},
 * semver pre-release precedence, and NuGet four-part versions.
 */
class LegacyVersionComparatorRegressionTest {

    // ---------------------------------------------------------------- Maven

    /**
     * Maven qualifiers. The old comparator applies one blunt rule — "any
     * qualifier sorts below no qualifier" — plus a case-sensitive string
     * compare between qualifiers. Maven's actual table is
     * {@code alpha < beta < milestone < rc < snapshot < "" < sp}, with aliases.
     */
    @ParameterizedTest(name = "maven: {0} < {1} — {2}")
    @CsvSource({
        "1.0, 1.0-sp1, a service pack ranks ABOVE the plain release; the old rule puts every qualifier below it",
        "1.0-rc1, 1.0-SNAPSHOT, qualifiers are compared case-sensitively by the old comparator so SNAPSHOT lands below rc1",
        "1.0a1, 1.0, the short alpha alias is read as a numeric suffix and pushes 1.0a1 above the release",
        "1.0-alpha9, 1.0-alpha10, the trailing number is compared as text so alpha10 lands below alpha9",
    })
    void mavenQualifiersWereMisordered(String lower, String higher, String why) {
        assertSchemeFixesOrdering(VersionSchemes.MAVEN, lower, higher, why);
    }

    @ParameterizedTest(name = "maven: {0} == {1} — {2}")
    @CsvSource({
        "1.0-final, 1.0, final/ga/release are aliases for the plain release",
        "1.0-ga, 1.0, final/ga/release are aliases for the plain release",
        "1.0-cr1, 1.0-rc1, cr is the Maven alias for rc",
    })
    void mavenAliasesWereTreatedAsDistinctVersions(String a, String b, String why) {
        assertSchemeFixesEquality(VersionSchemes.MAVEN, a, b, why);
    }

    // -------------------------------------------------------------- PEP 440

    /**
     * PEP 440 is the clearest counter-example to a single generic algorithm:
     * {@code rc} sorts below the release while {@code post} sorts above it. No
     * "qualifier means older" rule can satisfy both.
     */
    @ParameterizedTest(name = "pep440: {0} < {1} — {2}")
    @CsvSource({
        "1.0, 1.0.post1, a post release is NEWER than its release; the old comparator reads .post1 as a text segment and sorts it below",
        "1.0rc1, 1.0, an rc is OLDER than its release; without a separator the old comparator reads rc1 as an extra segment and sorts it above",
        "2.0, 1!1.0, an epoch outranks the whole release segment; the old comparator compares 1!1 against 2 digit-wise",
        "1.0, 1.0-1, a bare -N after the release is an implicit .postN rather than a pre-release qualifier",
    })
    void pep440SuffixesWereMisordered(String lower, String higher, String why) {
        assertSchemeFixesOrdering(VersionSchemes.PEP440, lower, higher, why);
    }

    @ParameterizedTest(name = "pep440: {0} == {1} — {2}")
    @CsvSource({
        "1.0rev2, 1.0.post2, rev and r are spellings of post",
        "1.0-r2, 1.0.post2, rev and r are spellings of post",
        "1.0c1, 1.0rc1, c/pre/preview are spellings of rc",
    })
    void pep440SpellingsWereTreatedAsDistinctVersions(String a, String b, String why) {
        assertSchemeFixesEquality(VersionSchemes.PEP440, a, b, why);
    }

    // --------------------------------------------------------------- semver

    /**
     * Semver pre-release precedence is per dot-separated identifier, with
     * numeric identifiers compared as numbers. The old comparator compares the
     * whole qualifier as one string, which is where {@code beta.11} ends up
     * below {@code beta.2}.
     */
    @ParameterizedTest(name = "semver: {0} < {1} — {2}")
    @CsvSource({
        "1.0.0-alpha.2, 1.0.0-alpha.10, numeric pre-release identifiers compare numerically; a string compare puts .10 before .2",
        "1.0.0-beta.2, 1.0.0-beta.11, numeric pre-release identifiers compare numerically; a string compare puts .11 before .2",
    })
    void semverPreReleasePrecedenceWasMisordered(String lower, String higher, String why) {
        assertSchemeFixesOrdering(VersionSchemes.SEMVER, lower, higher, why);
    }

    @ParameterizedTest(name = "semver: {0} == {1} — {2}")
    @CsvSource({
        "1.0.0+build.1, 1.0.0+build.2, build metadata is excluded from precedence (semver 10)",
        "1.0.0+build.1, 1.0.0, build metadata is excluded from precedence (semver 10)",
    })
    void semverBuildMetadataWasTreatedAsPrecedence(String a, String b, String why) {
        assertSchemeFixesEquality(VersionSchemes.SEMVER, a, b, why);
    }

    // ---------------------------------------------------------------- NuGet

    /**
     * NuGet allows a fourth (revision) segment and compares release labels
     * ordinal-ignore-case. Both rows below use four-part versions, which is the
     * shape the specification names.
     */
    @ParameterizedTest(name = "nuget: {0} < {1} — {2}")
    @CsvSource({
        "1.0.0.4-rc.2, 1.0.0.4-rc.10, a four-part version with a numeric SemVer2 label; the old comparator compares rc.2 against rc.10 as text",
        "1.0.0-rc.2, 1.0.0-rc.10, same defect without the revision segment",
    })
    void nugetPreReleaseLabelsWereMisordered(String lower, String higher, String why) {
        assertSchemeFixesOrdering(VersionSchemes.NUGET, lower, higher, why);
    }

    @ParameterizedTest(name = "nuget: {0} == {1} — {2}")
    @CsvSource({
        "1.0.0.4-Alpha, 1.0.0.4-alpha, NuGet folds case on release labels; the old comparator compares them case-sensitively",
        "1.0.0-Beta, 1.0.0-beta, NuGet folds case on release labels; the old comparator compares them case-sensitively",
        "1.0.0.1, 1.0.0.1+sha.deadbeef, build metadata is not part of NuGet version identity",
    })
    void nugetLabelCaseAndMetadataWereTreatedAsDistinctVersions(String a, String b, String why) {
        assertSchemeFixesEquality(VersionSchemes.NUGET, a, b, why);
    }

    // -------------------------------------------------------- range effects

    /**
     * The mis-orderings are not academic. Each of these is an advisory range
     * that the old comparator answers wrongly, i.e. a component that the
     * firewall would either wave through or block for the wrong reason.
     */
    @Test
    void misorderingChangesAdvisoryRangeVerdicts() {
        // A Maven service-pack build of the fixed version is NOT vulnerable: it
        // is newer than the fix. The old comparator ranks it below 2.17.0 and
        // would keep flagging it.
        VersionRange log4shell = VersionRange.between("2.0-beta9", "2.17.0");
        assertFalse(
                VersionSchemes.MAVEN.contains(log4shell, "2.17.0-sp1"),
                "a service pack of the fixed release is fixed");
        assertTrue(
                VersionComparator.compare("2.17.0-sp1", "2.17.0") < 0,
                "the old comparator ranks the service pack below the release");

        // A PyPI post release of the fixed version is NOT vulnerable either.
        VersionRange pyRange = VersionRange.between("0", "2.0.1");
        assertFalse(
                VersionSchemes.PEP440.contains(pyRange, "2.0.1.post1"),
                "a post release of the fix carries the fix");
        assertTrue(
                VersionComparator.compare("2.0.1.post1", "2.0.1") < 0,
                "the old comparator ranks the post release below the fix");

        // The reverse error: a genuinely vulnerable npm pre-release that the old
        // ordering lets through. beta.11 is inside [1.0.0-beta.0, 1.0.0-beta.5)
        // for the old comparator because it sorts beta.11 below beta.5.
        VersionRange npmRange = VersionRange.between("1.0.0-beta.0", "1.0.0-beta.5");
        assertFalse(
                VersionSchemes.SEMVER.contains(npmRange, "1.0.0-beta.11"),
                "beta.11 is past the fix and must not be flagged");
        assertTrue(
                VersionComparator.compare("1.0.0-beta.11", "1.0.0-beta.5") < 0,
                "the old comparator ranks beta.11 below beta.5, so it would flag it");
    }

    // -------------------------------------------------------------- helpers

    private static void assertSchemeFixesOrdering(
            VersionScheme scheme, String lower, String higher, String why) {
        assertTrue(
                scheme.compare(lower, higher) < 0,
                () -> scheme.id() + " must order " + lower + " < " + higher + " — " + why);
        assertTrue(
                scheme.compare(higher, lower) > 0,
                () -> scheme.id() + " must order " + higher + " > " + lower + " — " + why);
        assertFalse(
                VersionComparator.compare(lower, higher) < 0,
                () -> "the legacy VersionComparator is expected to get " + lower + " vs " + higher
                        + " wrong; if it no longer does, retire this row — " + why);
    }

    private static void assertSchemeFixesEquality(
            VersionScheme scheme, String a, String b, String why) {
        assertEquals(
                0,
                scheme.compare(a, b),
                () -> scheme.id() + " must treat " + a + " and " + b + " as the same version — " + why);
        assertNotEquals(
                0,
                VersionComparator.compare(a, b),
                () -> "the legacy VersionComparator is expected to separate " + a + " and " + b
                        + "; if it no longer does, retire this row — " + why);
    }
}
