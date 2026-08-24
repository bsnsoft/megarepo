package de.bsnsoft.megarepo.repository.firewall.exemption;

import de.bsnsoft.megarepo.database.entity.FirewallExemptionEntity;

import java.util.Arrays;
import java.util.List;

/**
 * {@link FirewallExemptionEntity} ↔ {@link FirewallExemption}.
 *
 * <p>Separate from the service so that the one place a {@code TEXT[]} becomes a
 * {@code List<String>} — the mapping most likely to hand out a null and the one
 * least interesting to read in the middle of the workflow — is a function with a
 * name.
 *
 * <p>Deliberately one-directional for the identity fields: nothing here writes
 * {@code id}, {@code state}, {@code approvedBy} or the timestamps back onto an
 * entity. Those are transitions, and a transition that can happen through a
 * mapper is a transition without a state check.
 */
final class ExemptionMapper {

    private ExemptionMapper() {}

    static FirewallExemption toDomain(FirewallExemptionEntity entity) {
        if (entity == null) {
            return null;
        }
        return new FirewallExemption(
                entity.getId(),
                entity.getComponentKey(),
                entity.getKeyKind(),
                entity.getScopeType(),
                entity.getRepositoryId(),
                entity.getRuleType(),
                advisoryIds(entity),
                entity.getState(),
                entity.getExpiresAt(),
                entity.getExpiryNotifiedAt(),
                entity.getJustification(),
                entity.getRequestedBy(),
                entity.getRequestedAt(),
                entity.getApprovedBy(),
                entity.getApprovedAt(),
                entity.getDecisionNote());
    }

    static List<FirewallExemption> toDomain(List<FirewallExemptionEntity> entities) {
        return entities.stream().map(ExemptionMapper::toDomain).toList();
    }

    /** Empty means "every advisory"; a null column says the same thing. */
    private static List<String> advisoryIds(FirewallExemptionEntity entity) {
        String[] ids = entity.getAdvisoryIds();
        return ids == null ? List.of() : Arrays.asList(ids);
    }
}
