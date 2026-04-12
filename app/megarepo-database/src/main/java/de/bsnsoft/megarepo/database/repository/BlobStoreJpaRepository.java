package de.bsnsoft.megarepo.database.repository;

import de.bsnsoft.megarepo.database.entity.BlobStoreEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface BlobStoreJpaRepository extends JpaRepository<BlobStoreEntity, String> {
}
