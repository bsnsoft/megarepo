package de.bsnsoft.megarepo.database.repository;

import de.bsnsoft.megarepo.database.entity.RoutingRuleEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RoutingRuleJpaRepository extends JpaRepository<RoutingRuleEntity, String> {
}
