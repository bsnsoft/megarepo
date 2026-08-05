package de.bsnsoft.megarepo.rest.dto.firewall;

import jakarta.validation.constraints.NotNull;

/**
 * Request body for writing the global enforcement switch.
 *
 * @param enabled the desired state. Boxed and {@code @NotNull} on purpose: a
 *     body that forgot the field must be rejected, not silently read as
 *     {@code false} — "the switch you meant to arm is off" is the worst possible
 *     default here.
 * @param confirmation required only for the transition to {@code true}; must
 *     equal the phrase the resource advertises in
 *     {@link FirewallEnforcementXO#requiredConfirmation()}. Ignored when turning
 *     enforcement off, which is always allowed without ceremony.
 */
public record FirewallEnforcementUpdateXO(@NotNull Boolean enabled, String confirmation) {}
