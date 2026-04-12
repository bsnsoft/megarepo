package de.bsnsoft.megarepo.database.repository;

import de.bsnsoft.megarepo.database.entity.CleanupPolicyEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CleanupPolicyJpaRepository extends JpaRepository<CleanupPolicyEntity, String> {
}
