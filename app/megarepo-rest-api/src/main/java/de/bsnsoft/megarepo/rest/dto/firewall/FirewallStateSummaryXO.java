package de.bsnsoft.megarepo.rest.dto.firewall;

/**
 * How many repositories sit in each effective state.
 *
 * @param blocking repositories that can actually refuse a download right now
 * @param quarantineNotEnforced repositories asking for QUARANTINE on an instance
 *     whose enforcement switch is off — configured for protection, delivering
 *     none. The number the operator most needs to see.
 * @param observing repositories in AUDIT: evaluated, recorded, never blocked
 * @param notEvaluated repositories the firewall ignores entirely
 */
public record FirewallStateSummaryXO(
        int blocking, int quarantineNotEnforced, int observing, int notEvaluated) {}
