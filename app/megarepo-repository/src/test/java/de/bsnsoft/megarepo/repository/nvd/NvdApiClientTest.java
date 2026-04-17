package de.bsnsoft.megarepo.repository.nvd;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parses a real NVD response for CVE-2021-44228 (Log4Shell). Verifies we
 * extract the CVSS V3.1 score of 10.0 and the CPE version range that makes
 * log4j-core 2.14.1 a hit.
 */
class NvdApiClientTest {

    private NvdApiClient client;

    @BeforeEach
    void setUp() {
        client = new NvdApiClient(null, new ObjectMapper(), NvdApiClient.NVD_URL);
    }

    @Test
    void parsesLog4ShellFixture() throws Exception {
        String body = loadFixture("/nvd/log4shell.json");
        NvdApiClient.PageResult page = client.parseResponse(body);

        assertEquals(1, page.totalResults());
        assertEquals(1, page.cves().size());

        NvdApiClient.CveData cve = page.cves().get(0);
        assertEquals("CVE-2021-44228", cve.cveId());
        assertEquals(10.0, cve.cvssScore(), 0.001);
        assertEquals("3.1", cve.cvssVersion());
        assertEquals("CRITICAL", cve.severity());
        assertNotNull(cve.description());
        assertTrue(cve.description().toLowerCase().contains("log4j"));

        assertFalse(cve.cpeMatches().isEmpty(), "Log4Shell should have CPE matches");
        boolean hasLog4j = cve.cpeMatches().stream()
                .anyMatch(m -> "log4j".equals(m.product()));
        assertTrue(hasLog4j, "Expected at least one CPE match for product=log4j");

        // Verify version range covers 2.14.1
        NvdApiClient.CpeMatch log4jMatch = cve.cpeMatches().stream()
                .filter(m -> "log4j".equals(m.product()))
                .filter(m -> m.versionStartIncluding() != null || m.versionEndExcluding() != null)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No ranged log4j CPE match found"));
        assertNotNull(log4jMatch.versionStartIncluding(), "Expected versionStartIncluding");
    }

    private String loadFixture(String path) throws Exception {
        try (InputStream in = getClass().getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("Fixture not found: " + path);
            return new String(in.readAllBytes());
        }
    }
}
