package de.bsnsoft.megarepo.repository.nvd;

import de.bsnsoft.megarepo.database.entity.ComponentEntity;
import de.bsnsoft.megarepo.database.entity.NvdFirewallBlockEntity;
import de.bsnsoft.megarepo.database.entity.NvdFirewallSettingsEntity;
import de.bsnsoft.megarepo.database.entity.NvdFirewallWhitelistEntity;
import de.bsnsoft.megarepo.database.repository.AssetJpaRepository;
import de.bsnsoft.megarepo.database.repository.ComponentJpaRepository;
import de.bsnsoft.megarepo.database.repository.NvdFirewallBlockJpaRepository;
import de.bsnsoft.megarepo.database.repository.NvdFirewallSettingsJpaRepository;
import de.bsnsoft.megarepo.database.repository.NvdFirewallWhitelistJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class NvdFirewallService {

    private static final Logger log = LoggerFactory.getLogger(NvdFirewallService.class);
    private static final int SETTINGS_ID = 1;

    private final NvdFirewallSettingsJpaRepository settingsRepo;
    private final AssetJpaRepository assetRepo;
    private final ComponentJpaRepository componentRepo;
    private final NvdCveLookupService lookup;
    private final NvdFirewallBlockJpaRepository blockRepo;
    private final NvdFirewallWhitelistJpaRepository whitelistRepo;

    public NvdFirewallService(
            NvdFirewallSettingsJpaRepository settingsRepo,
            AssetJpaRepository assetRepo,
            ComponentJpaRepository componentRepo,
            NvdCveLookupService lookup,
            NvdFirewallBlockJpaRepository blockRepo,
            NvdFirewallWhitelistJpaRepository whitelistRepo) {
        this.settingsRepo = settingsRepo;
        this.assetRepo = assetRepo;
        this.componentRepo = componentRepo;
        this.lookup = lookup;
        this.blockRepo = blockRepo;
        this.whitelistRepo = whitelistRepo;
    }

    public record CheckResult(boolean blocked, double maxScore, List<NvdCveLookupService.Hit> vulnerabilities) {
        public static CheckResult allowed() { return new CheckResult(false, 0, List.of()); }
    }

    public CheckResult checkDownload(UUID repositoryId, String path, String repoName, String userId) {
        NvdFirewallSettingsEntity settings = getSettings();
        if (!settings.isEnabled()) return CheckResult.allowed();

        var asset = assetRepo.findByRepositoryIdAndPath(repositoryId, path);
        if (asset.isEmpty() || asset.get().getComponentId() == null) return CheckResult.allowed();

        var component = componentRepo.findById(asset.get().getComponentId());
        if (component.isEmpty()) return CheckResult.allowed();

        ComponentEntity comp = component.get();
        String componentKey = buildComponentKey(comp);

        // Component whitelist short-circuits the check entirely.
        if (isComponentWhitelisted(componentKey)) {
            log.debug("Component {} is whitelisted, allowing", componentKey);
            return CheckResult.allowed();
        }

        List<NvdCveLookupService.Hit> hits = lookup.findApplicableCves(comp);
        if (hits.isEmpty()) return CheckResult.allowed();

        // Drop whitelisted CVE IDs before thresholding.
        Set<String> cveWhitelist = getCveWhitelist();
        List<NvdCveLookupService.Hit> effective = cveWhitelist.isEmpty() ? hits
                : hits.stream().filter(h -> !cveWhitelist.contains(h.cveId())).toList();

        double maxScore = effective.stream().mapToDouble(NvdCveLookupService.Hit::cvssScore).max().orElse(0);
        List<NvdCveLookupService.Hit> blocking = effective.stream()
                .filter(h -> h.cvssScore() >= settings.getCvssThreshold())
                .toList();

        if (blocking.isEmpty()) return new CheckResult(false, maxScore, effective);

        logBlock(userId, repoName, path, componentKey, blocking);
        return new CheckResult(true, maxScore, blocking);
    }

    private boolean isComponentWhitelisted(String componentKey) {
        if (whitelistRepo.findByEntryTypeAndValue("COMPONENT", componentKey).isPresent()) return true;
        // Also match on the coord-without-version prefix (e.g. "maven2:org.apache.logging.log4j:log4j-core")
        int lastColon = componentKey.lastIndexOf(':');
        if (lastColon > 0) {
            String prefix = componentKey.substring(0, lastColon);
            return whitelistRepo.findByEntryTypeAndValue("COMPONENT", prefix).isPresent();
        }
        return false;
    }

    private Set<String> getCveWhitelist() {
        return whitelistRepo.findByEntryType("CVE").stream()
                .map(NvdFirewallWhitelistEntity::getValue)
                .collect(Collectors.toSet());
    }

    @Transactional
    protected void logBlock(String userId, String repoName, String path, String componentKey,
                            List<NvdCveLookupService.Hit> blocking) {
        try {
            var entity = new NvdFirewallBlockEntity();
            entity.setTimestamp(Instant.now());
            entity.setUserId(userId);
            entity.setRepository(repoName);
            entity.setPath(path);
            entity.setComponentKey(componentKey);
            double max = blocking.stream().mapToDouble(NvdCveLookupService.Hit::cvssScore).max().orElse(0);
            entity.setMaxCvssScore(max);
            List<Map<String, Object>> details = blocking.stream().map(h -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("cveId", h.cveId());
                m.put("cvssScore", h.cvssScore());
                m.put("severity", h.severity());
                m.put("description", h.description());
                return m;
            }).toList();
            entity.setCveDetails(details);
            blockRepo.save(entity);
        } catch (Exception e) {
            log.warn("Failed to log NVD block event", e);
        }
    }

    public static String buildComponentKey(ComponentEntity component) {
        String ns = component.getNamespace() != null ? component.getNamespace() : "";
        return component.getFormat() + ":" + ns + ":" + component.getName() + ":" + component.getVersion();
    }

    public NvdFirewallSettingsEntity getSettings() {
        return settingsRepo.findById(SETTINGS_ID).orElseGet(() -> {
            var defaults = new NvdFirewallSettingsEntity();
            return settingsRepo.save(defaults);
        });
    }
}
