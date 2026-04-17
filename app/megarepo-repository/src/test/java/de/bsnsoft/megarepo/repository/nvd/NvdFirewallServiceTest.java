package de.bsnsoft.megarepo.repository.nvd;

import de.bsnsoft.megarepo.database.entity.AssetEntity;
import de.bsnsoft.megarepo.database.entity.ComponentEntity;
import de.bsnsoft.megarepo.database.entity.NvdFirewallSettingsEntity;
import de.bsnsoft.megarepo.database.entity.NvdFirewallWhitelistEntity;
import de.bsnsoft.megarepo.database.repository.AssetJpaRepository;
import de.bsnsoft.megarepo.database.repository.ComponentJpaRepository;
import de.bsnsoft.megarepo.database.repository.NvdFirewallBlockJpaRepository;
import de.bsnsoft.megarepo.database.repository.NvdFirewallSettingsJpaRepository;
import de.bsnsoft.megarepo.database.repository.NvdFirewallWhitelistJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NvdFirewallServiceTest {

    @Mock NvdFirewallSettingsJpaRepository settingsRepo;
    @Mock AssetJpaRepository assetRepo;
    @Mock ComponentJpaRepository componentRepo;
    @Mock NvdCveLookupService lookup;
    @Mock NvdFirewallBlockJpaRepository blockRepo;
    @Mock NvdFirewallWhitelistJpaRepository whitelistRepo;

    NvdFirewallService service;

    UUID repoId = UUID.randomUUID();
    UUID componentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new NvdFirewallService(settingsRepo, assetRepo, componentRepo, lookup, blockRepo, whitelistRepo);
    }

    @Test
    void disabledFirewallAlwaysAllows() {
        when(settingsRepo.findById(1)).thenReturn(Optional.of(settings(false, 7.0)));

        var r = service.checkDownload(repoId, "path", "maven-central", "alice");
        assertFalse(r.blocked());
        verify(lookup, never()).findApplicableCves(any(ComponentEntity.class));
    }

    @Test
    void missingAssetAllows() {
        when(settingsRepo.findById(1)).thenReturn(Optional.of(settings(true, 7.0)));
        when(assetRepo.findByRepositoryIdAndPath(repoId, "path")).thenReturn(Optional.empty());

        assertFalse(service.checkDownload(repoId, "path", "maven-central", "alice").blocked());
    }

    @Test
    void assetWithoutComponentAllows() {
        when(settingsRepo.findById(1)).thenReturn(Optional.of(settings(true, 7.0)));
        AssetEntity asset = new AssetEntity();
        asset.setComponentId(null);
        when(assetRepo.findByRepositoryIdAndPath(repoId, "path")).thenReturn(Optional.of(asset));

        assertFalse(service.checkDownload(repoId, "path", "maven-central", "alice").blocked());
    }

    @Test
    void log4shellBlocksWithHighScore() {
        stubLog4jComponent();
        when(settingsRepo.findById(1)).thenReturn(Optional.of(settings(true, 7.0)));
        when(lookup.findApplicableCves(any(ComponentEntity.class)))
                .thenReturn(List.of(new NvdCveLookupService.Hit("CVE-2021-44228", 10.0, "CRITICAL", "Log4Shell")));
        when(whitelistRepo.findByEntryTypeAndValue(anyString(), anyString())).thenReturn(Optional.empty());
        when(whitelistRepo.findByEntryType("CVE")).thenReturn(List.of());

        var r = service.checkDownload(repoId, "path", "maven-central", "alice");
        assertTrue(r.blocked());
        assertEquals(10.0, r.maxScore());
        assertEquals(1, r.vulnerabilities().size());
        assertEquals("CVE-2021-44228", r.vulnerabilities().get(0).cveId());
        verify(blockRepo).save(any());
    }

    @Test
    void belowThresholdAllows() {
        stubLog4jComponent();
        when(settingsRepo.findById(1)).thenReturn(Optional.of(settings(true, 9.0))); // threshold 9.0
        when(lookup.findApplicableCves(any(ComponentEntity.class)))
                .thenReturn(List.of(new NvdCveLookupService.Hit("CVE-X", 7.5, "HIGH", "Medium issue")));
        when(whitelistRepo.findByEntryTypeAndValue(anyString(), anyString())).thenReturn(Optional.empty());
        when(whitelistRepo.findByEntryType("CVE")).thenReturn(List.of());

        var r = service.checkDownload(repoId, "path", "maven-central", "alice");
        assertFalse(r.blocked(), "Score 7.5 below threshold 9.0 — should allow");
        verify(blockRepo, never()).save(any());
    }

    @Test
    void cveWhitelistBypassesBlock() {
        stubLog4jComponent();
        when(settingsRepo.findById(1)).thenReturn(Optional.of(settings(true, 7.0)));
        when(lookup.findApplicableCves(any(ComponentEntity.class)))
                .thenReturn(List.of(new NvdCveLookupService.Hit("CVE-2021-44228", 10.0, "CRITICAL", "Log4Shell")));

        NvdFirewallWhitelistEntity cveEntry = new NvdFirewallWhitelistEntity();
        cveEntry.setEntryType("CVE");
        cveEntry.setValue("CVE-2021-44228");
        when(whitelistRepo.findByEntryTypeAndValue(anyString(), anyString())).thenReturn(Optional.empty());
        when(whitelistRepo.findByEntryType("CVE")).thenReturn(List.of(cveEntry));

        assertFalse(service.checkDownload(repoId, "path", "maven-central", "alice").blocked(),
                "Whitelisted CVE should not block");
    }

    @Test
    void componentWhitelistSkipsLookup() {
        stubLog4jComponent();
        when(settingsRepo.findById(1)).thenReturn(Optional.of(settings(true, 7.0)));

        NvdFirewallWhitelistEntity entry = new NvdFirewallWhitelistEntity();
        entry.setEntryType("COMPONENT");
        entry.setValue("maven2:org.apache.logging.log4j:log4j-core:2.14.1");
        when(whitelistRepo.findByEntryTypeAndValue(eq("COMPONENT"), eq("maven2:org.apache.logging.log4j:log4j-core:2.14.1")))
                .thenReturn(Optional.of(entry));

        var r = service.checkDownload(repoId, "path", "maven-central", "alice");
        assertFalse(r.blocked());
        verify(lookup, never()).findApplicableCves(any(ComponentEntity.class));
    }

    private NvdFirewallSettingsEntity settings(boolean enabled, double threshold) {
        var s = new NvdFirewallSettingsEntity();
        s.setEnabled(enabled);
        s.setCvssThreshold(threshold);
        s.setApiKey("test-key");
        return s;
    }

    private void stubLog4jComponent() {
        AssetEntity asset = new AssetEntity();
        asset.setComponentId(componentId);
        when(assetRepo.findByRepositoryIdAndPath(repoId, "path")).thenReturn(Optional.of(asset));

        ComponentEntity comp = new ComponentEntity();
        comp.setId(componentId);
        comp.setFormat("maven2");
        comp.setNamespace("org.apache.logging.log4j");
        comp.setName("log4j-core");
        comp.setVersion("2.14.1");
        when(componentRepo.findById(componentId)).thenReturn(Optional.of(comp));
    }
}
