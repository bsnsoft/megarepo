package de.bsnsoft.megarepo.rest.controller;

import de.bsnsoft.megarepo.core.exception.NotFoundException;
import de.bsnsoft.megarepo.core.firewall.FirewallQuarantineReason;
import de.bsnsoft.megarepo.core.firewall.FirewallQuarantineResolution;
import de.bsnsoft.megarepo.core.firewall.FirewallQuarantineState;
import de.bsnsoft.megarepo.database.entity.FirewallPolicyEntity;
import de.bsnsoft.megarepo.database.repository.FirewallPolicyJpaRepository;
import de.bsnsoft.megarepo.database.repository.FirewallQuarantineJpaRepository;
import de.bsnsoft.megarepo.repository.firewall.quarantine.FirewallQuarantineEntry;
import de.bsnsoft.megarepo.repository.firewall.quarantine.QuarantineDecision;
import de.bsnsoft.megarepo.repository.firewall.quarantine.QuarantineMapper;
import de.bsnsoft.megarepo.repository.firewall.quarantine.QuarantineQuery;
import de.bsnsoft.megarepo.repository.firewall.quarantine.QuarantineService;
import de.bsnsoft.megarepo.rest.dto.common.PageResponse;
import de.bsnsoft.megarepo.rest.dto.firewall.FirewallQuarantineDecisionXO;
import de.bsnsoft.megarepo.rest.dto.firewall.FirewallQuarantineEntryXO;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The quarantine queue over HTTP: read it, release an entry, block one.
 *
 * <h2>What is in the queue, and what never reaches it</h2>
 *
 * Quarantine is rule-driven and never blanket. Three verdicts produce an entry —
 * a component that is not old enough, one no advisory source knows anything
 * about, and an evaluation that did not finish on a fail-closed repository — and
 * all three are expected to resolve on their own. A critical advisory or a
 * known-malicious package is refused outright and produces no entry at all,
 * which is why this screen has no "release" button next to a credential stealer.
 *
 * <h2>Only deliberate decisions, and no deletion</h2>
 *
 * The two write endpoints record {@code MANUAL_RELEASE} and {@code MANUAL_BLOCK}
 * against the authenticated caller, with a note that is required rather than
 * optional: the resolution says who decided, and "why" is the whole value of the
 * row six months later. An illegal transition — releasing something the sweep
 * already released — comes out of the state machine as an
 * {@code IllegalStateException} and reaches the client as a 409 through
 * {@code GlobalExceptionHandler}.
 *
 * <p>There is no {@code DELETE}. A decided entry is the audit trail of what the
 * firewall did to somebody's build, and a queue whose rows can be made to
 * disappear cannot answer whether the thing that made a component unacceptable
 * actually went away. Old rows age out through
 * {@code megarepo.firewall.quarantine.retention}, not through this API.
 *
 * <h2>Access</h2>
 *
 * Under {@code /api/v1/admin/firewall/**}, which {@code SecurityConfig}
 * restricts to {@code nx-admin} — the queue enumerates components this instance
 * is refusing and offers a button that serves them. Authorization lives in the
 * filter chain, not in {@code @PreAuthorize}: method security is not enabled, so
 * the annotation would be decoration.
 */
@RestController
@RequestMapping(FirewallQuarantineController.BASE_PATH)
public class FirewallQuarantineController {

    static final String BASE_PATH = "/api/v1/admin/firewall/quarantine";

    private static final Logger log = LoggerFactory.getLogger(FirewallQuarantineController.class);

    /** Fixed like {@code FirewallAdminController}'s, so a continuation token stays meaningful. */
    private static final int PAGE_SIZE = 50;

    /** Key the decision snapshot stores advisory ids under (see {@code QuarantineMapper}). */
    private static final String SNAPSHOT_ADVISORY_IDS = "advisoryIds";

    /** Key the decision snapshot stores the deciding policy's name under. */
    private static final String SNAPSHOT_POLICY_NAME = "policyName";

    private final QuarantineService quarantine;
    private final FirewallQuarantineJpaRepository entries;
    private final QuarantineMapper mapper;
    private final FirewallPolicyJpaRepository policies;

    /**
     * @param entries and {@code mapper} serve the single-entry read only. The
     *     service's own interface is built around the queue and the request path
     *     and has no by-id accessor; adding one would be a contract change for
     *     the sake of a detail view, and reading one row through the repository
     *     it is stored in costs nothing else
     */
    public FirewallQuarantineController(
            QuarantineService quarantine,
            FirewallQuarantineJpaRepository entries,
            QuarantineMapper mapper,
            FirewallPolicyJpaRepository policies) {
        this.quarantine = quarantine;
        this.entries = entries;
        this.mapper = mapper;
        this.policies = policies;
    }

    // ── Reading ─────────────────────────────────────────────────────────

    /**
     * The queue, newest first.
     *
     * <p>All filters are optional and each one means "do not filter on this".
     * {@code state} defaults to nothing rather than to {@code QUARANTINED}: the
     * released and blocked rows are the audit trail, and a list that silently
     * hid them would make the retention window invisible. The UI asks for
     * {@code state=QUARANTINED} when it wants the working queue.
     */
    @GetMapping
    public ResponseEntity<PageResponse<FirewallQuarantineEntryXO>> list(
            @RequestParam(required = false) FirewallQuarantineState state,
            @RequestParam(required = false) UUID repositoryId,
            @RequestParam(required = false) FirewallQuarantineReason reason,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String continuationToken) {

        int pageNumber = decodePage(continuationToken);
        Page<FirewallQuarantineEntry> page = quarantine.queue(
                new QuarantineQuery(state, repositoryId, reason, search),
                PageRequest.of(pageNumber, PAGE_SIZE));

        Map<UUID, String> policyNames = policyNames(page.getContent());
        List<FirewallQuarantineEntryXO> items =
                page.getContent().stream().map(entry -> toXO(entry, policyNames)).toList();
        String next = page.hasNext() ? encodePage(pageNumber + 1) : null;
        return ResponseEntity.ok(new PageResponse<>(items, next));
    }

    /** Counts per state, for the admin overview and the navigation badge. */
    @GetMapping("/summary")
    public ResponseEntity<QuarantineOverviewXO> summary() {
        QuarantineService.QuarantineSummary summary = quarantine.summary();
        return ResponseEntity.ok(new QuarantineOverviewXO(
                summary.quarantined(), summary.released(), summary.blocked()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<FirewallQuarantineEntryXO> get(@PathVariable UUID id) {
        FirewallQuarantineEntry entry = entries.findById(id)
                .map(mapper::toEntry)
                .orElseThrow(() -> new NotFoundException("Quarantine entry not found: " + id));
        return ResponseEntity.ok(toXO(entry, policyNames(List.of(entry))));
    }

    // ── Deciding ────────────────────────────────────────────────────────

    /**
     * Releases a held component so it may be served.
     *
     * @param body {@code note} is required — see
     *     {@link FirewallQuarantineDecisionXO}
     */
    @PostMapping("/{id}/release")
    public ResponseEntity<FirewallQuarantineEntryXO> release(
            @PathVariable UUID id, @Valid @RequestBody FirewallQuarantineDecisionXO body) {

        FirewallQuarantineEntry released = quarantine.release(
                id,
                QuarantineDecision.manual(
                        FirewallQuarantineResolution.MANUAL_RELEASE, currentUser(), body.note()));
        log.info("Quarantine entry {} ({}) released by {}",
                id, released.componentKey(), currentUser());
        return ResponseEntity.ok(toXO(released, policyNames(List.of(released))));
    }

    /**
     * Moves a held component to {@code BLOCKED}: refused, and no longer
     * re-evaluated in the hope of a different answer.
     */
    @PostMapping("/{id}/block")
    public ResponseEntity<FirewallQuarantineEntryXO> block(
            @PathVariable UUID id, @Valid @RequestBody FirewallQuarantineDecisionXO body) {

        FirewallQuarantineEntry blocked = quarantine.block(
                id,
                QuarantineDecision.manual(
                        FirewallQuarantineResolution.MANUAL_BLOCK, currentUser(), body.note()));
        log.info("Quarantine entry {} ({}) blocked by {}", id, blocked.componentKey(), currentUser());
        return ResponseEntity.ok(toXO(blocked, policyNames(List.of(blocked))));
    }

    /**
     * The quarantine page's header.
     *
     * @param quarantined how many components are being held right now — the only
     *     number that is a queue rather than a log
     */
    public record QuarantineOverviewXO(long quarantined, long released, long blocked) {}

    // ── Helpers ─────────────────────────────────────────────────────────

    /**
     * Live policy names for the entries on this page.
     *
     * <p>Read from the policy table rather than from the decision snapshot so a
     * renamed policy shows under its current name. The snapshot's copy is the
     * fallback, and it is the only answer left once the policy has been deleted —
     * which is exactly when an operator most wants to know what decided this.
     */
    private Map<UUID, String> policyNames(List<FirewallQuarantineEntry> page) {
        var ids = page.stream()
                .map(FirewallQuarantineEntry::policyId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> names = new HashMap<>();
        for (FirewallPolicyEntity policy : policies.findAllById(ids)) {
            names.put(policy.getId(), policy.getName());
        }
        return names;
    }

    private static FirewallQuarantineEntryXO toXO(
            FirewallQuarantineEntry entry, Map<UUID, String> policyNames) {

        Map<String, Object> evaluation = entry.evaluation();
        String policyName = entry.policyId() == null ? null : policyNames.get(entry.policyId());
        if (policyName == null) {
            policyName = asText(evaluation.get(SNAPSHOT_POLICY_NAME));
        }

        return new FirewallQuarantineEntryXO(
                entry.id(),
                entry.repositoryId(),
                entry.repositoryName(),
                entry.componentKey(),
                entry.path(),
                entry.state(),
                entry.reason(),
                entry.resolution(),
                entry.policyId(),
                policyName,
                advisoryIds(evaluation),
                evaluation,
                entry.firstSeen(),
                entry.lastSeen(),
                entry.hitCount(),
                entry.lastEvaluatedAt(),
                entry.nextEvaluationAt(),
                entry.decidedAt(),
                entry.decidedBy(),
                entry.decisionReason(),
                entry.exemptionId());
    }

    /**
     * The advisory ids out of the snapshot, hoisted so the list view does not
     * have to parse JSON to show the column operators sort by.
     */
    private static List<String> advisoryIds(Map<String, Object> evaluation) {
        Object raw = evaluation.get(SNAPSHOT_ADVISORY_IDS);
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().filter(Objects::nonNull).map(Object::toString).toList();
    }

    private static String asText(Object raw) {
        return raw == null ? null : raw.toString();
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
