package de.bsnsoft.megarepo.rest.dto.firewall;

import de.bsnsoft.megarepo.core.firewall.FirewallMode;
import jakarta.validation.constraints.NotNull;

/**
 * Request body for setting one repository's firewall mode.
 *
 * @param mode the desired mode; required
 * @param confirmation required only when moving <em>into</em>
 *     {@link FirewallMode#QUARANTINE}, and then it must be
 *     {@code QUARANTINE <repository name>}. Naming the repository is the point:
 *     the overview is a list of near-identical dropdowns, and the realistic
 *     accident is arming the wrong row, not arming by accident at all. Leaving
 *     QUARANTINE, or any move between OFF and AUDIT, needs nothing.
 */
public record FirewallRepositoryModeUpdateXO(@NotNull FirewallMode mode, String confirmation) {}
