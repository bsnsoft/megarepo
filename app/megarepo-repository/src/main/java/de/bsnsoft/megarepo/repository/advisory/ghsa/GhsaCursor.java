package de.bsnsoft.megarepo.repository.advisory.ghsa;

import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * The resume token this source stores in {@code advisory_sync_state.cursor}.
 *
 * <p>Text form: {@code v1|<since>|<after>}, either field possibly empty.
 * <ul>
 *   <li>{@code since} — an ISO instant. Every following run asks GitHub only for
 *       advisories updated at or after it ({@code ?updated=>=…}), so a completed initial
 *       import is never repeated.</li>
 *   <li>{@code after} — GitHub's opaque pagination token from the {@code Link} header,
 *       set while a run is still walking pages. Present means "resume mid-import";
 *       {@code since} then stays frozen at the window this import started with, so
 *       GitHub's ascending {@code updated} ordering stays stable across calls.</li>
 * </ul>
 *
 * <p>The pipe separator is safe: GitHub's cursors are base64-ish and instants never
 * contain one, and splitting with a limit keeps any surprise character in {@code after}
 * intact.
 *
 * <p>Two further values are written as cursors and parse back to {@link #start()}:
 * {@link #DISABLED_NO_TOKEN} and {@link #DISABLED_OFF}. They exist so that an operator
 * looking at {@code advisory_sync_state} sees <em>why</em> GHSA contributes nothing,
 * rather than an empty row that could equally mean "never ran". Since they parse as
 * "start from the beginning", configuring a token later resumes with a clean full import.
 *
 * @param since window start for the next request, null on a first-ever run
 * @param after GitHub pagination token, null when not mid-import
 */
record GhsaCursor(Instant since, String after) {

    /** Written when no token is configured — the source produced nothing on purpose. */
    static final String DISABLED_NO_TOKEN = "disabled:no-token";

    /** Written when {@code megarepo.firewall.ghsa.enabled=false}. */
    static final String DISABLED_OFF = "disabled:switched-off";

    private static final String PREFIX = "v1|";

    static GhsaCursor start() {
        return new GhsaCursor(null, null);
    }

    /**
     * Parses a persisted cursor. Anything unrecognised — a disabled marker, a cursor
     * from an older format, garbage — yields {@link #start()}: re-importing is slow but
     * correct, whereas failing here would wedge the source until an operator clears the
     * column by hand.
     */
    static GhsaCursor parse(String raw) {
        if (raw == null || raw.isBlank() || !raw.startsWith(PREFIX)) {
            return start();
        }
        String[] parts = raw.substring(PREFIX.length()).split("\\|", 2);
        Instant since = null;
        if (!parts[0].isBlank()) {
            try {
                since = Instant.parse(parts[0].trim());
            } catch (DateTimeParseException e) {
                return start();
            }
        }
        String after = parts.length > 1 && !parts[1].isBlank() ? parts[1] : null;
        return new GhsaCursor(since, after);
    }

    /** The text form persisted in {@code advisory_sync_state.cursor}. */
    String text() {
        return PREFIX + (since == null ? "" : since.toString()) + "|" + (after == null ? "" : after);
    }

    /** Whether this cursor points into the middle of an import. */
    boolean midImport() {
        return after != null;
    }
}
