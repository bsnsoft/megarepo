package de.bsnsoft.megarepo.repository.advisory;

/**
 * A source of vulnerability advisories.
 *
 * <p>Implementations pull from upstream and normalise into {@link NormalizedAdvisory}; they
 * do not persist anything themselves and they never decide policy. NVD, OSV.dev and GitHub
 * Advisories each provide one implementation, collected by Spring.
 *
 * <p>Sources are only ever called from background sync, never from a request thread — the
 * local {@code advisory} table is the request-path fast path. Blocking here is therefore
 * acceptable; blocking a download is not.
 *
 * <p>All outbound HTTP must go through the configured {@code megarepo.outbound-proxy}
 * settings, like every other outbound call in this codebase.
 */
public interface AdvisorySource {

    /**
     * Stable identifier written to {@code advisory.source} and used as the key in
     * {@code advisory_sync_state}. Must be constant across restarts and unique per source:
     * {@code NVD}, {@code OSV}, {@code GHSA}.
     */
    String sourceId();

    /**
     * Fetches the next batch of advisories.
     *
     * @param cursor the {@code nextCursor} of the previous call, or null on the first run
     *     (or after a reset) — meaning "start from the beginning"
     * @return the batch plus the cursor to resume from; never null
     * @throws AdvisorySyncException when upstream is unreachable or returns something
     *     unusable. The caller records the failure in {@code advisory_sync_state} and
     *     retries later; a failing source must never take the whole sync down.
     */
    AdvisorySyncResult sync(String cursor) throws AdvisorySyncException;
}
