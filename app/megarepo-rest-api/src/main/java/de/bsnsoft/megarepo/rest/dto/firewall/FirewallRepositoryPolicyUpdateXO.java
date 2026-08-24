package de.bsnsoft.megarepo.rest.dto.firewall;

import de.bsnsoft.megarepo.core.firewall.FirewallFailMode;

import java.util.UUID;

/**
 * Request body for assigning a policy and a fail mode to one repository.
 *
 * <p>Separate from {@code FirewallRepositoryModeUpdateXO}, which sets the mode.
 * The mode is the dangerous call — it is what arms a repository, and it carries a
 * typed confirmation — while assigning a policy to a repository that is merely
 * observing changes nothing anybody can see. Folding both into one body would
 * either put a confirmation prompt in front of a harmless change or take it away
 * from a dangerous one.
 *
 * @param policyId the policy to apply, or null to fall back to the global
 *     default. <b>A repository policy replaces the default; it does not stack on
 *     top of it.</b> The customer confirmed that reading, and it is the only one
 *     an operator can predict: with stacking, "assign a lenient policy" would
 *     still enforce every rule of the strict default underneath
 * @param failMode what happens to a download the firewall cannot judge in time.
 *     Only consulted while the repository is enforcing — there is nothing to fail
 *     open or closed on while it is merely observing
 * @param confirmation required when the repository is currently enforcing and the
 *     change would alter what it denies
 */
public record FirewallRepositoryPolicyUpdateXO(
        UUID policyId, FirewallFailMode failMode, String confirmation) {}
