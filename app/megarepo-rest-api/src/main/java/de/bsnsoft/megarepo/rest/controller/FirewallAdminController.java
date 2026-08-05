package de.bsnsoft.megarepo.rest.controller;

import de.bsnsoft.megarepo.core.exception.NotFoundException;
import de.bsnsoft.megarepo.core.exception.ValidationException;
import de.bsnsoft.megarepo.core.firewall.FirewallEffectiveState;
import de.bsnsoft.megarepo.core.firewall.FirewallMode;
import de.bsnsoft.megarepo.database.entity.FirewallEnforcementSettingsEntity;
import de.bsnsoft.megarepo.database.entity.FirewallRepositoryConfigEntity;
import de.bsnsoft.megarepo.database.entity.FirewallViolationEntity;
import de.bsnsoft.megarepo.database.entity.RepositoryEntity;
import de.bsnsoft.megarepo.database.repository.FirewallEnforcementSettingsJpaRepository;
import de.bsnsoft.megarepo.database.repository.FirewallRepositoryConfigJpaRepository;
import de.bsnsoft.megarepo.database.repository.FirewallViolationJpaRepository;
import de.bsnsoft.megarepo.database.repository.RepositoryJpaRepository;
import de.bsnsoft.megarepo.repository.firewall.FirewallAuditProperties;
import de.bsnsoft.megarepo.rest.dto.common.PageResponse;
import de.bsnsoft.megarepo.rest.dto.firewall.FirewallEnforcementUpdateXO;
import de.bsnsoft.megarepo.rest.dto.firewall.FirewallEnforcementXO;
import de.bsnsoft.megarepo.rest.dto.firewall.FirewallOverviewXO;
import de.bsnsoft.megarepo.rest.dto.firewall.FirewallRepositoryModeUpdateXO;
import de.bsnsoft.megarepo.rest.dto.firewall.FirewallRepositoryStateXO;
import de.bsnsoft.megarepo.rest.dto.firewall.FirewallStateSummaryXO;
import de.bsnsoft.megarepo.rest.dto.firewall.FirewallViolationXO;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The operator's control surface for the repository firewall: the global
 * enforcement switch, the per-repository mode, and the recorded findings the
 * decision to arm is based on.
 *
 * <h2>Why this exists</h2>
 *
 * The customer runs their own MegaRepo. Phase 1 observes and records; turning
 * that into actual blocking is their call, made on their evidence, at their
 * moment — not ours, and not a container restart with an edited environment
 * variable. Everything needed for that decision is here:
 * {@code GET /status} shows what the instance is doing right now,
 * {@code GET /violations} shows what it has been finding, and the two
 * {@code PUT}s change it.
 *
 * <h2>Access</h2>
 *
 * The mapping sits under {@code /api/v1/admin/firewall/**}, which
 * {@code SecurityConfig} restricts to the {@code nx-admin} role — this project
 * expresses authorization in the filter chain, not with {@code @PreAuthorize}
 * (method security is not enabled, so the annotation would be decoration). Two
 * separate reasons apply here: the violation log enumerates every vulnerable
 * component in every repository, and the switch below can stop every build in
 * the organisation. Plain {@code authenticated()}, what the rest of
 * {@code /api/v1/**} gets, covers neither.
 * {@code FirewallAdminControllerTest} asserts the mapping stays under that
 * prefix, because moving it would silently downgrade it.
 *
 * <h2>Arming is confirmed, disarming is not</h2>
 *
 * Both transitions that can start refusing downloads — switching enforcement on,
 * and moving a repository into {@link FirewallMode#QUARANTINE} — require the
 * caller to repeat an exact phrase. Reverting either does not. A guard on the
 * safe direction would be a guard that trains people to click through the
 * dangerous one, and an operator disarming a firewall that is breaking their
 * builds should not have to type anything.
 *
 * <p>The check lives here rather than in the Web UI because a dialog only
 * protects the one caller that shows it; {@code curl} and every future script
 * reach the same endpoint. See {@link #requiredConfirmation} for the phrases.
 */
@RestController
@RequestMapping(FirewallAdminController.BASE_PATH)
public class FirewallAdminController {

    static final String BASE_PATH = "/api/v1/admin/firewall";

    private static final Logger log = LoggerFactory.getLogger(FirewallAdminController.class);

    /** Phrase that must be repeated to turn the global enforcement switch on. */
    public static final String ENFORCEMENT_CONFIRMATION = "ENABLE ENFORCEMENT";

    /** Window the overview counts violations over. */
    private static final int VIOLATION_WINDOW_DAYS = 30;

    /** Fixed like {@code AuditController}'s, so a continuation token stays meaningful. */
    private static final int VIOLATION_PAGE_SIZE = 50;

    private final FirewallEnforcementSettingsJpaRepository enforcementRepo;
    private final FirewallRepositoryConfigJpaRepository configRepo;
    private final FirewallViolationJpaRepository violationRepo;
    private final RepositoryJpaRepository repositoryRepo;
    private final FirewallAuditProperties auditProperties;

    public FirewallAdminController(
            FirewallEnforcementSettingsJpaRepository enforcementRepo,
            FirewallRepositoryConfigJpaRepository configRepo,
            FirewallViolationJpaRepository violationRepo,
            RepositoryJpaRepository repositoryRepo,
            FirewallAuditProperties auditProperties) {
        this.enforcementRepo = enforcementRepo;
        this.configRepo = configRepo;
        this.violationRepo = violationRepo;
        this.repositoryRepo = repositoryRepo;
        this.auditProperties = auditProperties;
    }

    // ── Status ──────────────────────────────────────────────────────────

    /**
     * One consistent snapshot: the switch, every repository's effective state,
     * and the summary.
     *
     * <p>Deliberately one call rather than three. Assembled client-side from
     * separate responses, a slow or failed second request would leave the page
     * showing a switch from one moment beside modes from another — and the one
     * disagreement that matters ("Quarantine" next to a stale "enforcement on")
     * reads as protection that is not there.
     */
    @GetMapping("/status")
    public ResponseEntity<FirewallOverviewXO> status() {
        FirewallEnforcementSettingsEntity enforcement = enforcementRepo.current();
        boolean enabled = enforcement.isEnabled();

        Map<UUID, FirewallRepositoryConfigEntity> configs = new HashMap<>();
        for (FirewallRepositoryConfigEntity config : configRepo.findAll()) {
            configs.put(config.getRepositoryId(), config);
        }

        Map<UUID, Long> counts = new HashMap<>();
        Instant since = Instant.now().minus(Duration.ofDays(VIOLATION_WINDOW_DAYS));
        for (var row : violationRepo.countByRepositorySince(since)) {
            counts.put(row.getRepositoryId(), row.getViolations());
        }

        List<FirewallRepositoryStateXO> repositories = repositoryRepo.findAll().stream()
                .map(repository -> toXO(repository, configs.get(repository.getId()), enabled, counts))
                .sorted(Comparator.comparing(FirewallRepositoryStateXO::repositoryName))
                .toList();

        return ResponseEntity.ok(new FirewallOverviewXO(
                toXO(enforcement),
                VIOLATION_WINDOW_DAYS,
                summarize(repositories),
                repositories));
    }

    // ── Global enforcement switch ───────────────────────────────────────

    @GetMapping("/enforcement")
    public ResponseEntity<FirewallEnforcementXO> getEnforcement() {
        return ResponseEntity.ok(toXO(enforcementRepo.current()));
    }

    /**
     * Arm or disarm the instance.
     *
     * <p>Arming needs {@link #ENFORCEMENT_CONFIRMATION} in {@code confirmation};
     * disarming needs nothing. The phrase is only demanded on the transition, so
     * re-sending {@code enabled: true} on an already-armed instance stays
     * idempotent — the guard is on the change of behaviour, not on the state.
     */
    @PutMapping("/enforcement")
    public ResponseEntity<FirewallEnforcementXO> setEnforcement(
            @Valid @RequestBody FirewallEnforcementUpdateXO request) {

        FirewallEnforcementSettingsEntity settings = enforcementRepo.current();
        boolean desired = Boolean.TRUE.equals(request.enabled());

        if (desired && !settings.isEnabled()) {
            requireConfirmation(request.confirmation(), ENFORCEMENT_CONFIRMATION);
        }

        if (settings.isEnabled() == desired) {
            return ResponseEntity.ok(toXO(settings));
        }

        settings.setId(FirewallEnforcementSettingsEntity.SINGLETON_ID);
        settings.setEnabled(desired);
        settings.setUpdatedAt(Instant.now());
        settings.setUpdatedBy(currentUser());
        FirewallEnforcementSettingsEntity saved = enforcementRepo.save(settings);

        // WARN on both edges: arming can break every build in the organisation,
        // and disarming silently removes a control someone is relying on.
        log.warn(
                "Repository firewall enforcement switched {} by {}",
                desired ? "ON — repositories in QUARANTINE now block downloads" : "OFF — nothing blocks",
                saved.getUpdatedBy());

        return ResponseEntity.ok(toXO(saved));
    }

    // ── Per-repository mode ─────────────────────────────────────────────

    /**
     * Set one repository's mode, creating its {@code firewall_repository_config}
     * row if it has none.
     *
     * <p>Moving into {@link FirewallMode#QUARANTINE} requires the confirmation
     * phrase for that repository by name (see
     * {@link FirewallRepositoryModeUpdateXO}). {@code failMode} and
     * {@code policyId} are left exactly as they are: this endpoint changes one
     * thing, and an upsert that quietly reset a fail mode to its default would be
     * a second, invisible change to how the repository behaves.
     */
    @PutMapping("/repositories/{repositoryId}")
    public ResponseEntity<FirewallRepositoryStateXO> setRepositoryMode(
            @PathVariable UUID repositoryId, @Valid @RequestBody FirewallRepositoryModeUpdateXO request) {

        RepositoryEntity repository = repositoryRepo
                .findById(repositoryId)
                .orElseThrow(() -> new NotFoundException("Repository not found: " + repositoryId));

        Optional<FirewallRepositoryConfigEntity> existing = configRepo.findById(repositoryId);
        FirewallMode current = existing.map(FirewallRepositoryConfigEntity::getMode).orElse(null);

        if (request.mode() == FirewallMode.QUARANTINE && current != FirewallMode.QUARANTINE) {
            requireConfirmation(request.confirmation(), requiredConfirmation(repository.getName()));
        }

        FirewallRepositoryConfigEntity config = existing.orElseGet(() -> {
            FirewallRepositoryConfigEntity fresh = new FirewallRepositoryConfigEntity();
            fresh.setRepositoryId(repositoryId);
            fresh.setCreatedAt(Instant.now());
            return fresh;
        });
        config.setMode(request.mode());
        config.setUpdatedAt(Instant.now());
        FirewallRepositoryConfigEntity saved = configRepo.save(config);

        boolean enabled = enforcementRepo.current().isEnabled();
        log.info(
                "Repository firewall mode for '{}' set to {} by {} (effective: {})",
                repository.getName(),
                saved.getMode(),
                currentUser(),
                FirewallEffectiveState.resolve(enabled, saved.getMode()));

        Instant since = Instant.now().minus(Duration.ofDays(VIOLATION_WINDOW_DAYS));
        Map<UUID, Long> counts = new HashMap<>();
        for (var row : violationRepo.countByRepositorySince(since)) {
            counts.put(row.getRepositoryId(), row.getViolations());
        }

        return ResponseEntity.ok(toXO(repository, saved, enabled, counts));
    }

    /** The phrase that arms {@code repositoryName}. */
    public static String requiredConfirmation(String repositoryName) {
        return "QUARANTINE " + repositoryName;
    }

    // ── Recorded findings ───────────────────────────────────────────────

    /**
     * The violation log, newest first, optionally scoped to one repository.
     *
     * <p>Paged like {@code AuditController}: fixed page size, opaque
     * continuation token. This is the evidence surface — the operator reads it
     * to decide whether the findings are real before arming anything.
     */
    @GetMapping("/violations")
    public ResponseEntity<PageResponse<FirewallViolationXO>> listViolations(
            @RequestParam(required = false) UUID repositoryId,
            @RequestParam(required = false) String continuationToken) {

        int pageNumber = decodePage(continuationToken);
        PageRequest pageable = PageRequest.of(pageNumber, VIOLATION_PAGE_SIZE);

        Page<FirewallViolationEntity> page = repositoryId != null
                ? violationRepo.findByRepositoryIdOrderByOccurredAtDesc(repositoryId, pageable)
                : violationRepo.findAllByOrderByOccurredAtDesc(pageable);

        List<FirewallViolationXO> items =
                page.getContent().stream().map(FirewallAdminController::toXO).toList();
        String next = page.hasNext() ? encodePage(pageNumber + 1) : null;
        return ResponseEntity.ok(new PageResponse<>(items, next));
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    /**
     * @throws ValidationException (400) naming the expected phrase. Stating it
     *     in the error is not a weakening of the guard: the guard is against a
     *     reflex, not against a determined caller, and a 400 that only says "bad
     *     confirmation" sends an operator to the source code mid-incident.
     */
    private static void requireConfirmation(String provided, String expected) {
        if (provided == null || !expected.equals(provided.trim())) {
            throw new ValidationException(
                    "This turns on blocking and can fail builds. To confirm, send confirmation=\""
                            + expected + "\".");
        }
    }

    private FirewallRepositoryStateXO toXO(
            RepositoryEntity repository,
            FirewallRepositoryConfigEntity config,
            boolean enforcementEnabled,
            Map<UUID, Long> violationCounts) {

        FirewallMode mode = config != null ? config.getMode() : auditProperties.defaultMode();
        return new FirewallRepositoryStateXO(
                repository.getId(),
                repository.getName(),
                repository.getFormat(),
                repository.getType(),
                mode,
                config != null ? config.getFailMode() : null,
                FirewallEffectiveState.resolve(enforcementEnabled, mode),
                config != null,
                violationCounts.getOrDefault(repository.getId(), 0L),
                config != null ? config.getUpdatedAt() : null);
    }

    private static FirewallEnforcementXO toXO(FirewallEnforcementSettingsEntity settings) {
        return new FirewallEnforcementXO(
                settings.isEnabled(),
                settings.getUpdatedAt(),
                settings.getUpdatedBy(),
                ENFORCEMENT_CONFIRMATION);
    }

    private static FirewallViolationXO toXO(FirewallViolationEntity violation) {
        String[] advisoryIds = violation.getAdvisoryIds();
        return new FirewallViolationXO(
                violation.getId(),
                violation.getRepositoryId(),
                violation.getRepositoryName(),
                violation.getPurl(),
                violation.getPolicyId(),
                violation.getRuleType(),
                violation.getAction(),
                advisoryIds == null ? List.of() : Arrays.asList(advisoryIds),
                violation.getOccurredAt(),
                violation.getRequestContext());
    }

    private static FirewallStateSummaryXO summarize(List<FirewallRepositoryStateXO> repositories) {
        int blocking = 0;
        int quarantineNotEnforced = 0;
        int observing = 0;
        int notEvaluated = 0;
        for (FirewallRepositoryStateXO repository : repositories) {
            switch (repository.effectiveState()) {
                case BLOCKING -> blocking++;
                case QUARANTINE_NOT_ENFORCED -> quarantineNotEnforced++;
                case OBSERVING -> observing++;
                case NOT_EVALUATED -> notEvaluated++;
            }
        }
        return new FirewallStateSummaryXO(blocking, quarantineNotEnforced, observing, notEvaluated);
    }

    private static String currentUser() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : "anonymous";
    }

    private static int decodePage(String continuationToken) {
        if (continuationToken == null || continuationToken.isBlank()) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(new String(Base64.getDecoder().decode(continuationToken))));
        } catch (Exception e) {
            return 0;
        }
    }

    private static String encodePage(int page) {
        return Base64.getEncoder().encodeToString(String.valueOf(page).getBytes());
    }
}
