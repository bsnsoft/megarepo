package de.bsnsoft.megarepo.repository.advisory.osv;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.bsnsoft.megarepo.repository.advisory.AdvisorySource;
import de.bsnsoft.megarepo.repository.advisory.AdvisorySyncException;
import de.bsnsoft.megarepo.repository.advisory.AdvisorySyncResult;
import de.bsnsoft.megarepo.repository.advisory.NormalizedAdvisory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Mirrors OSV.dev into the local advisory store.
 *
 * <h2>Why the bulk export and not the query API</h2>
 *
 * <p>OSV offers two ways in, and only one of them can build a mirror.
 *
 * <p>{@code api.osv.dev/v1/query} answers "what is wrong with <em>this</em> package" — it
 * is a lookup, and it has no enumeration endpoint. Building the local table from it would
 * mean one request per component MegaRepo has ever seen, on a feed that changes daily, and
 * it would answer nothing at all for a package that has not been downloaded yet. That
 * breaks the one property the firewall is built around: the request path must reach a
 * verdict from local data, including for a component it is seeing for the first time. A
 * per-request call to OSV would put a third-party outage on the critical path of every
 * download, which is precisely what the customer's proxy-only egress rules exist to
 * prevent.
 *
 * <p>The bulk exports at {@code osv-vulnerabilities.storage.googleapis.com} are the other
 * way: one ZIP of one JSON file per advisory, published per ecosystem. Complete, no rate
 * limit, no API key. The cost is size — the combined export is gigabytes — and the fix for
 * that is to never ask for the combined one. MegaRepo hosts four package formats, so it
 * pulls four archives and ignores the twenty-odd Linux-distribution ecosystems that make
 * up most of the volume.
 *
 * <p>The remaining cost is that the export has no delta: every pass downloads the whole
 * archive. Two things keep that from turning into a daily rewrite of the advisory table.
 * One archive is fetched per {@code sync()} call, so a failure costs one ecosystem rather
 * than the run; and the newest {@code modified} timestamp per ecosystem is carried in the
 * cursor, so a steady-state pass hands the caller only the records that actually changed.
 * Periodically the watermark is ignored and everything is re-emitted, because a watermark
 * only moves forward and upstream is free to backfill. See {@link OsvSyncCursor}.
 *
 * <h2>What it contributes that NVD cannot</h2>
 *
 * <p>OSV is purl-native — it names {@code com.acme:util}, not a guessed CPE product — and
 * it carries the malicious-package feed ({@code MAL-} ids) that NVD does not publish at
 * all. For Phase 1 that feed is the sole signal behind {@code KNOWN_MALICIOUS}, which the
 * customer signed off on; findings therefore have to stay labelled with their source and
 * this class fills {@link NormalizedAdvisory#source()} with {@link #SOURCE_ID} for every
 * record so a later second source can be told apart.
 *
 * <h2>Failure behaviour</h2>
 *
 * <p>Only genuine unreachability throws {@link AdvisorySyncException}. A record with an
 * unknown ecosystem, a broken range or unparseable JSON is counted on {@link OsvSyncStats}
 * and skipped, because an advisory feed is external input and one bad record must not cost
 * the day's sync.
 */
@Component
@ConditionalOnProperty(
        prefix = "megarepo.firewall.advisory.osv",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class OsvAdvisorySource implements AdvisorySource {

    private static final Logger log = LoggerFactory.getLogger(OsvAdvisorySource.class);

    /** Written to {@code advisory.source} and keyed on in {@code advisory_sync_state}. */
    public static final String SOURCE_ID = "OSV";

    /** The archives pulled, in the order the cursor indexes them. */
    static final List<OsvEcosystem> ECOSYSTEMS = List.of(OsvEcosystem.values());

    /**
     * Slack subtracted from a watermark before filtering. Publication and modification
     * timestamps are written by the contributing databases, not by a single clock, and
     * re-emitting a handful of records is free next to missing one.
     */
    static final Duration WATERMARK_OVERLAP = Duration.ofHours(24);

    private final OsvArchiveSource archiveSource;
    private final ObjectMapper objectMapper;
    private final OsvRecordParser parser;
    private final int maxBatchSize;
    private final int maxEntryBytes;
    private final Duration fullRefreshInterval;

    public OsvAdvisorySource(
            OsvArchiveSource archiveSource,
            ObjectMapper objectMapper,
            @Value("${megarepo.firewall.advisory.osv.max-batch-size:50000}") int maxBatchSize,
            @Value("${megarepo.firewall.advisory.osv.max-entry-bytes:2097152}") int maxEntryBytes,
            @Value("${megarepo.firewall.advisory.osv.full-refresh-interval:7d}") Duration fullRefreshInterval) {
        this.archiveSource = archiveSource;
        this.objectMapper = objectMapper;
        this.parser = new OsvRecordParser(SOURCE_ID);
        this.maxBatchSize = Math.max(1, maxBatchSize);
        this.maxEntryBytes = Math.max(1024, maxEntryBytes);
        this.fullRefreshInterval = fullRefreshInterval;
    }

    @Override
    public String sourceId() {
        return SOURCE_ID;
    }

    @Override
    public AdvisorySyncResult sync(String cursor) throws AdvisorySyncException {
        OsvSyncCursor position = OsvSyncCursor.parse(cursor, objectMapper);
        Instant now = Instant.now();

        if (position.ecosystemIndex() >= ECOSYSTEMS.size()) {
            // A cursor from an older ecosystem list, or one that ran off the end.
            position = position.startingPass(isFullRefreshDue(position, now));
        } else if (position.ecosystemIndex() == 0 && position.entryOffset() == 0) {
            position = position.startingPass(isFullRefreshDue(position, now));
        }

        OsvEcosystem ecosystem = ECOSYSTEMS.get(position.ecosystemIndex());
        Instant watermark = position.fullPass() ? null : effectiveWatermark(position, ecosystem);

        OsvSyncStats stats = new OsvSyncStats();
        Batch batch = readArchive(ecosystem, position.entryOffset(), watermark, stats);

        OsvSyncCursor next = position.withCurrentMax(batch.newestModified());
        boolean complete;
        if (batch.truncated()) {
            next = next.resumingAt(batch.nextOffset());
            complete = false;
            log.info(
                    "OSV {} batch: {} — more to come, resuming at entry {}",
                    ecosystem.osvName(),
                    stats,
                    batch.nextOffset());
        } else {
            next = next.completedEcosystem(ecosystem);
            complete = next.ecosystemIndex() >= ECOSYSTEMS.size();
            if (complete) {
                next = next.completedPass(now);
            }
            log.info(
                    "OSV {} done ({} pass): {}",
                    ecosystem.osvName(),
                    position.fullPass() ? "full" : "incremental",
                    stats);
        }

        return new AdvisorySyncResult(batch.advisories(), next.format(objectMapper), complete);
    }

    private boolean isFullRefreshDue(OsvSyncCursor position, Instant now) {
        Instant last = position.lastFullPass();
        return last == null || fullRefreshInterval.isZero()
                || !last.plus(fullRefreshInterval).isAfter(now);
    }

    private Instant effectiveWatermark(OsvSyncCursor position, OsvEcosystem ecosystem) {
        Instant since = position.sinceFor(ecosystem);
        return since == null ? null : since.minus(WATERMARK_OVERLAP);
    }

    /** One archive's worth of work, up to the batch cap. */
    private record Batch(
            List<NormalizedAdvisory> advisories,
            int nextOffset,
            boolean truncated,
            Instant newestModified) {}

    /**
     * Streams one ecosystem archive, skipping the first {@code offset} JSON entries.
     *
     * <p>The offset counts JSON entries only, so a directory or a stray file added to the
     * archive cannot shift it. Entry order within an archive is the order the ZIP stores
     * them, which is stable for a given published file; if upstream replaces the archive
     * between two calls of a truncated read, the offset lands somewhere slightly different
     * and a few records are re-read or wait for the next pass. Ingest is an upsert, so
     * re-reading is free, and the periodic full pass is the backstop for the other case.
     */
    private Batch readArchive(
            OsvEcosystem ecosystem, int offset, Instant watermark, OsvSyncStats stats)
            throws AdvisorySyncException {

        List<NormalizedAdvisory> advisories = new ArrayList<>();
        Instant newestModified = null;
        int entryIndex = 0;
        int nextOffset = offset;
        boolean truncated = false;

        try (InputStream raw = archiveSource.openArchive(ecosystem);
                ZipInputStream zip = new ZipInputStream(new BufferedInputStream(raw))) {

            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory() || !entry.getName().toLowerCase(Locale.ROOT).endsWith(".json")) {
                    continue;
                }
                int current = entryIndex++;
                if (current < offset) {
                    continue;
                }
                nextOffset = current + 1;

                byte[] payload = readBounded(zip);
                if (payload == null) {
                    stats.oversizedEntries++;
                    continue;
                }
                stats.entriesRead++;

                JsonNode root;
                try {
                    root = objectMapper.readTree(payload);
                } catch (IOException e) {
                    stats.malformedJson++;
                    log.debug("Skipping unparseable OSV entry {}: {}", entry.getName(), e.toString());
                    continue;
                }

                Optional<NormalizedAdvisory> advisory;
                try {
                    advisory = parser.parse(root, stats);
                } catch (RuntimeException e) {
                    // The parser is written not to throw; if it ever does, one record is
                    // still a cheaper price than the sync.
                    stats.unusableRecord++;
                    log.warn("Skipping OSV entry {} after an unexpected parse failure",
                            entry.getName(), e);
                    continue;
                }
                if (advisory.isEmpty()) {
                    continue;
                }

                NormalizedAdvisory normalized = advisory.get();
                newestModified = newer(newestModified, normalized.modified());

                if (watermark != null
                        && normalized.modified() != null
                        && !normalized.modified().isAfter(watermark)) {
                    stats.unchanged++;
                    continue;
                }

                advisories.add(normalized);
                stats.emitted++;
                if (advisories.size() >= maxBatchSize) {
                    truncated = true;
                    break;
                }
            }
        } catch (IOException e) {
            // A truncated download or a corrupt archive: upstream gave us something
            // unusable, which is exactly the contract's AdvisorySyncException case.
            throw new AdvisorySyncException(
                    "Could not read the OSV %s export".formatted(ecosystem.osvName()), e);
        }

        return new Batch(advisories, nextOffset, truncated, newestModified);
    }

    /**
     * Reads one archive entry, refusing anything past the per-entry cap.
     *
     * <p>A single OSV record is kilobytes. The cap is what stops a malformed or hostile
     * archive from being decompressed straight into the heap, and returning null rather
     * than throwing keeps the rest of the archive readable.
     */
    private byte[] readBounded(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(8192);
        byte[] chunk = new byte[8192];
        int total = 0;
        int read;
        while ((read = in.read(chunk)) != -1) {
            total += read;
            if (total > maxEntryBytes) {
                return null;
            }
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    private static Instant newer(Instant current, Instant candidate) {
        if (candidate == null) {
            return current;
        }
        return current == null || candidate.isAfter(current) ? candidate : current;
    }
}
