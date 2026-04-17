package de.bsnsoft.megarepo.database.repository;

import de.bsnsoft.megarepo.database.entity.NvdFirewallBlockEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface NvdFirewallBlockJpaRepository extends JpaRepository<NvdFirewallBlockEntity, Long> {
    Page<NvdFirewallBlockEntity> findAllByOrderByTimestampDesc(Pageable pageable);
}
