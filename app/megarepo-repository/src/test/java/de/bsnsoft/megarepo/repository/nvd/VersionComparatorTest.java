package de.bsnsoft.megarepo.repository.nvd;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionComparatorTest {

    @Test
    void equalVersionsCompareZero() {
        assertEquals(0, VersionComparator.compare("1.0.0", "1.0.0"));
        assertEquals(0, VersionComparator.compare("2.14.1", "2.14.1"));
    }

    @Test
    void higherPatchIsGreater() {
        assertTrue(VersionComparator.compare("2.14.1", "2.14.0") > 0);
        assertTrue(VersionComparator.compare("2.14.0", "2.14.1") < 0);
    }

    @Test
    void higherMinorIsGreater() {
        assertTrue(VersionComparator.compare("2.15.0", "2.14.999") > 0);
    }

    @Test
    void missingSegmentsTreatedAsZero() {
        assertEquals(0, VersionComparator.compare("1.0", "1.0.0"));
        assertTrue(VersionComparator.compare("1.0.1", "1.0") > 0);
    }

    @Test
    void qualifierSortsBelowRelease() {
        // Release > pre-release with same main version
        assertTrue(VersionComparator.compare("1.0.0", "1.0.0-rc1") > 0);
        assertTrue(VersionComparator.compare("2.0", "2.0-SNAPSHOT") > 0);
        assertTrue(VersionComparator.compare("2.0-rc2", "2.0-rc1") > 0);
    }

    @Test
    void log4ShellRange() {
        // Log4Shell: vulnerable versions 2.0-beta9 <= v < 2.17.0
        String start = "2.0-beta9";
        String end = "2.17.0";
        // vulnerable version
        String v = "2.14.1";
        assertTrue(VersionComparator.compare(v, start) >= 0, "2.14.1 >= 2.0-beta9");
        assertTrue(VersionComparator.compare(v, end) < 0, "2.14.1 < 2.17.0");
        // not vulnerable
        assertTrue(VersionComparator.compare("2.17.0", end) >= 0, "2.17.0 >= 2.17.0");
        assertTrue(VersionComparator.compare("2.17.1", end) > 0, "2.17.1 > 2.17.0");
    }

    @Test
    void nullsSortLow() {
        assertTrue(VersionComparator.compare(null, "1.0") < 0);
        assertTrue(VersionComparator.compare("1.0", null) > 0);
        assertEquals(0, VersionComparator.compare(null, null));
    }
}
