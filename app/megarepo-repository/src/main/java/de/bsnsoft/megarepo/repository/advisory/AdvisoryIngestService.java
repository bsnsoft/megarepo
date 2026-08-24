package de.bsnsoft.megarepo.repository.advisory;

import de.bsnsoft.megarepo.database.entity.AdvisoryAffectedEntity;
import de.bsnsoft.megarepo.database.entity.AdvisoryEntity;
import de.bsnsoft.megarepo.database.entity.AdvisorySyncStateEntity;
import de.bsnsoft.megarepo.database.repository.AdvisoryAffectedJpaRepository;
import de.bsnsoft.megarepo.database.repository.AdvisoryJpaRepository;
import de.bsnsoft.megarepo.database.repository.AdvisorySyncStateJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Drives every {@link AdvisorySource} and persists what they return.
 *
 * <h2>Isolation between sources</h2>
 *
 * {@link #syncAll(Collection)} runs the sources one after another and each one
 * inside its own guard. An {@link AdvisorySyncException} — or any runtime
 * failure — is recorded in {@code advisory_sync_state} for that source and the
 * loop continues, so GitHub being rate-limited never stops OSV from updating.
 * Nothing propagates out of this class.
 *
 * <h2>Idempotency</h2>
 *
 * Running the same sync twice must not double anything. Two different
 * mechanisms are needed because the two tables have different keys:
 *
 * <ul>
 *   <li>{@code advisory} is keyed by the upstream id, so a re-sync updates the
 *       existing row in place. {@code created_at} survives, {@code updated_at}
 *       moves.</li>
 *   <li>{@code advisory_affected} has a surrogate UUID key and no natural
 *       unique constraint, so {@code saveAll} would happily insert a second copy
 *       of every range. Ranges are therefore <em>replaced</em>: all rows of the
 *       advisories in the batch are deleted, then re-inserted. This also handles
 *       an upstream advisory that <em>narrows</em> its affected set, which an
 *       upsert could not — the withdrawn range has to disappear, otherwise a
 *       component stays flagged forever.</li>
 * </ul>
 *
 * <h2>The same id from two sources</h2>
 *
 * {@code CVE-2021-44228} arrives from NVD and, for ecosystems OSV covers, from
 * OSV as well. The primary key already prevents duplicate rows, but it does not
 * say whose data should win — and "whoever synced last" would make the source
 * label, the severity and the affected ranges of an advisory depend on
 * scheduling. A fixed precedence decides instead: a source may write an
 * advisory only if it is at least as precise as the source that currently owns
 * the row.
 *
 * <p>Precision here means purl-native versus CPE-derived. GHSA publishes purls
 * and owns the {@code GHSA-} id space; OSV publishes purls and aggregates;
 * NVD publishes CPEs, which {@link CpePurlTranslator} can only approximate. So
 * an OSV row for a CVE is never overwritten by NVD's CPE-shaped version of the
 * same CVE, while NVD refreshing its own rows works normally.
 *
 * <h2>Transaction boundaries</h2>
 *
 * Explicit, via {@link TransactionTemplate}, rather than {@code @Transactional}.
 * The sync loop calls the per-batch unit of work from inside this same bean, and
 * Spring's proxy does not intercept self-invocation — the reason
 * {@code NvdIngestService} had to be split out of {@code NvdSyncService} in the
 * V8 code. One transaction per batch keeps a failure from discarding batches
 * that already succeeded.
 */
@Service
public class AdvisoryIngestService {

    /**
     * Upper bound on batches per source and run. A source whose {@code complete}
     * flag never turns true — a paging bug, a cursor that does not advance —
     * would otherwise loop forever inside a scheduled job.
     */
    static final int MAX_BATCHES_PER_RUN = 10_000;

    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_IDLE = "IDLE";
    private static final String STATUS_ERROR = "ERROR";

    /** {@code advisory_sync_state.cursor} is VARCHAR(500). */
    private static final int MAX_CURSOR_LENGTH = 500;

    private static final int MAX_ADVISORY_ID_LENGTH = 100;
    private static final int MAX_SOURCE_LENGTH = 30;
    private static final int MAX_SEVERITY_LENGTH = 20;
    private static final int MAX_CVSS_VECTOR_LENGTH = 200;
    private static final int MAX_PURL_TYPE_LENGTH = 50;
    private static final int MAX_PURL_PART_LENGTH = 500;
    private static final int MAX_VERSION_RANGE_LENGTH = 1000;
    private static final int MAX_VERSION_BOUND_LENGTH = 200;

    private static final Logger log = LoggerFactory.getLogger(AdvisoryIngestService.class);

    private final AdvisoryJpaRepository advisories;
    private final AdvisoryAffectedJpaRepository affected;
    private final AdvisorySyncStateJpaRepository syncStates;
    private final TransactionTemplate transactionTemplate;
    private final int maxBatchesPerRun;

    @Autowired
    public AdvisoryIngestService(
            AdvisoryJpaRepository advisories,
            AdvisoryAffectedJpaRepository affected,
            AdvisorySyncStateJpaRepository syncStates,
            PlatformTransactionManager transactionManager) {
        this(advisories, affected, syncStates, transactionManager, MAX_BATCHES_PER_RUN);
    }

    /** Visible for tests, which need a batch ceiling they can reach. */
    AdvisoryIngestService(
            AdvisoryJpaRepository advisories,
            AdvisoryAffectedJpaRepository affected,
            AdvisorySyncStateJpaRepository syncStates,
            PlatformTransactionManager transactionManager,
            int maxBatchesPerRun) {
        this.advisories = advisories;
        this.affected = affected;
        this.syncStates = syncStates;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.maxBatchesPerRun = maxBatchesPerRun;
    }

    /**
     * Syncs every source, one after another.
     *
     * @return one summary per source, in the given order; never throws
     */
    public List<AdvisorySyncSummary> syncAll(Collection<AdvisorySource> sources) {
        List<AdvisorySyncSummary> summaries = new ArrayList<>();
        for (AdvisorySource source : sources) {
            summaries.add(sync(source));
        }
        return summaries;
    }

    /**
     * Syncs one source to completion, persisting each batch as it arrives and
     * keeping {@code advisory_sync_state} current.
     *
     * <p>Never throws. A failure leaves the state row at {@code ERROR} with the
     * message, and — importantly — leaves the last successfully persisted cursor
     * in place, so the next run resumes instead of starting over.
     */
    public AdvisorySyncSummary sync(AdvisorySource source) {
        String sourceId = source.sourceId();
        int batches = 0;
        int ingested = 0;
        int skipped = 0;
        String cursor;

        try {
            cursor = markRunning(sourceId);
        } catch (RuntimeException e) {
            log.error("Advisory sync for {} could not start", sourceId, e);
            return new AdvisorySyncSummary(sourceId, false, 0, 0, 0, null, describe(e));
        }

        while (batches < maxBatchesPerRun) {
            AdvisorySyncResult result;
            try {
                result = source.sync(cursor);
            } catch (AdvisorySyncException | RuntimeException e) {
                // Exactly the case the AdvisorySource contract calls out: record
                // it and let the other sources carry on.
                log.warn("Advisory source {} failed after {} batch(es)", sourceId, batches, e);
                markFailed(sourceId, describe(e));
                return new AdvisorySyncSummary(
                        sourceId, false, batches, ingested, skipped, cursor, describe(e));
            }

            batches++;
            try {
                IngestStats stats = ingest(sourceId, result.advisories());
                ingested += stats.ingested();
                skipped += stats.skipped();

                String nextCursor = result.nextCursor();
                if (nextCursor != null) {
                    if (nextCursor.length() > MAX_CURSOR_LENGTH) {
                        // Truncating would corrupt the resume point silently,
                        // which is worse than stopping: the source would look
                        // healthy while re-reading or skipping data every run.
                        String message = "Source %s returned a cursor of %d characters; the maximum is %d"
                                .formatted(sourceId, nextCursor.length(), MAX_CURSOR_LENGTH);
                        log.error(message);
                        markFailed(sourceId, message);
                        return new AdvisorySyncSummary(
                                sourceId, false, batches, ingested, skipped, cursor, message);
                    }
                    cursor = nextCursor;
                    storeCursor(sourceId, cursor);
                }
                // A null nextCursor keeps the stored one. AdvisorySyncResult.empty()
                // carries null and means "nothing new"; clearing the cursor on it
                // would make every source that has caught up re-read its whole
                // feed on the following run.
            } catch (RuntimeException e) {
                log.error("Persisting advisories from {} failed after {} batch(es)", sourceId, batches, e);
                markFailed(sourceId, describe(e));
                return new AdvisorySyncSummary(
                        sourceId, false, batches, ingested, skipped, cursor, describe(e));
            }

            if (result.complete()) {
                break;
            }
        }

        if (batches >= maxBatchesPerRun) {
            log.warn("Advisory source {} did not report completion within {} batches — stopping this run",
                    sourceId, maxBatchesPerRun);
        }

        markSuccess(sourceId, cursor);
        log.info("Advisory sync {} finished: {} batch(es), {} ingested, {} skipped",
                sourceId, batches, ingested, skipped);
        return new AdvisorySyncSummary(sourceId, true, batches, ingested, skipped, cursor, null);
    }

    /**
     * Persists one batch in a single transaction, idempotently.
     *
     * <p>Public so a caller that already owns the sync loop (a scheduler, a
     * test) can persist a batch directly.
     */
    public IngestStats ingest(String sourceId, List<NormalizedAdvisory> batch) {
        if (batch == null || batch.isEmpty()) {
            return new IngestStats(0, 0);
        }
        IngestStats stats = transactionTemplate.execute(status -> persist(sourceId, batch));
        return stats == null ? new IngestStats(0, 0) : stats;
    }

    private IngestStats persist(String sourceId, List<NormalizedAdvisory> batch) {
        // Last occurrence wins if a source repeats an id inside one batch —
        // upstream feeds do that when an advisory is amended mid-page.
        Map<String, NormalizedAdvisory> byId = new LinkedHashMap<>();
        int skipped = 0;
        for (NormalizedAdvisory advisory : batch) {
            String id = trimToNull(advisory.id());
            if (id == null || id.length() > MAX_ADVISORY_ID_LENGTH) {
                log.warn("Skipping advisory from {} with an unusable id '{}'", sourceId, advisory.id());
                skipped++;
                continue;
            }
            byId.put(id, advisory);
        }
        if (byId.isEmpty()) {
            return new IngestStats(0, skipped);
        }

        Map<String, AdvisoryEntity> existing = new LinkedHashMap<>();
        for (AdvisoryEntity entity : advisories.findByIdIn(byId.keySet())) {
            existing.put(entity.getId(), entity);
        }

        Instant now = Instant.now();
        List<AdvisoryEntity> toSave = new ArrayList<>(byId.size());
        Set<String> writableIds = new LinkedHashSet<>();

        for (Map.Entry<String, NormalizedAdvisory> entry : byId.entrySet()) {
            String id = entry.getKey();
            NormalizedAdvisory advisory = entry.getValue();
            AdvisoryEntity entity = existing.get(id);

            if (entity != null && !mayOverwrite(sourceId, entity.getSource())) {
                log.debug("Advisory {} stays owned by {}; {} is less precise", id, entity.getSource(), sourceId);
                skipped++;
                continue;
            }
            if (entity == null) {
                entity = new AdvisoryEntity();
                entity.setId(id);
                entity.setCreatedAt(now);
            }
            apply(entity, sourceId, advisory, now);
            toSave.add(entity);
            writableIds.add(id);
        }

        if (writableIds.isEmpty()) {
            return new IngestStats(0, skipped);
        }

        // The advisory rows have to exist before their ranges: advisory_affected
        // has a FK onto advisory(id).
        advisories.saveAll(toSave);
        advisories.flush();

        // Replace rather than upsert — see the class javadoc.
        affected.deleteByAdvisoryIdIn(writableIds);
        affected.flush();

        List<AdvisoryAffectedEntity> ranges = new ArrayList<>();
        for (String id : writableIds) {
            for (NormalizedAffected range : byId.get(id).affected()) {
                toEntity(id, range, sourceId).ifPresent(ranges::add);
            }
        }
        if (!ranges.isEmpty()) {
            affected.saveAll(ranges);
            affected.flush();
        }

        return new IngestStats(writableIds.size(), skipped);
    }

    private static void apply(
            AdvisoryEntity entity, String sourceId, NormalizedAdvisory advisory, Instant now) {
        if (advisory.source() != null && !sourceId.equals(advisory.source())) {
            // advisory_sync_state is keyed by the emitting source, so that id is
            // the one that has to end up in the row; a mismatch is a bug in the
            // source, not a reason to drop the advisory.
            log.warn("Source {} emitted an advisory declaring source '{}' — storing it as {}",
                    sourceId, advisory.source(), sourceId);
        }
        entity.setSource(truncate(sourceId, MAX_SOURCE_LENGTH));
        entity.setSummary(advisory.summary());
        entity.setSeverity(truncate(advisory.severity(), MAX_SEVERITY_LENGTH));
        entity.setCvssScore(advisory.cvssScore());
        entity.setCvssVector(truncate(advisory.cvssVector(), MAX_CVSS_VECTOR_LENGTH));
        entity.setPublished(advisory.published());
        entity.setModified(advisory.modified());
        entity.setWithdrawnAt(advisory.withdrawnAt());
        entity.setUpdatedAt(now);
    }

    /**
     * Turns a normalised range into a row, or drops it.
     *
     * <p>Values that exceed their column are dropped rather than truncated,
     * except {@code version_range}, which is descriptive only. A truncated
     * package name would match the wrong package; a dropped range is one missed
     * match, logged. Both are bad, but only one of them is silently wrong.
     */
    private static Optional<AdvisoryAffectedEntity> toEntity(
            String advisoryId, NormalizedAffected range, String sourceId) {
        String purlType = trimToNull(range.purlType());
        String purlName = trimToNull(range.purlName());
        String purlNamespace = trimToNull(range.purlNamespace());

        if (purlType == null || purlName == null) {
            log.debug("Dropping affected range of {} from {} without purl type or name", advisoryId, sourceId);
            return Optional.empty();
        }
        if (purlType.length() > MAX_PURL_TYPE_LENGTH
                || purlName.length() > MAX_PURL_PART_LENGTH
                || (purlNamespace != null && purlNamespace.length() > MAX_PURL_PART_LENGTH)
                || tooLong(range.introduced())
                || tooLong(range.fixed())
                || tooLong(range.lastAffected())) {
            log.warn("Dropping oversized affected range of {} from {}: {}/{}/{}",
                    advisoryId, sourceId, purlType, purlNamespace, purlName);
            return Optional.empty();
        }

        AdvisoryAffectedEntity entity = new AdvisoryAffectedEntity();
        entity.setAdvisoryId(advisoryId);
        entity.setPurlType(purlType.toLowerCase(Locale.ROOT));
        entity.setPurlNamespace(purlNamespace);
        entity.setPurlName(purlName);
        entity.setVersionRange(truncate(range.versionRange(), MAX_VERSION_RANGE_LENGTH));
        entity.setIntroduced(trimToNull(range.introduced()));
        entity.setFixed(trimToNull(range.fixed()));
        entity.setLastAffected(trimToNull(range.lastAffected()));
        return Optional.of(entity);
    }

    /**
     * Whether {@code incoming} may take ownership of a row currently owned by
     * {@code owner}. Equal precedence is allowed, which is what lets a source
     * refresh its own advisories.
     */
    static boolean mayOverwrite(String incoming, String owner) {
        return precedence(incoming) >= precedence(owner);
    }

    /**
     * How precisely a source identifies packages. Higher wins.
     *
     * <p>Not a quality ranking of the feeds: it ranks the <em>shape</em> of the
     * data. GHSA and OSV publish purls, which map onto MegaRepo's identity
     * without loss; NVD publishes CPEs, which do not. GHSA edges out OSV only
     * because it is the publisher of the id space OSV mirrors.
     */
    private static int precedence(String sourceId) {
        if (sourceId == null) {
            return 0;
        }
        return switch (sourceId.trim().toUpperCase(Locale.ROOT)) {
            case "GHSA" -> 30;
            case "OSV" -> 20;
            case "NVD" -> 10;
            default -> 0;
        };
    }

    private String markRunning(String sourceId) {
        return transactionTemplate.execute(status -> {
            AdvisorySyncStateEntity state = state(sourceId);
            String cursor = state.getCursor();
            state.setStatus(STATUS_RUNNING);
            state.setErrorMessage(null);
            state.setUpdatedAt(Instant.now());
            syncStates.save(state);
            return cursor;
        });
    }

    private void storeCursor(String sourceId, String cursor) {
        transactionTemplate.executeWithoutResult(status -> {
            AdvisorySyncStateEntity state = state(sourceId);
            state.setCursor(cursor);
            state.setUpdatedAt(Instant.now());
            syncStates.save(state);
        });
    }

    private void markSuccess(String sourceId, String cursor) {
        transactionTemplate.executeWithoutResult(status -> {
            Instant now = Instant.now();
            AdvisorySyncStateEntity state = state(sourceId);
            state.setStatus(STATUS_IDLE);
            state.setCursor(cursor);
            state.setLastSuccessAt(now);
            state.setErrorMessage(null);
            state.setUpdatedAt(now);
            syncStates.save(state);
        });
    }

    private void markFailed(String sourceId, String message) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                AdvisorySyncStateEntity state = state(sourceId);
                state.setStatus(STATUS_ERROR);
                // last_success_at and cursor are deliberately left alone: the
                // next run has to resume where the last successful batch ended,
                // and an operator needs to see how stale the data now is.
                state.setErrorMessage(message);
                state.setUpdatedAt(Instant.now());
                syncStates.save(state);
            });
        } catch (RuntimeException e) {
            // Recording the failure failed. Losing the whole run over that would
            // defeat the point of isolating sources from each other.
            log.error("Could not record the sync failure of {} in advisory_sync_state", sourceId, e);
        }
    }

    private AdvisorySyncStateEntity state(String sourceId) {
        return syncStates.findById(sourceId).orElseGet(() -> {
            AdvisorySyncStateEntity created = new AdvisorySyncStateEntity();
            created.setSource(sourceId);
            return created;
        });
    }

    private static boolean tooLong(String bound) {
        return bound != null && bound.trim().length() > MAX_VERSION_BOUND_LENGTH;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String describe(Throwable e) {
        String message = e.getMessage();
        return message == null || message.isBlank()
                ? e.getClass().getSimpleName()
                : e.getClass().getSimpleName() + ": " + message;
    }

    /**
     * @param ingested advisories written or updated
     * @param skipped advisories left alone — unusable id, or owned by a more
     *     precise source
     */
    public record IngestStats(int ingested, int skipped) {}
}
