package de.bsnsoft.megarepo.rest.dto.firewall;

import de.bsnsoft.megarepo.core.firewall.FirewallAction;
import de.bsnsoft.megarepo.core.firewall.FirewallRuleType;
import jakarta.validation.constraints.NotNull;

import java.util.Map;
import java.util.UUID;

/**
 * One rule inside a policy, in both directions — read and written.
 *
 * <p>{@link #config()} is the untyped JSONB bag from
 * {@code firewall_policy_rule.config}, deliberately not modelled as one record
 * per rule type. Rule parameters live in JSON so that adding one is a code change
 * and never a migration (design section 3), and giving the API a typed shape per
 * rule would put that migration back, in the DTO layer, where it would also have
 * to be versioned.
 *
 * <p>The policy editor gets its per-rule field list from
 * {@link FirewallRuleTypeXO#configSchema()} instead, which the server derives
 * from the rules it actually has.
 *
 * @param id null when creating
 * @param implemented whether this build has a bean for the rule type. Returned,
 *     never accepted: a policy may legitimately carry a rule the running version
 *     does not implement — it is skipped, not enforced — and the editor has to be
 *     able to say so rather than showing a switch that does nothing
 */
public record FirewallPolicyRuleXO(
        UUID id,
        @NotNull FirewallRuleType ruleType,
        @NotNull FirewallAction action,
        Map<String, Object> config,
        boolean enabled,
        boolean implemented) {}
