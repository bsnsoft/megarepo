package de.bsnsoft.megarepo.rest.dto.firewall;

import java.util.List;

/**
 * One consistent snapshot of "what is this firewall doing right now".
 *
 * <p>The switch, the per-repository states and their summary come from a single
 * read and travel together on purpose. Composed from separate calls, a client
 * could pair a stale switch with fresh modes and paint the exact banner this
 * page exists to prevent — "protected" over an instance that blocks nothing.
 *
 * @param violationWindowDays the window {@link FirewallRepositoryStateXO#violations()}
 *     counts over, so the client can label the number instead of guessing
 * @param summary how many repositories are in each effective state — the banner
 *     reads from here rather than recounting the list
 */
public record FirewallOverviewXO(
        FirewallEnforcementXO enforcement,
        int violationWindowDays,
        FirewallStateSummaryXO summary,
        List<FirewallRepositoryStateXO> repositories) {}
