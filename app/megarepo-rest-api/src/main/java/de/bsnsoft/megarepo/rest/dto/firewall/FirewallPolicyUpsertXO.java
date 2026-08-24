package de.bsnsoft.megarepo.rest.dto.firewall;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Request body for creating or replacing a policy.
 *
 * <p>{@link #rules()} is the complete set, not a delta: a PUT replaces the
 * policy's rules with exactly what is sent. Partial rule updates would need a
 * per-rule identity the editor does not have while a rule is being added, and
 * "the rule I deleted came back" is a worse failure than re-sending five rows.
 *
 * @param makeDefault whether this policy becomes the global default. Moving the
 *     flag also clears it from whichever policy held it — the schema permits
 *     exactly one, and asking a client to run two calls in the right order is
 *     asking for the window in between
 * @param confirmation required when the change would alter what an enforcing
 *     repository denies — editing the policy of a repository that is in
 *     QUARANTINE while the master switch is on. The same guard
 *     {@code FirewallAdminController} already applies to arming a repository, for
 *     the same reason: this is the call that can break somebody's build in the
 *     next second
 */
public record FirewallPolicyUpsertXO(
        @NotBlank @Size(max = 200) String name,
        @Size(max = 1000) String description,
        boolean makeDefault,
        @Valid List<FirewallPolicyRuleXO> rules,
        String confirmation) {}
