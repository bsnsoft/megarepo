package de.bsnsoft.megarepo.repository.advisory.osv;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Where the OSV mirror stands, encoded for {@code advisory_sync_state.cursor}.
 *
 * <p>OSV has no "give me everything since X" endpoint, so the position has to be
 * reconstructed from what was already read. Four things are needed for that, and they are
 * exactly the fields below:
 *
 * <ul>
 *   <li>{@code eco} — which ecosystem archive is being read. One archive per
 *       {@code sync()} call, so a sync interrupted after Maven resumes at npm instead of
 *       downloading Maven again.</li>
 *   <li>{@code off} — how many JSON entries of that archive were already handed on.
 *       Non-zero only when an archive exceeded the per-batch cap; resuming re-opens the
 *       archive and skips ahead, which costs a download but bounds heap.</li>
 *   <li>{@code since} — the newest {@code modified} seen per ecosystem on the last
 *       completed pass. The next pass still downloads the archive (there is no delta
 *       export) but only hands on records newer than this, so a steady-state sync writes
 *       tens of advisories instead of tens of thousands.</li>
 *   <li>{@code full} / {@code fp} — when the last unfiltered pass ran, and whether the
 *       current one is unfiltered. A watermark can only ever move forward, so a record
 *       that upstream backfills with an old {@code modified} would be invisible for
 *       ever; a periodic full pass is the floor under that.</li>
 * </ul>
 *
 * <p>Keys are short and the whole thing is JSON because the column is
 * {@code VARCHAR(500)}: four ecosystems' worth of watermarks fit with room to spare, and
 * {@link #format(ObjectMapper)} drops the watermarks rather than overflow the column.
 *
 * <p>{@code since} is keyed by ecosystem <em>name</em>, not by index, so reordering
 * {@link OsvEcosystem} cannot silently attach a watermark to the wrong archive.
 *
 * <p>Anything unreadable — a truncated string, a cursor from a future version, a hand-edit
 * — parses back to {@link #initial()}. Starting over costs one full pass; guessing at a
 * half-understood cursor costs correctness.
 */
public record OsvSyncCursor(
        int ecosystemIndex,
        int entryOffset,
        Map<String, Instant> since,
        Instant currentMax,
        Instant lastFullPass,
        boolean fullPass) {

    private static final Logger log = LoggerFactory.getLogger(OsvSyncCursor.class);

    /** Bumped whenever the meaning of a field changes; older cursors then reset. */
    static final int VERSION = 1;

    /** Leaves headroom under {@code advisory_sync_state.cursor VARCHAR(500)}. */
    static final int MAX_LENGTH = 480;

    public OsvSyncCursor {
        since = since == null ? Map.of() : Map.copyOf(since);
    }

    /** First run, or after an operator resets the row: start at the first ecosystem. */
    public static OsvSyncCursor initial() {
        return new OsvSyncCursor(0, 0, Map.of(), null, null, true);
    }

    public static OsvSyncCursor parse(String cursor, ObjectMapper mapper) {
        if (cursor == null || cursor.isBlank()) {
            return initial();
        }
        try {
            JsonNode root = mapper.readTree(cursor);
            if (!root.isObject() || root.path("v").asInt(-1) != VERSION) {
                log.warn("Ignoring OSV cursor of unknown version — restarting from the first ecosystem");
                return initial();
            }
            // Read by ecosystem rather than by whatever keys the JSON happens to carry, so
            // a watermark left behind by an ecosystem MegaRepo no longer mirrors simply
            // disappears instead of taking up room in the column for ever.
            Map<String, Instant> since = new LinkedHashMap<>();
            JsonNode sinceNode = root.path("since");
            for (OsvEcosystem ecosystem : OsvEcosystem.values()) {
                Instant value =
                        OsvRecordParser.parseInstant(sinceNode.path(ecosystem.osvName()).asText(null));
                if (value != null) {
                    since.put(ecosystem.osvName(), value);
                }
            }
            return new OsvSyncCursor(
                    Math.max(0, root.path("eco").asInt(0)),
                    Math.max(0, root.path("off").asInt(0)),
                    since,
                    OsvRecordParser.parseInstant(root.path("cur").asText(null)),
                    OsvRecordParser.parseInstant(root.path("full").asText(null)),
                    root.path("fp").asBoolean(false));
        } catch (Exception e) {
            log.warn("Unreadable OSV cursor — restarting from the first ecosystem: {}", e.toString());
            return initial();
        }
    }

    /** Serialises for the sync-state column, shedding watermarks before overflowing it. */
    public String format(ObjectMapper mapper) {
        String full = write(mapper, true);
        if (full != null && full.length() <= MAX_LENGTH) {
            return full;
        }
        log.warn("OSV cursor exceeds {} chars — dropping watermarks, next pass re-reads everything",
                MAX_LENGTH);
        String reduced = write(mapper, false);
        return reduced == null ? null : reduced;
    }

    private String write(ObjectMapper mapper, boolean withWatermarks) {
        try {
            ObjectNode node = mapper.createObjectNode();
            node.put("v", VERSION);
            node.put("eco", ecosystemIndex);
            node.put("off", entryOffset);
            node.put("fp", fullPass);
            if (lastFullPass != null) {
                node.put("full", lastFullPass.toString());
            }
            if (withWatermarks) {
                if (currentMax != null) {
                    node.put("cur", currentMax.toString());
                }
                if (!since.isEmpty()) {
                    ObjectNode sinceNode = node.putObject("since");
                    since.forEach((key, value) -> sinceNode.put(key, value.toString()));
                }
            }
            return mapper.writeValueAsString(node);
        } catch (Exception e) {
            log.warn("Could not serialise the OSV cursor: {}", e.toString());
            return null;
        }
    }

    /** The watermark for an ecosystem, or null when it has never completed a pass. */
    public Instant sinceFor(OsvEcosystem ecosystem) {
        return since.get(ecosystem.osvName());
    }

    /** Same position, with the running newest-{@code modified} advanced. */
    public OsvSyncCursor withCurrentMax(Instant candidate) {
        if (candidate == null || (currentMax != null && !candidate.isAfter(currentMax))) {
            return this;
        }
        return new OsvSyncCursor(ecosystemIndex, entryOffset, since, candidate, lastFullPass, fullPass);
    }

    /** Mid-archive: same ecosystem, resume at {@code offset}. */
    public OsvSyncCursor resumingAt(int offset) {
        return new OsvSyncCursor(ecosystemIndex, offset, since, currentMax, lastFullPass, fullPass);
    }

    /**
     * Archive finished: promote the running maximum into the ecosystem's watermark and
     * move to the next archive.
     */
    public OsvSyncCursor completedEcosystem(OsvEcosystem ecosystem) {
        Map<String, Instant> updated = new LinkedHashMap<>(since);
        Instant previous = updated.get(ecosystem.osvName());
        if (currentMax != null && (previous == null || currentMax.isAfter(previous))) {
            updated.put(ecosystem.osvName(), currentMax);
        }
        return new OsvSyncCursor(ecosystemIndex + 1, 0, updated, null, lastFullPass, fullPass);
    }

    /**
     * All archives done: rewind to the first ecosystem and record the pass, so the next
     * call starts a fresh — and normally filtered — pass.
     */
    public OsvSyncCursor completedPass(Instant now) {
        return new OsvSyncCursor(0, 0, since, null, fullPass ? now : lastFullPass, false);
    }

    /** Marks the pass that is about to start as filtered or unfiltered. */
    public OsvSyncCursor startingPass(boolean full) {
        return new OsvSyncCursor(0, 0, since, null, lastFullPass, full);
    }
}
