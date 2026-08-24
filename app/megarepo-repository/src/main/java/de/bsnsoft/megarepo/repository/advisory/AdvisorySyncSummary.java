package de.bsnsoft.megarepo.repository.advisory;

/**
 * Outcome of one sync run of one {@link AdvisorySource}.
 *
 * <p>Returned rather than thrown, because a failing source is an expected
 * operating condition: {@link AdvisoryIngestService#syncAll} has to keep going
 * and report on every source, and the scheduler that will call it needs to see
 * what happened without catching anything.
 *
 * @param source the source's {@link AdvisorySource#sourceId()}
 * @param succeeded false when the source or the persistence failed; the failure
 *     is also written to {@code advisory_sync_state}
 * @param batches how many batches were fetched and persisted
 * @param ingested advisories written or updated
 * @param skipped advisories left untouched because a more precise source already
 *     owns the id; see {@link AdvisoryIngestService}
 * @param cursor the cursor stored for the next run, may be null
 * @param errorMessage null when {@code succeeded}
 */
public record AdvisorySyncSummary(
        String source,
        boolean succeeded,
        int batches,
        int ingested,
        int skipped,
        String cursor,
        String errorMessage) {}
