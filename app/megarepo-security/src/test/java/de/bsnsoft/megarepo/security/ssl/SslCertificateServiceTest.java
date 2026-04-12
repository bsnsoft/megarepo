package de.bsnsoft.megarepo.security.ssl;

import de.bsnsoft.megarepo.core.exception.NotFoundException;
import de.bsnsoft.megarepo.database.entity.SslCertificateEntity;
import de.bsnsoft.megarepo.database.repository.SslCertificateJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SslCertificateServiceTest {

    @Mock
    private SslCertificateJpaRepository sslCertificateRepository;

    private SslCertificateService service;

    // Self-signed test certificate generated via openssl for CN=localhost, O=Test Corp
    private static final String TEST_PEM = """
            -----BEGIN CERTIFICATE-----
            MIIDMTCCAhmgAwIBAgIUEu9JhY15n0E98Ghs+YSVlgztqn4wDQYJKoZIhvcNAQEL
            BQAwKDESMBAGA1UEAwwJbG9jYWxob3N0MRIwEAYDVQQKDAlUZXN0IENvcnAwHhcN
            MjYwMzI4MTk0OTA3WhcNMjcwMzI4MTk0OTA3WjAoMRIwEAYDVQQDDAlsb2NhbGhv
            c3QxEjAQBgNVBAoMCVRlc3QgQ29ycDCCASIwDQYJKoZIhvcNAQEBBQADggEPADCC
            AQoCggEBAKyP5ba3+XXXHHseyCLD+XfZSzG0LOUp9XUa3L1A8r3o4T4rG4+NiFSO
            jdXtuJJNVBVwYmQ+4PKQw8/J2AipkPcBw6gcOMV0k74RFvAXPkipElXtH3mcVb1/
            Sr0BchvMC0pRDsX01o8RVKYHjtiZd7hWO3B99ntPkvLLi/y5Jkz0DfdGyyeGgfCW
            J6rGgUalJAl2rB8l9JK27XsswS9iYMgGkBrEG+vJbV3q0Q2SOtahiAbm6D0Hlaqi
            Dc08vPrvHdiaKzC571AW4X+XPx+i7Bo85WKoPn/Mth6dcHYd15tjiVE4jq9qC/5h
            hGU1xojrBGm+piLMAGY+F+B2y+2SYScCAwEAAaNTMFEwHQYDVR0OBBYEFLnPSPJf
            ErNCrcrlOiVT0FHrds2EMB8GA1UdIwQYMBaAFLnPSPJfErNCrcrlOiVT0FHrds2E
            MA8GA1UdEwEB/wQFMAMBAf8wDQYJKoZIhvcNAQELBQADggEBAItvQSpDebXTNYnT
            fwkS4d/G4giTABmwyg/PWAGPnUrDfmZEA6UXvt3bakw48BMiz1U6cvJBLBaA2JrG
            R//lzb+2mXy7kMb3Ipphw9CXkoMld/NveH6dTtIJN+lDCxS2KFjqc8uXE/3Qd/Sr
            fCJTI2SGoBivO0g45vu3q0tlhsYsNvUKtCo65CxDiKhtIHyRhjUtWwnVeSvYLUvR
            rmkHIAZp94zuZ4Z28NyAf5jy/fYIv2/oq/MA+uppdfLHsp6sfzFjiblwcA4hBoNV
            vvxXQqAQmzOpG0YupVP8g5TUsufycSQkJVEC3zfhkFXFdQaFFPJHTIKzquQae46n
            oU7ggz0=
            -----END CERTIFICATE-----""";

    @BeforeEach
    void setUp() {
        service = new SslCertificateService(sslCertificateRepository);
    }

    @Test
    void listCertificates_returnsAll() {
        var entity1 = new SslCertificateEntity();
        entity1.setSubjectCn("test1");
        var entity2 = new SslCertificateEntity();
        entity2.setSubjectCn("test2");

        when(sslCertificateRepository.findAll()).thenReturn(List.of(entity1, entity2));

        List<SslCertificateEntity> result = service.listCertificates();

        assertThat(result).hasSize(2);
        verify(sslCertificateRepository).findAll();
    }

    @Test
    void parsePem_validCertificate_returnsX509() {
        X509Certificate cert = service.parsePem(TEST_PEM);

        assertThat(cert).isNotNull();
        assertThat(cert.getSubjectX500Principal().getName()).contains("localhost");
        assertThat(cert.getSubjectX500Principal().getName()).contains("Test Corp");
    }

    @Test
    void parsePem_invalidPem_throwsException() {
        assertThatThrownBy(() -> service.parsePem("not a valid certificate"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid PEM certificate");
    }

    @Test
    void computeFingerprint_returnsColonSeparatedHex() {
        X509Certificate cert = service.parsePem(TEST_PEM);

        String fingerprint = service.computeFingerprint(cert);

        assertThat(fingerprint).matches("[A-F0-9]{2}(:[A-F0-9]{2}){31}");
    }

    @Test
    void computeFingerprint_sameCertSameFingerprint() {
        X509Certificate cert = service.parsePem(TEST_PEM);

        String fp1 = service.computeFingerprint(cert);
        String fp2 = service.computeFingerprint(cert);

        assertThat(fp1).isEqualTo(fp2);
    }

    @Test
    void extractCn_parsesCorrectly() {
        assertThat(service.extractCn("CN=localhost")).isEqualTo("localhost");
        assertThat(service.extractCn("CN=test.example.com,O=Example Inc")).isEqualTo("test.example.com");
        assertThat(service.extractCn("O=Example Inc")).isNull();
    }

    @Test
    void extractField_parsesOrganization() {
        assertThat(service.extractField("CN=test,O=Example Inc,C=US", "O")).isEqualTo("Example Inc");
        assertThat(service.extractField("CN=test,C=US", "O")).isNull();
    }

    @Test
    void addCertificateFromPem_savesEntity() {
        when(sslCertificateRepository.findByFingerprint(any())).thenReturn(Optional.empty());
        when(sslCertificateRepository.save(any())).thenAnswer(inv -> {
            SslCertificateEntity e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });

        SslCertificateEntity saved = service.addCertificateFromPem(TEST_PEM);

        assertThat(saved.getSubjectCn()).isEqualTo("localhost");
        assertThat(saved.getFingerprint()).isNotNull();
        assertThat(saved.getPem()).contains("BEGIN CERTIFICATE");
        assertThat(saved.getIssuedOn()).isNotNull();
        assertThat(saved.getExpiresOn()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();

        verify(sslCertificateRepository).save(any());
    }

    @Test
    void addCertificateFromPem_duplicateFingerprint_throwsException() {
        when(sslCertificateRepository.findByFingerprint(any())).thenReturn(Optional.of(new SslCertificateEntity()));

        assertThatThrownBy(() -> service.addCertificateFromPem(TEST_PEM))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists");

        verify(sslCertificateRepository, never()).save(any());
    }

    @Test
    void deleteCertificate_existingId_deletes() {
        UUID id = UUID.randomUUID();
        when(sslCertificateRepository.existsById(id)).thenReturn(true);

        service.deleteCertificate(id);

        verify(sslCertificateRepository).deleteById(id);
    }

    @Test
    void deleteCertificate_nonExistingId_throwsNotFound() {
        UUID id = UUID.randomUUID();
        when(sslCertificateRepository.existsById(id)).thenReturn(false);

        assertThatThrownBy(() -> service.deleteCertificate(id))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining(id.toString());
    }
}
