package de.bsnsoft.megarepo.database.repository;

import de.bsnsoft.megarepo.core.firewall.FirewallMode;
import de.bsnsoft.megarepo.database.entity.FirewallRepositoryConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface FirewallRepositoryConfigJpaRepository
        extends JpaRepository<FirewallRepositoryConfigEntity, UUID> {

    List<FirewallRepositoryConfigEntity> findByMode(FirewallMode mode);

    List<FirewallRepositoryConfigEntity> findByPolicyId(UUID policyId);
}
