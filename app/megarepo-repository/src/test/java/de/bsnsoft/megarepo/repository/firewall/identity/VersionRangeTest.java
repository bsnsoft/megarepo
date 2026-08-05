package de.bsnsoft.megarepo.repository.firewall.identity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Range semantics, tested against the shape {@code advisory_affected} will
 * store: OSV {@code introduced} / {@code fixed} / {@code last_affected} events.
 */
class VersionRangeTest {

    private final VersionScheme semver = VersionSchemes.SEMVER;

    @Test
    void introducedIsInclusiveAndFixedIsExclusive() {
        VersionRange range = VersionRange.between("1.2.0", "1.4.0");

        assertTrue(semver.contains(range, "1.2.0"), "introduced is inclusive");
        assertTrue(semver.contains(range, "1.3.9"));
        assertFalse(semver.contains(range, "1.1.9"));
        assertFalse(semver.contains(range, "1.4.0"), "fixed is exclusive");
    }

    @Test
    void lastAffectedIsInclusive() {
        VersionRange range = VersionRange.throughLastAffected("1.2.0", "1.4.0");

        assertTrue(semver.contains(range, "1.4.0"), "last_affected is inclusive");
        assertFalse(semver.contains(range, "1.4.1"));
    }

    @Test
    void unboundedRangeMatchesEverything() {
        VersionRange range = VersionRange.all();

        assertTrue(range.isUnbounded());
        assertTrue(semver.contains(range, "0.0.1"));
        assertTrue(semver.contains(range, "99.0.0"));
        assertTrue(semver.contains(range, "1.0.0-alpha"));
    }

    @Test
    void openEndedRangeHasNoUpperBound() {
        VersionRange range = VersionRange.from("2.0.0");

        assertFalse(semver.contains(range, "1.9.9"));
        assertTrue(semver.contains(range, "2.0.0"));
        assertTrue(semver.contains(range, "99.0.0"));
    }

    @Test
    void rangeWithOnlyAFixHasNoLowerBound() {
        VersionRange range = VersionRange.before("2.0.0");

        assertTrue(semver.contains(range, "0.0.1"));
        assertTrue(semver.contains(range, "1.9.9"));
        assertFalse(semver.contains(range, "2.0.0"));
    }

    /**
     * OSV writes {@code introduced: "0"} for "since the beginning". Treating it
     * as a literal version would exclude anything the scheme happens to order
     * below {@code 0} — a docker tag like {@code latest}, for instance.
     */
    @Test
    void osvZeroSentinelMeansUnbounded() {
        VersionRange range = VersionRange.from("0");

        assertFalse(range.hasLowerBound());
        assertTrue(semver.contains(range, "0.0.1"));
        assertTrue(
                VersionSchemes.GENERIC.contains(range, "latest"),
                "the generic scheme orders 'latest' below '0'; the sentinel must not exclude it");
    }

    /**
     * OSV treats {@code fixed} and {@code last_affected} as mutually exclusive.
     * Advisory feeds are external input and do violate that, so both bounds are
     * applied rather than the row being rejected on the download path.
     */
    @Test
    void bothUpperBoundsApplyAsAnIntersection() {
        VersionRange range = new VersionRange("1.0.0", "1.5.0", "1.3.0");

        assertTrue(semver.contains(range, "1.3.0"));
        assertFalse(semver.contains(range, "1.4.0"), "the stricter bound wins");
        assertFalse(semver.contains(range, "1.5.0"));
    }

    @Test
    void blankBoundsAreNormalisedToNull() {
        VersionRange range = new VersionRange("  ", "", null);

        assertNull(range.introduced());
        assertNull(range.fixed());
        assertNull(range.lastAffected());
        assertTrue(range.isUnbounded());
    }

    @Test
    void boundsAreTrimmed() {
        assertEquals("1.2.0", VersionRange.from("  1.2.0  ").introduced());
    }

    @Test
    void nullVersionIsNeverContained() {
        assertFalse(semver.contains(VersionRange.all(), null));
    }

    @Test
    void nullRangeIsNeverContained() {
        assertFalse(semver.contains(null, "1.0.0"));
    }

    /**
     * The same bounds must produce different verdicts per ecosystem — that is
     * the whole reason containment lives on the scheme rather than the range.
     */
    @Test
    void containmentDependsOnTheScheme() {
        VersionRange range = VersionRange.before("1.0");

        // PEP 440: 1.0rc1 precedes 1.0, so it is still affected
        assertTrue(VersionSchemes.PEP440.contains(range, "1.0rc1"));
        // Maven: a service pack of 1.0 is newer than 1.0, so it is not
        assertFalse(VersionSchemes.MAVEN.contains(range, "1.0-sp1"));
        // Generic: any suffix ranks above the bare version
        assertFalse(VersionSchemes.GENERIC.contains(range, "1.0-rc1"));
    }
}
