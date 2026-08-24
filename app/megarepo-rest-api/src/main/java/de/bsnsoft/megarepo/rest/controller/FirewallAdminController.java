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
import de.bsnsoft.megarepo.repository.firewall.FirewallEnforcementSettingsService;
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
 *
 * <h2>The switch is read and written through the service that enforces it</h2>
 *
 * Both go through {@link FirewallEnforcementSettingsService} and never straight
 * to {@code firewall_enforcement_settings}, because that service — not this row —
 * is what the download path actually consults. Writing past it produced three
 * failures at once, none of which either side's own tests could see:
 *
 * <ul>
 *   <li>the row was written without {@code configured = true}, and the service
 *       ignores a row that nobody has claimed, so the switch was a permanent
 *       no-op while this endpoint reported it as on;</li>
 *   <li>the service holds the switch in memory for
 *       {@code settings-refresh-interval}, so even a correctly written row took
 *       up to that long to be noticed — an operator watching their build still
 *       succeed concludes the switch is broken;</li>
 *   <li>{@code enforcing_since}, the watermark that decides which components
 *       count as already present, was never stamped.</li>
 * </ul>
 *
 * <p>Reading through the service matters for the same reason in the other
 * direction. On an installation configured through
 * {@code megarepo.firewall.enforcement.enabled} the row still says
 * {@code configured = false}, and reading the row would report "off" on an
 * instance that is blocking — and would make the one-step disarm silently do
 * nothing, because the desired state would already appear to be the current one.
 * {@link FirewallEnforcementSettingsService#enforcementEnabled()} answers with
 * the value enforcement uses, which is the only answer this API may give.
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

    private final FirewallEnforcementSettingsService enforcementSettings;
    private final FirewallEnforcementSettingsJpaRepository enforcementRepo;
    private final FirewallRepositoryConfigJpaRepository configRepo;
    private final FirewallViolationJpaRepository violationRepo;
    private final RepositoryJpaRepository repositoryRepo;
    private final FirewallAuditProperties auditProperties;

    /**
     * @param enforcementSettings owns the switch: its value, its persistence and
     *     the in-memory copy the download path reads
     * @param enforcementRepo read only, and only for the audit metadata
     *     ({@code updated_at}, {@code updated_by}) the service does not expose.
     *     The switch's value never comes from here — see the class comment.
     */
    public FirewallAdminController(
            FirewallEnforcementSettingsService enforcementSettings,
            FirewallEnforcementSettingsJpaRepository enforcementRepo,
            FirewallRepositoryConfigJpaRepository configRepo,
            FirewallViolationJpaRepository violationRepo,
            RepositoryJpaRepository repositoryRepo,
            FirewallAuditProperties auditProperties) {
        this.enforcementSettings = enforcementSettings;
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
        boolean enabled = enforcementSettings.enforcementEnabled();

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
                toXO(enforcement, enabled),
                VIOLATION_WINDOW_DAYS,
                summarize(repositories),
                repositories));
    }

    // ── Global enforcement switch ───────────────────────────────────────

    @GetMapping("/enforcement")
    public ResponseEntity<FirewallEnforcementXO> getEnforcement() {
        return ResponseEntity.ok(
                toXO(enforcementRepo.current(), enforcementSettings.enforcementEnabled()));
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

        boolean current = enforcementSettings.enforcementEnabled();
        boolean desired = Boolean.TRUE.equals(request.enabled());

        if (desired && !current) {
            requireConfirmation(request.confirmation(), ENFORCEMENT_CONFIRMATION);
        }

        if (current == desired) {
            return ResponseEntity.ok(toXO(enforcementRepo.current(), current));
        }

        // The service persists the row, claims it with configured = true, stamps
        // the grandfathering watermark and republishes its in-memory copy, so the
        // next download on this node already sees the new state. It also logs the
        // transition at WARN on both edges — arming can break every build in the
        // organisation, disarming silently removes a control someone relies on —
        // which is why nothing is logged again here.
        FirewallEnforcementSettingsEntity saved = enforcementSettings.save(desired, currentUser());

        // save() claims the row (configured = true), so from here the row's own
        // flag *is* the effective value and no second read is needed to say what
        // the instance is now doing.
        return ResponseEntity.ok(toXO(saved, saved.isEnabled()));
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

        boolean enabled = enforcementSettings.enforcementEnabled();
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

    /**
     * @param effectiveEnabled the value {@link FirewallEnforcementSettingsService}
     *     answers with, not {@code settings.isEnabled()}. The two differ on an
     *     installation that set the switch through the deployment property, and
     *     reporting the row there would describe an instance that is not the one
     *     serving downloads.
     */
    private static FirewallEnforcementXO toXO(
            FirewallEnforcementSettingsEntity settings, boolean effectiveEnabled) {
        return new FirewallEnforcementXO(
                effectiveEnabled,
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
