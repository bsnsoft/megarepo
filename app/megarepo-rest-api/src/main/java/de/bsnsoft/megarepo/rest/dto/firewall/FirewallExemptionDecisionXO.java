package de.bsnsoft.megarepo.rest.dto.firewall;

import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * Request body for approving, rejecting or revoking an exemption.
 *
 * <p>Which of the three happens is the endpoint — {@code …/approve},
 * {@code …/reject}, {@code …/revoke} — not a field in the body.
 *
 * @param expiresAt when the exemption should lapse. Read only by
 *     {@code …/approve}. <b>Null means never</b>, and the API does not treat an
 *     omitted field as "use the default": an exemption that never expires is the
 *     V8 whitelist's defining flaw, and it should require someone to have said so
 *     rather than to have left a field out. The UI pre-fills
 *     {@code megarepo.firewall.exemption.default-validity} so the easy path is
 *     the bounded one
 * @param note what the approver has to say. Optional for an approval, and the
 *     thing that makes a rejection or a revocation actionable for whoever asked
 */
public record FirewallExemptionDecisionXO(Instant expiresAt, @Size(max = 1000) String note) {}
