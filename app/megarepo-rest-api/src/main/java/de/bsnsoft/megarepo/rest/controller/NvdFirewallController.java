package de.bsnsoft.megarepo.rest.controller;

import de.bsnsoft.megarepo.database.entity.NvdFirewallBlockEntity;
import de.bsnsoft.megarepo.database.entity.NvdFirewallSettingsEntity;
import de.bsnsoft.megarepo.database.entity.NvdFirewallWhitelistEntity;
import de.bsnsoft.megarepo.database.entity.NvdSyncStateEntity;
import de.bsnsoft.megarepo.database.repository.NvdFirewallBlockJpaRepository;
import de.bsnsoft.megarepo.database.repository.NvdFirewallSettingsJpaRepository;
import de.bsnsoft.megarepo.database.repository.NvdFirewallWhitelistJpaRepository;
import de.bsnsoft.megarepo.repository.nvd.NvdSyncService;
import de.bsnsoft.megarepo.rest.dto.security.NvdBlockXO;
import de.bsnsoft.megarepo.rest.dto.security.NvdFirewallSettingsXO;
import de.bsnsoft.megarepo.rest.dto.security.NvdSyncStateXO;
import de.bsnsoft.megarepo.rest.dto.security.NvdWhitelistEntryXO;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/security/nvd-firewall")
public class NvdFirewallController {

    private static final Integer SETTINGS_ID = 1;

    private final NvdFirewallSettingsJpaRepository settingsRepo;
    private final NvdSyncService syncService;
    private final NvdFirewallBlockJpaRepository blockRepo;
    private final NvdFirewallWhitelistJpaRepository whitelistRepo;

    public NvdFirewallController(
            NvdFirewallSettingsJpaRepository settingsRepo,
            NvdSyncService syncService,
            NvdFirewallBlockJpaRepository blockRepo,
            NvdFirewallWhitelistJpaRepository whitelistRepo) {
        this.settingsRepo = settingsRepo;
        this.syncService = syncService;
        this.blockRepo = blockRepo;
        this.whitelistRepo = whitelistRepo;
    }

    // ── Settings ─────────────────────────────────────────────────────────

    @GetMapping
    public ResponseEntity<NvdFirewallSettingsXO> getSettings() {
        var entity = settingsRepo.findById(SETTINGS_ID).orElseGet(() -> {
            var d = new NvdFirewallSettingsEntity();
            return settingsRepo.save(d);
        });
        return ResponseEntity.ok(toXO(entity));
    }

    @PutMapping
    public ResponseEntity<NvdFirewallSettingsXO> updateSettings(@Valid @RequestBody NvdFirewallSettingsXO request) {
        var entity = settingsRepo.findById(SETTINGS_ID).orElseGet(NvdFirewallSettingsEntity::new);
        entity.setEnabled(request.enabled());
        entity.setApiKey(request.apiKey());
        entity.setCvssThreshold(request.cvssThreshold());
        return ResponseEntity.ok(toXO(settingsRepo.save(entity)));
    }

    // ── Sync ─────────────────────────────────────────────────────────────

    @GetMapping("/sync-state")
    public ResponseEntity<NvdSyncStateXO> getSyncState() {
        return ResponseEntity.ok(toXO(syncService.getState()));
    }

    @PostMapping("/sync")
    public ResponseEntity<Map<String, Object>> triggerSync(
            @RequestParam(defaultValue = "auto") String mode) {
        boolean started;
        String effectiveMode;
        switch (mode.toLowerCase()) {
            case "full" -> { started = syncService.triggerFullSync(); effectiveMode = "FULL"; }
            case "delta" -> { started = syncService.triggerDeltaSync(); effectiveMode = "DELTA"; }
            default -> {
                // auto: delta if we have prior success, else full
                var state = syncService.getState();
                if (state.getLastSuccessAt() != null) {
                    started = syncService.triggerDeltaSync();
                    effectiveMode = "DELTA";
                } else {
                    started = syncService.triggerFullSync();
                    effectiveMode = "FULL";
                }
            }
        }
        if (!started) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "SYNC_ALREADY_RUNNING", "message", "A sync is already in progress."));
        }
        return ResponseEntity.accepted().body(Map.of("mode", effectiveMode, "startedAt", Instant.now()));
    }

    // ── Blocks log ──────────────────────────────────────────────────────

    @GetMapping("/blocks")
    public ResponseEntity<List<NvdBlockXO>> listBlocks(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        var results = blockRepo.findAllByOrderByTimestampDesc(PageRequest.of(page, Math.min(size, 200)));
        return ResponseEntity.ok(results.stream().map(this::toXO).toList());
    }

    // ── Whitelist ───────────────────────────────────────────────────────

    @GetMapping("/whitelist")
    public ResponseEntity<List<NvdWhitelistEntryXO>> listWhitelist() {
        return ResponseEntity.ok(whitelistRepo.findAll().stream().map(this::toXO).toList());
    }

    @PostMapping("/whitelist")
    public ResponseEntity<NvdWhitelistEntryXO> addWhitelist(@Valid @RequestBody NvdWhitelistEntryXO request) {
        var existing = whitelistRepo.findByEntryTypeAndValue(request.entryType(), request.value());
        if (existing.isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        var e = new NvdFirewallWhitelistEntity();
        e.setEntryType(request.entryType());
        e.setValue(request.value());
        e.setReason(request.reason());
        e.setAddedAt(Instant.now());
        e.setAddedBy(currentUser());
        return ResponseEntity.ok(toXO(whitelistRepo.save(e)));
    }

    @DeleteMapping("/whitelist/{id}")
    public ResponseEntity<Void> deleteWhitelist(@PathVariable Long id) {
        if (!whitelistRepo.existsById(id)) return ResponseEntity.notFound().build();
        whitelistRepo.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    // ── Mappers ─────────────────────────────────────────────────────────

    private NvdFirewallSettingsXO toXO(NvdFirewallSettingsEntity e) {
        return new NvdFirewallSettingsXO(e.isEnabled(), e.getApiKey(), e.getCvssThreshold());
    }

    private NvdSyncStateXO toXO(NvdSyncStateEntity s) {
        return new NvdSyncStateXO(
                s.getStatus(), s.getMode(), s.getStartedAt(),
                s.getLastSyncAt(), s.getLastSuccessAt(),
                s.getTotalCves(), s.getSyncedCves(), s.getTotalResults(),
                s.getErrorMessage());
    }

    private NvdBlockXO toXO(NvdFirewallBlockEntity b) {
        return new NvdBlockXO(
                b.getId(), b.getTimestamp(), b.getUserId(), b.getRepository(),
                b.getPath(), b.getComponentKey(), b.getMaxCvssScore(), b.getCveDetails());
    }

    private NvdWhitelistEntryXO toXO(NvdFirewallWhitelistEntity w) {
        return new NvdWhitelistEntryXO(
                w.getId(), w.getEntryType(), w.getValue(), w.getReason(),
                w.getAddedAt(), w.getAddedBy());
    }

    private static String currentUser() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : "anonymous";
    }
}
