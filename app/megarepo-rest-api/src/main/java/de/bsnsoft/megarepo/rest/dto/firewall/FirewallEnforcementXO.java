package de.bsnsoft.megarepo.rest.dto.firewall;

import java.time.Instant;

/**
 * The global enforcement switch as the administration API reports it.
 *
 * @param enabled whether this instance may block downloads at all. False means
 *     the firewall observes and records; no repository blocks, whatever its mode
 *     says.
 * @param updatedAt when the switch was last written
 * @param updatedBy who wrote it, or null on a never-touched installation
 * @param requiredConfirmation the exact phrase a caller must send in
 *     {@code confirmation} to turn the switch <em>on</em>. Advertised here so
 *     the Web UI does not carry its own copy of the phrase and so a curl user
 *     learns it from the resource rather than from a 400. Turning the switch off
 *     never needs it.
 */
public record FirewallEnforcementXO(
        boolean enabled, Instant updatedAt, String updatedBy, String requiredConfirmation) {}
