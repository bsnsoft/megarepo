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

class NuGetVersionSchemeTest {

    private final VersionScheme scheme = VersionSchemes.NUGET;

    @Test
    void identifiesAsNuGet() {
        assertEquals("nuget", scheme.id());
    }

    /** A four-part chain that runs the revision segment through pre-releases. */
    @Test
    void fourPartReferenceOrdering() {
        assertAscending(
                scheme,
                "1.0.0-alpha",
                "1.0.0-beta.2",
                "1.0.0-beta.10",
                "1.0.0-rc.1",
                "1.0.0",
                "1.0.0.1-alpha",
                "1.0.0.1",
                "1.0.0.2",
                "1.0.0.10",
                "1.0.1");
    }

    @ParameterizedTest(name = "{0} < {1}")
    @CsvSource({
        // the fourth (revision) segment orders like the others
        "1.0.0, 1.0.0.1",
        "1.2.3.4, 1.2.3.5",
        "1.2.3.9, 1.2.3.10",
        "1.2.3.99, 1.2.4",
        // pre-release below release, at four parts too
        "1.0.0.4-rc1, 1.0.0.4",
        "1.0.0.4-alpha, 1.0.0.4-beta",
        // SemVer2 dot-separated pre-release labels compare numerically
        "1.0.0.4-rc.2, 1.0.0.4-rc.10",
        "1.0.0-beta.2, 1.0.0-beta.11",
        // numeric label ranks below alphanumeric, more labels win on a tie
        "1.0.0-rc.1, 1.0.0-rc.beta",
        "1.0.0-rc, 1.0.0-rc.1",
        // plain numeric ordering
        "1.0.0, 1.0.1",
        "1.9.0, 1.10.0",
    })
    void ordersNuGetVersions(String lower, String higher) {
        assertLess(scheme, lower, higher);
    }

    @ParameterizedTest(name = "{0} == {1}")
    @CsvSource({
        // a zero revision is dropped by NuGet's normalisation
        "1.0.0.0, 1.0.0",
        "6.0.0.0, 6.0.0",
        "1.0.0.0-beta, 1.0.0-beta",
        // missing segments read as zero
        "1.0, 1.0.0",
        "1, 1.0.0.0",
        // leading zeros are stripped during normalisation
        "01.02.03, 1.2.3",
        // build metadata is irrelevant to NuGet ordering and identity
        "1.0.0+sha.deadbeef, 1.0.0",
        "1.0.0.1+build1, 1.0.0.1+build2",
        "1.0.0-rc.1+meta, 1.0.0-rc.1",
        // NuGet compares release labels ordinal-ignore-case
        "1.0.0-Beta, 1.0.0-beta",
        "1.0.0.4-Alpha, 1.0.0.4-alpha",
        "1.0.0-RC.1, 1.0.0-rc.1",
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
     * A .NET advisory range whose bounds are three-part while the installed
     * package carries a revision — the case where treating the revision as
     * text would silently drop the component out of the range.
     */
    @Test
    void rangeCoversFourPartVersionsBetweenThreePartBounds() {
        VersionRange range = VersionRange.between("4.0.0", "4.5.0");

        assertTrue(scheme.contains(range, "4.0.0"));
        assertTrue(scheme.contains(range, "4.4.9.9"));
        assertTrue(scheme.contains(range, "4.5.0-rc.1"), "an rc precedes its release");
        assertTrue(scheme.contains(range, "4.0.0.0"), "a zero revision is the same version");

        assertFalse(scheme.contains(range, "3.9.9.9"));
        assertFalse(scheme.contains(range, "4.5.0"));
        assertFalse(scheme.contains(range, "4.5.0.1"));
    }

    @Test
    void unparseableInputSortsBelowParseableAndDoesNotThrow() {
        assertTrue(scheme.compare("preview", "1.0.0") < 0);
        assertTrue(scheme.compare("1.0.0", "preview") > 0);
        assertEquals(0, scheme.compare("preview", "preview"));
    }
}
