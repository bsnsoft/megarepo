package de.bsnsoft.megarepo.rest.dto.firewall;

import de.bsnsoft.megarepo.core.firewall.FirewallEffectiveState;
import de.bsnsoft.megarepo.core.firewall.FirewallFailMode;
import de.bsnsoft.megarepo.core.firewall.FirewallMode;

import java.time.Instant;
import java.util.UUID;

/**
 * One repository in the firewall overview: what it is configured to do, and what
 * it is actually doing.
 *
 * @param mode the configured intent, verbatim from
 *     {@code firewall_repository_config}
 * @param effectiveState the configured intent combined with the global
 *     enforcement switch. Clients render <em>this</em>, not {@link #mode()}: a
 *     repository can be configured {@link FirewallMode#QUARANTINE} and block
 *     nothing, and a UI that shows only the mode tells the operator they are
 *     protected when they are not.
 * @param configured false when no {@code firewall_repository_config} row exists
 *     and the mode shown is the instance-wide default
 * @param violations how many violations were recorded for this repository in the
 *     reporting window (see
 *     {@link FirewallOverviewXO#violationWindowDays()}) — the evidence an
 *     operator weighs before arming
 */
public record FirewallRepositoryStateXO(
        UUID repositoryId,
        String repositoryName,
        String format,
        String type,
        FirewallMode mode,
        FirewallFailMode failMode,
        FirewallEffectiveState effectiveState,
        boolean configured,
        long violations,
        Instant updatedAt) {}
