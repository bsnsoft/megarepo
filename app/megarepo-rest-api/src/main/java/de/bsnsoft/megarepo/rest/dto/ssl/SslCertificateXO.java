package de.bsnsoft.megarepo.rest.dto.ssl;

import java.time.Instant;
import java.util.UUID;

public record SslCertificateXO(
        UUID id,
        String pem,
        String subjectCn,
        String issuerCn,
        String issuerOrg,
        String fingerprint,
        Instant issuedOn,
        Instant expiresOn,
        Instant createdAt) {}
