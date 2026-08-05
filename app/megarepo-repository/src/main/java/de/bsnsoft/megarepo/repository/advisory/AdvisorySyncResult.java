package de.bsnsoft.megarepo.repository.advisory;

import java.util.List;

/**
 * One slice of an incremental advisory sync.
 *
 * <p>Sources return a batch rather than everything at once — OSV's full export is
 * gigabytes and GHSA pages its GraphQL results. The caller persists the batch, stores
 * {@code nextCursor} in {@code advisory_sync_state}, and calls again while
 * {@code complete} is false.
 *
 * <p>{@code nextCursor} is opaque: only the emitting source interprets it. A page number,
 * a timestamp, an ETag — whatever that source needs to resume. It is persisted as text.
 *
 * @param advisories the advisories in this batch, never null, may be empty
 * @param nextCursor resume token for the following call; null when there is nothing to resume
 * @param complete true when this batch was the last one for now
 */
public record AdvisorySyncResult(
        List<NormalizedAdvisory> advisories, String nextCursor, boolean complete) {

    /** An empty, finished result — for sources that have nothing new to report. */
    public static AdvisorySyncResult empty() {
        return new AdvisorySyncResult(List.of(), null, true);
    }
}
