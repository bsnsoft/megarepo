package de.bsnsoft.megarepo.rest.dto.firewall;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for releasing or blocking a quarantined component.
 *
 * <p>Which of the two happens is the endpoint, not a field: {@code POST
 * …/quarantine/{id}/release} and {@code …/block}. A body that could flip the
 * meaning of the call is the kind of API where a retry with a stale payload
 * releases something somebody just blocked.
 *
 * @param note why. Required, and deliberately so — the resolution recorded
 *     against a manual decision is {@code MANUAL_RELEASE} or {@code MANUAL_BLOCK},
 *     which says who but not why, and "why" is the whole value of the row six
 *     months later
 */
public record FirewallQuarantineDecisionXO(@NotBlank @Size(max = 1000) String note) {}
