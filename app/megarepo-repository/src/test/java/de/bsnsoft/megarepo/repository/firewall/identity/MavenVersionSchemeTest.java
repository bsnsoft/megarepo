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

class MavenVersionSchemeTest {

    private final VersionScheme scheme = VersionSchemes.MAVEN;

    @Test
    void identifiesAsMaven() {
        assertEquals("maven", scheme.id());
    }

    /**
     * Maven's qualifier table in full. The two ends are what a generic
     * comparator cannot know: {@code snapshot} sits directly below the plain
     * release, and {@code sp} (service pack) sits <em>above</em> it.
     */
    @Test
    void qualifierTableOrder() {
        assertAscending(
                scheme,
                "1.0-alpha1",
                "1.0-beta1",
                "1.0-milestone1",
                "1.0-rc1",
                "1.0-SNAPSHOT",
                "1.0",
                "1.0-sp1");
    }

    @ParameterizedTest(name = "{0} < {1}")
    @CsvSource({
        // -SNAPSHOT ranks below the release it will become
        "1.0-SNAPSHOT, 1.0",
        "2.0-SNAPSHOT, 2.0",
        "1.0, 1.1-SNAPSHOT",
        // qualifier precedence, including the case-insensitive spellings
        "1.0-alpha1, 1.0-beta1",
        "1.0-BETA1, 1.0-rc1",
        "1.0-rc1, 1.0-snapshot",
        "1.0-rc1, 1.0-SNAPSHOT",
        // a qualifier's trailing number is a number, not text
        "1.0-alpha9, 1.0-alpha10",
        "1.0-rc2, 1.0-rc10",
        // single-letter qualifiers alias to their long form when a digit follows
        "1.0a1, 1.0b1",
        "1.0b1, 1.0m1",
        "1.0a1, 1.0",
        // plain numeric ordering
        "1.9.9, 2.0",
        "2.14.1, 2.17.0",
        "1.0.1, 1.0.10",
        // an unrecognised qualifier ranks above every known one
        "1.0-rc1, 1.0-zzz",
        "1.0-sp1, 1.0-zzz",
    })
    void ordersMavenVersions(String lower, String higher) {
        assertLess(scheme, lower, higher);
    }

    @ParameterizedTest(name = "{0} == {1}")
    @CsvSource({
        // trailing zeros carry no meaning
        "1.0, 1.0.0",
        "1, 1.0.0.0",
        // ga / final / release are all spellings of "the plain release"
        "1.0-final, 1.0",
        "1.0-ga, 1.0",
        "1.0-release, 1.0",
        // cr is an alias for rc
        "1.0-cr1, 1.0-rc1",
        // short qualifier followed by a digit expands
        "1.0a1, 1.0-alpha-1",
        "1.0b2, 1.0-beta-2",
    })
    void treatsEquivalentSpellingsAsEqual(String a, String b) {
        assertEquivalent(scheme, a, b);
    }

    @Test
    void nullsSortLowest() {
        assertEquals(0, scheme.compare(null, null));
        assertTrue(scheme.compare(null, "1.0") < 0);
        assertTrue(scheme.compare("1.0", null) > 0);
    }

    /**
     * CVE-2021-44228. The advisory range is {@code [2.0-beta9, 2.17.0)} — the
     * lower bound is a qualifier version and the upper bound is exclusive, so
     * both range semantics and qualifier ordering have to hold at once.
     */
    @Test
    void log4ShellRange() {
        VersionRange range = VersionRange.between("2.0-beta9", "2.17.0");

        assertTrue(scheme.contains(range, "2.0-beta9"), "lower bound is inclusive");
        assertTrue(scheme.contains(range, "2.14.1"));
        assertTrue(scheme.contains(range, "2.16.0"));

        assertFalse(scheme.contains(range, "2.0-beta8"), "below the lower bound");
        assertFalse(scheme.contains(range, "1.9"));
        assertFalse(scheme.contains(range, "2.17.0"), "fixed version is excluded");
        assertFalse(scheme.contains(range, "2.17.1"));
    }

    /**
     * A -SNAPSHOT build of the fixed version must not be reported as fixed: it
     * predates the release, so it is still inside the vulnerable range.
     */
    @Test
    void snapshotOfFixedVersionIsStillAffected() {
        VersionRange range = VersionRange.between("2.0-beta9", "2.17.0");
        assertTrue(scheme.contains(range, "2.17.0-SNAPSHOT"));
    }
}
