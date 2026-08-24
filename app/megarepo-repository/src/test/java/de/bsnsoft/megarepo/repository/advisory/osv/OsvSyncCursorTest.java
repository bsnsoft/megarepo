package de.bsnsoft.megarepo.repository.advisory.osv;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OsvSyncCursorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void roundTripsThroughTheSyncStateColumn() {
        Map<String, Instant> since = new LinkedHashMap<>();
        since.put("Maven", Instant.parse("2026-02-14T05:23:41Z"));
        since.put("npm", Instant.parse("2026-03-02T05:12:41Z"));
        OsvSyncCursor cursor = new OsvSyncCursor(
                2,
                4000,
                since,
                Instant.parse("2026-03-04T00:00:00Z"),
                Instant.parse("2026-03-01T00:00:00Z"),
                true);

        OsvSyncCursor parsed = OsvSyncCursor.parse(cursor.format(mapper), mapper);

        assertEquals(2, parsed.ecosystemIndex());
        assertEquals(4000, parsed.entryOffset());
        assertEquals(since, parsed.since());
        assertEquals(cursor.currentMax(), parsed.currentMax());
        assertEquals(cursor.lastFullPass(), parsed.lastFullPass());
        assertTrue(parsed.fullPass());
    }

    @Test
    void fitsInTheSyncStateColumnWithEveryEcosystemWatermarked() {
        Map<String, Instant> since = new LinkedHashMap<>();
        for (OsvEcosystem ecosystem : OsvEcosystem.values()) {
            since.put(ecosystem.osvName(), Instant.parse("2026-03-02T05:12:41.123456789Z"));
        }
        String formatted = new OsvSyncCursor(
                        3,
                        123456,
                        since,
                        Instant.parse("2026-03-02T05:12:41.123456789Z"),
                        Instant.parse("2026-03-01T00:00:00Z"),
                        false)
                .format(mapper);

        assertTrue(
                formatted.length() <= OsvSyncCursor.MAX_LENGTH,
                "cursor is " + formatted.length() + " chars, column holds 500");
        assertEquals(since, OsvSyncCursor.parse(formatted, mapper).since());
    }

    @Test
    void anythingUnreadableStartsOver() {
        for (String broken : new String[] {
            null, "", "   ", "not json", "[]", "{\"v\":99,\"eco\":3}", "{\"eco\":3}"
        }) {
            OsvSyncCursor cursor = OsvSyncCursor.parse(broken, mapper);
            assertEquals(0, cursor.ecosystemIndex(), "for cursor: " + broken);
            assertEquals(0, cursor.entryOffset());
            assertTrue(cursor.since().isEmpty());
            assertTrue(cursor.fullPass(), "a restart is by definition an unfiltered pass");
        }
    }

    @Test
    void watermarksOnlyMoveForward() {
        OsvSyncCursor cursor = OsvSyncCursor.initial()
                .withCurrentMax(Instant.parse("2026-01-02T00:00:00Z"))
                .withCurrentMax(Instant.parse("2026-01-01T00:00:00Z"))
                .withCurrentMax(null);
        assertEquals(Instant.parse("2026-01-02T00:00:00Z"), cursor.currentMax());

        OsvSyncCursor completed = cursor.completedEcosystem(OsvEcosystem.MAVEN);
        assertEquals(Instant.parse("2026-01-02T00:00:00Z"), completed.sinceFor(OsvEcosystem.MAVEN));
        assertNull(completed.currentMax(), "the running maximum resets with the next archive");
        assertEquals(1, completed.ecosystemIndex());

        OsvSyncCursor older = completed.withCurrentMax(Instant.parse("2025-01-01T00:00:00Z"));
        assertEquals(
                Instant.parse("2026-01-02T00:00:00Z"),
                older.completedEcosystem(OsvEcosystem.MAVEN).sinceFor(OsvEcosystem.MAVEN),
                "a pass that saw only older records must not roll the watermark back");
    }

    @Test
    void completingAFullPassRecordsWhenItRan() {
        Instant now = Instant.parse("2026-04-01T00:00:00Z");
        OsvSyncCursor afterFull = OsvSyncCursor.initial().completedPass(now);
        assertEquals(now, afterFull.lastFullPass());
        assertFalse(afterFull.fullPass());
        assertEquals(0, afterFull.ecosystemIndex());

        OsvSyncCursor afterIncremental = afterFull
                .startingPass(false)
                .completedPass(Instant.parse("2026-04-02T00:00:00Z"));
        assertEquals(now, afterIncremental.lastFullPass(), "an incremental pass is not a full one");
    }
}
