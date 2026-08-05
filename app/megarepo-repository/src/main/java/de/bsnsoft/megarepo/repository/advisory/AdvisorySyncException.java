package de.bsnsoft.megarepo.repository.advisory;

/**
 * Thrown by an {@link AdvisorySource} when a sync cannot complete — upstream unreachable,
 * rate-limited, or answering with something that cannot be parsed.
 *
 * <p>Checked on purpose: a failing advisory source is an expected operating condition, not
 * a bug, and every caller has to decide what to record in {@code advisory_sync_state}.
 */
public class AdvisorySyncException extends Exception {

    public AdvisorySyncException(String message) {
        super(message);
    }

    public AdvisorySyncException(String message, Throwable cause) {
        super(message, cause);
    }
}
