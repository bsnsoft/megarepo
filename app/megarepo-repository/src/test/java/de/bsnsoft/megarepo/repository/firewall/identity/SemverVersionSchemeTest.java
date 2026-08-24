package de.bsnsoft.megarepo.repository.firewall.identity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static de.bsnsoft.megarepo.repository.firewall.identity.VersionOrderAssert.assertAscending;
import static de.bsnsoft.megarepo.repository.firewall.identity.VersionOrderAssert.assertEquivalent;
import static de.bsnsoft.megarepo.repository.firewall.identity.VersionOrderAssert.assertLess;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemverVersionSchemeTest {

    private final VersionScheme scheme = VersionSchemes.SEMVER;

    @Test
    void identifiesAsSemver() {
        assertEquals("semver", scheme.id());
    }

    /** The precedence example from semver.org §11, verbatim. */
    @Test
    void semverReferenceOrdering() {
        assertAscending(
                scheme,
                "1.0.0-alpha",
                "1.0.0-alpha.1",
                "1.0.0-alpha.beta",
                "1.0.0-beta",
                "1.0.0-beta.2",
                "1.0.0-beta.11",
                "1.0.0-rc.1",
                "1.0.0",
                "2.0.0",
                "2.1.0",
                "2.1.1");
    }

    @ParameterizedTest(name = "{0} < {1}")
    @CsvSource({
        // any pre-release ranks below the release
        "1.0.0-alpha, 1.0.0",
        "1.0.0-rc.1, 1.0.0",
        "2.0.0-0, 2.0.0",
        // numeric pre-release identifiers compare numerically, not as text
        "1.0.0-beta.2, 1.0.0-beta.11",
        "1.0.0-alpha.2, 1.0.0-alpha.10",
        "1.0.0-rc.9, 1.0.0-rc.10",
        // a numeric identifier ranks below an alphanumeric one
        "1.0.0-alpha.1, 1.0.0-alpha.beta",
        // more identifiers win when the shared prefix is equal
        "1.0.0-alpha, 1.0.0-alpha.1",
        // alphanumeric identifiers use ASCII order, so case matters
        "1.0.0-B, 1.0.0-a",
        // core version ordering
        "1.0.0, 1.0.1",
        "1.0.9, 1.0.10",
        "1.9.0, 1.10.0",
        "0.0.1, 0.1.0",
        // pre-release of a higher core still outranks a lower core
        "1.0.0, 1.0.1-alpha",
    })
    void ordersSemverVersions(String lower, String higher) {
        assertLess(scheme, lower, higher);
    }

    @ParameterizedTest(name = "{0} == {1}")
    @CsvSource({
        // build metadata is explicitly excluded from precedence (semver §10)
        "1.0.0+build.1, 1.0.0+build.2",
        "1.0.0+build.1, 1.0.0",
        "1.0.0-rc.1+exp.sha.5114f85, 1.0.0-rc.1",
        // registry metadata routinely carries these decorations
        "v1.0.0, 1.0.0",
        "=1.0.0, 1.0.0",
        // a missing minor or patch reads as zero
        "1.0, 1.0.0",
        "1, 1.0.0",
    })
    void treatsEquivalentSpellingsAsEqual(String a, String b) {
        assertEquivalent(scheme, a, b);
    }

    @Test
    void nullsSortLowest() {
        assertEquals(0, scheme.compare(null, null));
        assertTrue(scheme.compare(null, "1.0.0") < 0);
        assertTrue(scheme.compare("1.0.0", null) > 0);
    }

    /**
     * npm advisories are usually written as "affected below the patched
     * version". Pre-releases of the patch are still affected, releases are not.
     */
    @Test
    void rangeExcludesFixedVersionButNotItsPreReleases() {
        VersionRange range = VersionRange.before("4.17.21");

        assertTrue(scheme.contains(range, "4.17.20"));
        assertTrue(scheme.contains(range, "4.17.21-rc.1"), "an rc precedes its release");
        assertTrue(scheme.contains(range, "0.1.0"));

        assertFalse(scheme.contains(range, "4.17.21"));
        assertFalse(scheme.contains(range, "4.18.0"));
    }

    /** Build metadata must not smuggle a version out of an advisory range. */
    @Test
    void buildMetadataDoesNotAffectRangeMembership() {
        VersionRange range = VersionRange.before("4.17.21");
        assertTrue(scheme.contains(range, "4.17.20+sha.abc"));
        assertFalse(scheme.contains(range, "4.17.21+sha.abc"));
    }

    @Test
    void unparseableInputSortsBelowParseableAndDoesNotThrow() {
        assertTrue(scheme.compare("next", "1.0.0") < 0);
        assertTrue(scheme.compare("1.0.0", "next") > 0);
        assertEquals(0, scheme.compare("next", "next"));
    }
}
