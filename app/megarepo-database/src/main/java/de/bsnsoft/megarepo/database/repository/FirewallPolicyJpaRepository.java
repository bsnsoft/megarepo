package de.bsnsoft.megarepo.database.repository;

import de.bsnsoft.megarepo.database.entity.FirewallPolicyEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface FirewallPolicyJpaRepository extends JpaRepository<FirewallPolicyEntity, UUID> {

    Optional<FirewallPolicyEntity> findByName(String name);

    /** At most one row can match — enforced by idx_firewall_policy_single_default. */
    Optional<FirewallPolicyEntity> findByIsDefaultTrue();
}
