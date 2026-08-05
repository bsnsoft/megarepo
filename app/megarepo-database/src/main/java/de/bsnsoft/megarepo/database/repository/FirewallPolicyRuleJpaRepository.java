package de.bsnsoft.megarepo.database.repository;

import de.bsnsoft.megarepo.core.firewall.FirewallRuleType;
import de.bsnsoft.megarepo.database.entity.FirewallPolicyRuleEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface FirewallPolicyRuleJpaRepository extends JpaRepository<FirewallPolicyRuleEntity, UUID> {

    List<FirewallPolicyRuleEntity> findByPolicyId(UUID policyId);

    List<FirewallPolicyRuleEntity> findByPolicyIdAndEnabledTrue(UUID policyId);

    List<FirewallPolicyRuleEntity> findByPolicyIdAndRuleType(UUID policyId, FirewallRuleType ruleType);

    void deleteByPolicyId(UUID policyId);
}
