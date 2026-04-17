package de.bsnsoft.megarepo.repository.nvd;

import de.bsnsoft.megarepo.database.entity.CveAffectedProductEntity;
import de.bsnsoft.megarepo.database.entity.CveEntryEntity;
import de.bsnsoft.megarepo.database.repository.CveAffectedProductJpaRepository;
import de.bsnsoft.megarepo.database.repository.CveEntryJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NvdCveLookupServiceTest {

    @Mock CveEntryJpaRepository cveRepo;
    @Mock CveAffectedProductJpaRepository affectedRepo;

    NvdCveLookupService lookup;

    @BeforeEach
    void setUp() {
        lookup = new NvdCveLookupService(cveRepo, affectedRepo);
    }

    @Test
    void productCandidatesExpandDashAndUnderscore() {
        Set<String> candidates = NvdCveLookupService.buildProductCandidates("log4j-core");
        assertTrue(candidates.contains("log4j-core"));
        assertTrue(candidates.contains("log4j_core"));
        assertTrue(candidates.contains("log4j"));
    }

    @Test
    void productCandidatesKeepPlainName() {
        Set<String> candidates = NvdCveLookupService.buildProductCandidates("jackson-databind");
        assertTrue(candidates.contains("jackson"));
        assertTrue(candidates.contains("jackson-databind"));
        assertTrue(candidates.contains("jackson_databind"));
    }

    @Test
    void versionAppliesForExactMatch() {
        CveAffectedProductEntity m = affected("log4j", "2.14.1", null, null, null, null);
        assertTrue(NvdCveLookupService.versionApplies(m, "2.14.1"));
        assertFalse(NvdCveLookupService.versionApplies(m, "2.14.0"));
    }

    @Test
    void versionAppliesForLog4ShellRange() {
        // Log4Shell: 2.0-beta9 <= v < 2.17.0
        CveAffectedProductEntity m = affected("log4j", null, "2.0-beta9", null, null, "2.17.0");
        assertTrue(NvdCveLookupService.versionApplies(m, "2.14.1"));
        assertTrue(NvdCveLookupService.versionApplies(m, "2.0"));
        assertFalse(NvdCveLookupService.versionApplies(m, "2.17.0"), "2.17.0 is the fixed version");
        assertFalse(NvdCveLookupService.versionApplies(m, "2.18.0"));
        assertFalse(NvdCveLookupService.versionApplies(m, "1.9"));
    }

    @Test
    void versionAppliesForInclusiveEnd() {
        CveAffectedProductEntity m = affected("foo", null, "1.0", null, "2.0", null);
        assertTrue(NvdCveLookupService.versionApplies(m, "1.5"));
        assertTrue(NvdCveLookupService.versionApplies(m, "2.0"));
        assertFalse(NvdCveLookupService.versionApplies(m, "2.0.1"));
    }

    @Test
    void log4ShellScenarioEndToEnd() {
        // Simulate local DB: one log4j CVE (Log4Shell) with a range match on "log4j"
        CveAffectedProductEntity match = affected("log4j", null, "2.0-beta9", null, null, "2.15.0");
        match.setCveId("CVE-2021-44228");

        when(affectedRepo.findByProductIn(any(Collection.class))).thenReturn(List.of(match));

        CveEntryEntity log4shell = new CveEntryEntity();
        log4shell.setCveId("CVE-2021-44228");
        log4shell.setCvssScore(10.0);
        log4shell.setSeverity("CRITICAL");
        log4shell.setDescription("Apache Log4j2 JNDI RCE");
        log4shell.setPublished(Instant.now());
        log4shell.setLastModified(Instant.now());

        when(cveRepo.findAllById(any())).thenReturn(List.of(log4shell));

        List<NvdCveLookupService.Hit> hits = lookup.findApplicableCves(
                "maven2", "org.apache.logging.log4j", "log4j-core", "2.14.1");

        assertEquals(1, hits.size());
        assertEquals("CVE-2021-44228", hits.get(0).cveId());
        assertEquals(10.0, hits.get(0).cvssScore());

        // Verify the candidate list includes the underscored variant and the prefix
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(affectedRepo).findByProductIn(captor.capture());
        Collection<String> products = captor.getValue();
        assertNotNull(products);
        assertTrue(products.contains("log4j"), "Should try 'log4j' as CPE product");
    }

    @Test
    void safeVersionReturnsNoHits() {
        // log4j-core 2.17.1 is NOT vulnerable to Log4Shell
        CveAffectedProductEntity match = affected("log4j", null, "2.0-beta9", null, null, "2.15.0");
        match.setCveId("CVE-2021-44228");
        when(affectedRepo.findByProductIn(any(Collection.class))).thenReturn(List.of(match));

        List<NvdCveLookupService.Hit> hits = lookup.findApplicableCves(
                "maven2", "org.apache.logging.log4j", "log4j-core", "2.17.1");
        assertEquals(0, hits.size());
    }

    private static CveAffectedProductEntity affected(
            String product, String exact, String startInc, String startExc, String endInc, String endExc) {
        CveAffectedProductEntity a = new CveAffectedProductEntity();
        a.setProduct(product);
        a.setVersionExact(exact);
        a.setVersionStartIncluding(startInc);
        a.setVersionStartExcluding(startExc);
        a.setVersionEndIncluding(endInc);
        a.setVersionEndExcluding(endExc);
        return a;
    }
}
