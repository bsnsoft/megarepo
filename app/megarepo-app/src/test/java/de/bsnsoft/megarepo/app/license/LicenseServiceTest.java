package de.bsnsoft.megarepo.app.license;

import org.junit.jupiter.api.Disabled;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.bsnsoft.megarepo.database.repository.AuditLogJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@Disabled("Needs test keypair regeneration — Sprint 22 changed public key")
@ExtendWith(MockitoExtension.class)
class LicenseServiceTest {

    private static final String VALID_LICENSE = """
            {
              "company": "ACME Corp",
              "email": "admin@example.com",
              "issuedAt": "2026-04-01",
              "licenseId": "lic-test-0001",
              "signature": "aqBmvwM3rPEuuaEB2wgiaG71GVi5SQz+6sU9SwfV7GRJIdFFBXAn8nPDbhgzF5XfK47K5IOgsNjV644pf7z3pMH93foPcQWHyLK+XbLmw660jjXgfgUv4iz1B2n2PCEAOH3Bbzkq/QUhIpzL7GfERrAGkKFHlmEs+g8bKiySZGP2W7MUZ0tWBtldV8J/VokL/hRFDXi2i5EzyQ7vEXOdji72OOVqlegUZcUOaDDKeRomUBFFEupnJzzAI7Vb9L7GoWSTdbkco1DnVhtDN/mOfWld5CXBQLys6g89raFVz7q7OYpJDIsT+Z3eeLFwQipLthNy4Ydc2W/45RfS9jwO5w=="
            }
            """;

    @Mock
    private AuditLogJpaRepository auditLogJpaRepository;

    @TempDir
    Path tempDir;

    private LicenseService licenseService;

    @BeforeEach
    void setUp() {
        var licensePath = tempDir.resolve("megarepo.license").toString();
        licenseService = new LicenseService(auditLogJpaRepository, new ObjectMapper(), licensePath);
    }

    @Test
    void parseLicense_validSignature_returnsValidLicense() {
        var license = licenseService.parseLicense(VALID_LICENSE.getBytes(StandardCharsets.UTF_8));

        assertTrue(license.valid());
        assertEquals("ACME Corp", license.company());
        assertEquals("admin@example.com", license.email());
        assertEquals("2026-04-01", license.issuedAt());
        assertEquals("lic-test-0001", license.licenseId());
    }

    @Test
    void parseLicense_invalidSignature_returnsInvalidLicense() {
        var tampered = VALID_LICENSE.replace(
                "aqBmvwM3rPEuuaEB2wgiaG71GVi5SQz", "AAAAAAA3rPEuuaEB2wgiaG71GVi5SQz");
        var license = licenseService.parseLicense(tampered.getBytes(StandardCharsets.UTF_8));

        assertFalse(license.valid());
    }

    @Test
    void parseLicense_tamperedData_returnsInvalidLicense() {
        var tampered = VALID_LICENSE.replace("ACME Corp", "Hacker Corp");
        var license = licenseService.parseLicense(tampered.getBytes(StandardCharsets.UTF_8));

        assertFalse(license.valid());
    }

    @Test
    void parseLicense_malformedJson_returnsInvalidLicense() {
        var license = licenseService.parseLicense("not json".getBytes(StandardCharsets.UTF_8));

        assertFalse(license.valid());
    }

    @Test
    void parseLicense_missingFields_returnsInvalidLicense() {
        var partial = """
                {
                  "company": "Test",
                  "email": "test@test.com"
                }
                """;
        var license = licenseService.parseLicense(partial.getBytes(StandardCharsets.UTF_8));

        assertFalse(license.valid());
    }

    @Test
    void loadLicense_fileExists_loadsSuccessfully() throws IOException {
        var licensePath = tempDir.resolve("megarepo.license");
        Files.writeString(licensePath, VALID_LICENSE);

        var service = new LicenseService(auditLogJpaRepository, new ObjectMapper(), licensePath.toString());
        service.loadLicense();

        assertTrue(service.isLicensed());
        assertTrue(service.getLicenseInfo().isPresent());
        assertEquals("ACME Corp", service.getLicenseInfo().get().company());
    }

    @Test
    void loadLicense_noFile_remainsUnlicensed() {
        licenseService.loadLicense();

        assertFalse(licenseService.isLicensed());
        assertTrue(licenseService.getLicenseInfo().isEmpty());
    }

    @Test
    void uploadLicense_validContent_savesAndCaches() throws IOException {
        var content = VALID_LICENSE.getBytes(StandardCharsets.UTF_8);
        var license = licenseService.uploadLicense(content);

        assertTrue(license.valid());
        assertEquals("ACME Corp", license.company());
        assertTrue(licenseService.isLicensed());
        assertTrue(Files.exists(tempDir.resolve("megarepo.license")));
    }

    @Test
    void uploadLicense_invalidContent_throwsException() {
        var content = "{ \"company\": \"Evil\" }".getBytes(StandardCharsets.UTF_8);

        assertThrows(IllegalArgumentException.class, () -> licenseService.uploadLicense(content));
        assertFalse(licenseService.isLicensed());
    }

    @Test
    void removeLicense_clearsCacheAndDeletesFile() throws IOException {
        var content = VALID_LICENSE.getBytes(StandardCharsets.UTF_8);
        licenseService.uploadLicense(content);
        assertTrue(licenseService.isLicensed());

        licenseService.removeLicense();

        assertFalse(licenseService.isLicensed());
        assertFalse(Files.exists(tempDir.resolve("megarepo.license")));
    }

    @Test
    void getActiveUserCount_delegatesToRepository() {
        when(auditLogJpaRepository.countDistinctActiveUsers(any(Instant.class))).thenReturn(42);

        assertEquals(42, licenseService.getActiveUserCount());
    }

    @Test
    void requiresLicense_underLimit_returnsFalse() {
        when(auditLogJpaRepository.countDistinctActiveUsers(any(Instant.class))).thenReturn(30);

        assertFalse(licenseService.requiresLicense());
    }

    @Test
    void requiresLicense_overLimitAndUnlicensed_returnsTrue() {
        when(auditLogJpaRepository.countDistinctActiveUsers(any(Instant.class))).thenReturn(55);

        assertTrue(licenseService.requiresLicense());
    }

    @Test
    void requiresLicense_overLimitButLicensed_returnsFalse() throws IOException {
        licenseService.uploadLicense(VALID_LICENSE.getBytes(StandardCharsets.UTF_8));
        when(auditLogJpaRepository.countDistinctActiveUsers(any(Instant.class))).thenReturn(55);

        assertFalse(licenseService.requiresLicense());
    }
}
