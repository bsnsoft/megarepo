package de.bsnsoft.megarepo.repository.advisory.ghsa;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Bound extraction from GHSA's {@code vulnerable_version_range} expressions. */
class GhsaRangesTest {

    @Test
    void closedRange() {
        GhsaRanges.Bounds bounds = GhsaRanges.parse(">= 2.0.1, < 2.15.0", null);

        assertEquals("2.0.1", bounds.introduced());
        assertEquals("2.15.0", bounds.fixed());
        assertNull(bounds.lastAffected());
    }

    @Test
    void openLowerBound() {
        GhsaRanges.Bounds bounds = GhsaRanges.parse("< 4.17.20", null);

        assertNull(bounds.introduced());
        assertEquals("4.17.20", bounds.fixed());
        assertNull(bounds.lastAffected());
    }

    @Test
    void inclusiveUpperBoundBecomesLastAffected() {
        GhsaRanges.Bounds bounds = GhsaRanges.parse(">= 1.0.0, <= 4.0.0", null);

        assertEquals("1.0.0", bounds.introduced());
        assertNull(bounds.fixed());
        assertEquals("4.0.0", bounds.lastAffected());
    }

    @Test
    void exactVersionIsBothEnds() {
        GhsaRanges.Bounds bounds = GhsaRanges.parse("= 1.2.3", null);

        assertEquals("1.2.3", bounds.introduced());
        assertEquals("1.2.3", bounds.lastAffected());
        assertNull(bounds.fixed());
    }

    @Test
    void exclusiveLowerBoundIsWidenedToInclusive() {
        // OSV's introduced is inclusive, so "> 1.0.0" cannot be expressed exactly.
        // Recording 1.0.0 over-reports by one version instead of dropping the bound and
        // over-reporting everything below it; the raw expression stays the truth.
        GhsaRanges.Bounds bounds = GhsaRanges.parse("> 1.0.0, < 2.0.0", null);

        assertEquals("1.0.0", bounds.introduced());
        assertEquals("2.0.0", bounds.fixed());
    }

    @Test
    void firstPatchedVersionWinsOverTheRange() {
        GhsaRanges.Bounds bounds = GhsaRanges.parse(">= 2.0.0", "2.9.10");

        assertEquals("2.0.0", bounds.introduced());
        assertEquals("2.9.10", bounds.fixed());
        assertNull(bounds.lastAffected());
    }

    @Test
    void knownFixClearsLastAffected() {
        GhsaRanges.Bounds bounds = GhsaRanges.parse("<= 3.0.9", "3.1.0");

        assertEquals("3.1.0", bounds.fixed());
        assertNull(bounds.lastAffected(), "fixed and lastAffected are alternatives");
    }

    @Test
    void unparseableExpressionKeepsAllBoundsNull() {
        GhsaRanges.Bounds bounds = GhsaRanges.parse("all versions before the rewrite", null);

        assertEquals(GhsaRanges.Bounds.NONE, bounds);
    }

    @Test
    void missingRangeAndMissingFixYieldNothing() {
        assertEquals(GhsaRanges.Bounds.NONE, GhsaRanges.parse(null, null));
        assertEquals(GhsaRanges.Bounds.NONE, GhsaRanges.parse("  ", "   "));
    }

    @Test
    void fixAloneIsEnough() {
        GhsaRanges.Bounds bounds = GhsaRanges.parse(null, "1.4.0");

        assertNull(bounds.introduced());
        assertEquals("1.4.0", bounds.fixed());
    }

    @Test
    void spacingVariantsAreTolerated() {
        GhsaRanges.Bounds bounds = GhsaRanges.parse(">=2.0.1,<2.15.0", null);

        assertEquals("2.0.1", bounds.introduced());
        assertEquals("2.15.0", bounds.fixed());
    }
}
