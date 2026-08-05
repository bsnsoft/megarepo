package de.bsnsoft.megarepo.repository.advisory.ghsa;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The resume token round-trips through {@code advisory_sync_state.cursor} as text. */
class GhsaCursorTest {

    @Test
    void roundTripsBothFields() {
        GhsaCursor cursor =
                new GhsaCursor(Instant.parse("2024-06-30T12:00:00Z"), "Y3Vyc29yOnYyOpK5MjAyMg==");

        GhsaCursor parsed = GhsaCursor.parse(cursor.text());

        assertEquals(cursor, parsed);
        assertTrue(parsed.midImport());
        assertTrue(cursor.text().length() <= 500, "must fit advisory_sync_state.cursor");
    }

    @Test
    void roundTripsAWatermarkOnlyCursor() {
        GhsaCursor cursor = new GhsaCursor(Instant.parse("2024-06-30T12:00:00Z"), null);

        GhsaCursor parsed = GhsaCursor.parse(cursor.text());

        assertEquals(cursor, parsed);
        assertFalse(parsed.midImport());
    }

    @Test
    void roundTripsAPaginationOnlyCursor() {
        GhsaCursor cursor = new GhsaCursor(null, "Y3Vyc29y");

        GhsaCursor parsed = GhsaCursor.parse(cursor.text());

        assertNull(parsed.since());
        assertEquals("Y3Vyc29y", parsed.after());
    }

    @Test
    void nullMeansStartFromTheBeginning() {
        assertEquals(GhsaCursor.start(), GhsaCursor.parse(null));
        assertEquals(GhsaCursor.start(), GhsaCursor.parse(""));
    }

    @Test
    void disabledMarkersParseAsAFreshStart() {
        // So that configuring a token later simply begins a full import.
        assertEquals(GhsaCursor.start(), GhsaCursor.parse(GhsaCursor.DISABLED_NO_TOKEN));
        assertEquals(GhsaCursor.start(), GhsaCursor.parse(GhsaCursor.DISABLED_OFF));
    }

    @Test
    void garbageDoesNotWedgeTheSource() {
        // Re-importing is slow but correct; refusing to run until an operator clears the
        // column by hand is neither.
        assertEquals(GhsaCursor.start(), GhsaCursor.parse("whatever was there before"));
        assertEquals(GhsaCursor.start(), GhsaCursor.parse("v1|not-a-timestamp|token"));
    }

    @Test
    void aTokenContainingSeparatorCharactersSurvives() {
        GhsaCursor cursor = new GhsaCursor(null, "abc=def==");

        assertEquals("abc=def==", GhsaCursor.parse(cursor.text()).after());
    }
}
