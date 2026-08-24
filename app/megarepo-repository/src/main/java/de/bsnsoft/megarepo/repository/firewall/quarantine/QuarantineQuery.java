package de.bsnsoft.megarepo.repository.firewall.quarantine;

import de.bsnsoft.megarepo.core.firewall.FirewallQuarantineReason;
import de.bsnsoft.megarepo.core.firewall.FirewallQuarantineState;

import java.util.UUID;

/**
 * Filters for the quarantine queue.
 *
 * <p>All fields nullable, all meaning "do not filter on this". A record rather
 * than four overloads of {@code queue(...)} so that adding a filter is not a
 * change to every caller.
 *
 * @param state only entries in this state; null for all
 * @param repositoryId only this repository; null for all
 * @param reason only entries held for this reason; null for all
 * @param componentKeyContains substring match on the component key, for the
 *     search box. Case-insensitive; null or blank for no search
 */
public record QuarantineQuery(
        FirewallQuarantineState state,
        UUID repositoryId,
        FirewallQuarantineReason reason,
        String componentKeyContains) {

    /** Everything, unfiltered. */
    public static QuarantineQuery all() {
        return new QuarantineQuery(null, null, null, null);
    }

    /** The default queue view: what is still being held. */
    public static QuarantineQuery held() {
        return new QuarantineQuery(FirewallQuarantineState.QUARANTINED, null, null, null);
    }

    /** The same, narrowed to one repository. */
    public static QuarantineQuery heldIn(UUID repositoryId) {
        return new QuarantineQuery(FirewallQuarantineState.QUARANTINED, repositoryId, null, null);
    }
}
