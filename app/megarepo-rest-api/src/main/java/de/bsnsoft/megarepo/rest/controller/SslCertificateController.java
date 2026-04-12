package de.bsnsoft.megarepo.rest.controller;

import de.bsnsoft.megarepo.core.exception.ValidationException;
import de.bsnsoft.megarepo.core.validation.UrlSsrfValidator;
import de.bsnsoft.megarepo.database.entity.SslCertificateEntity;
import de.bsnsoft.megarepo.rest.dto.ssl.AddCertificateFromPemRequest;
import de.bsnsoft.megarepo.rest.dto.ssl.SslCertificateXO;
import de.bsnsoft.megarepo.security.ssl.SslCertificateService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/security/ssl")
public class SslCertificateController {

    private final SslCertificateService sslCertificateService;

    public SslCertificateController(SslCertificateService sslCertificateService) {
        this.sslCertificateService = sslCertificateService;
    }

    @GetMapping("/truststore")
    public ResponseEntity<List<SslCertificateXO>> list() {
        var certs = sslCertificateService.listCertificates().stream()
                .map(this::toXO)
                .toList();
        return ResponseEntity.ok(certs);
    }

    @PostMapping("/truststore")
    public ResponseEntity<SslCertificateXO> addFromPem(@Valid @RequestBody AddCertificateFromPemRequest request) {
        SslCertificateEntity saved = sslCertificateService.addCertificateFromPem(request.pem());
        return ResponseEntity.created(URI.create("/api/v1/security/ssl/truststore/" + saved.getId()))
                .body(toXO(saved));
    }

    @GetMapping
    public ResponseEntity<List<SslCertificateXO>> fetchFromHost(
            @RequestParam @Size(max = 500) String host,
            @RequestParam(defaultValue = "443") @Min(1) @Max(65535) int port) {
        validateHostNotInternal(host);
        var certs = sslCertificateService.fetchCertificatesFromHost(host, port).stream()
                .map(this::toXO)
                .toList();
        return ResponseEntity.ok(certs);
    }

    /**
     * Validates that the hostname does not resolve to a loopback or private/link-local address
     * to prevent SSRF attacks against internal infrastructure.
     * Delegates to the shared {@link UrlSsrfValidator}.
     */
    private void validateHostNotInternal(String host) {
        UrlSsrfValidator.validateHostNotInternal(host);
    }

    @DeleteMapping("/truststore/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        sslCertificateService.deleteCertificate(id);
        return ResponseEntity.noContent().build();
    }

    private SslCertificateXO toXO(SslCertificateEntity entity) {
        return new SslCertificateXO(
                entity.getId(),
                entity.getPem(),
                entity.getSubjectCn(),
                entity.getIssuerCn(),
                entity.getIssuerOrg(),
                entity.getFingerprint(),
                entity.getIssuedOn(),
                entity.getExpiresOn(),
                entity.getCreatedAt());
    }
}
