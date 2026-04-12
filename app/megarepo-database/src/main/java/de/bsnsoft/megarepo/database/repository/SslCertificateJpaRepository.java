package de.bsnsoft.megarepo.database.repository;

import de.bsnsoft.megarepo.database.entity.SslCertificateEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface SslCertificateJpaRepository extends JpaRepository<SslCertificateEntity, UUID> {

    Optional<SslCertificateEntity> findByFingerprint(String fingerprint);
}
