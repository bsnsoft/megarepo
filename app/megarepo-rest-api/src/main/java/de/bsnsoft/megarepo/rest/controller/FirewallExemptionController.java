package de.bsnsoft.megarepo.rest.controller;

import de.bsnsoft.megarepo.core.exception.AccessDeniedException;
import de.bsnsoft.megarepo.core.exception.NotFoundException;
import de.bsnsoft.megarepo.core.firewall.FirewallApiPaths;
import de.bsnsoft.megarepo.core.firewall.FirewallExemptionState;
import de.bsnsoft.megarepo.database.entity.RepositoryEntity;
import de.bsnsoft.megarepo.database.repository.RepositoryJpaRepository;
import de.bsnsoft.megarepo.repository.firewall.exemption.ExemptionProperties;
import de.bsnsoft.megarepo.repository.firewall.exemption.ExemptionQuery;
import de.bsnsoft.megarepo.repository.firewall.exemption.ExemptionRequest;
import de.bsnsoft.megarepo.repository.firewall.exemption.ExemptionService;
import de.bsnsoft.megarepo.repository.firewall.exemption.FirewallExemption;
import de.bsnsoft.megarepo.rest.dto.common.PageResponse;
import de.bsnsoft.megarepo.rest.dto.firewall.FirewallExemptionDecisionXO;
import de.bsnsoft.megarepo.rest.dto.firewall.FirewallExemptionRequestXO;
import de.bsnsoft.megarepo.rest.dto.firewall.FirewallExemptionXO;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The exemption workflow over HTTP: ask, decide, list.
 *
 * <p><b>The word is "exemption"</b> — in the path, in every field name, in every
 * message this class produces. "waiver" and "whitelist" appear nowhere; the
 * second is what the V8 feature was called, and the V8 feature is precisely the
 * one whose behaviour nobody could reconstruct a year later.
 *
 * <h2>Access: one endpoint is deliberately not for administrators only</h2>
 *
 * Authorization in this project lives in {@code SecurityConfig}'s filter chain,
 * not in {@code @PreAuthorize} — method security is not enabled, so the
 * annotation would be decoration that authorizes everyone. The chain gives
 * {@code /api/v1/firewall/exemptions/**} the {@code nx-admin} role, with one
 * exception written next to it: {@code POST} on the collection is
 * {@code authenticated()}, because the customer's requirement is that a
 * developer who hits a 403 can ask for an exemption from the block page rather
 * than open a ticket. An exemption process that starts with a support ticket is
 * a process people route around by copying the artifact somewhere else.
 *
 * <p>A request changes nothing. It is created {@code REQUESTED} and lets no
 * download through until an approver — who does need the role — acts. The
 * approve/reject/revoke endpoints sit under the admin rule.
 *
 * <p>{@code megarepo.firewall.exemption.self-service-requests} can close even
 * that. It is a runtime property rather than a fixed rule, so it cannot live in
 * the filter chain; {@link #requireRequestPermission()} enforces it here and
 * answers 403 for a non-administrator when it is off.
 *
 * <h2>Approval takes an explicit expiry decision</h2>
 *
 * {@code expiresAt: null} means never, and the API does not read an omitted
 * field as "use the default". An exemption that never expires is the V8
 * whitelist's defining flaw, and it should be something somebody said rather
 * than something they left out. {@link #summary()} serves
 * {@code default-validity} so the UI can pre-fill the bounded answer.
 */
@RestController
@RequestMapping(FirewallExemptionController.BASE_PATH)
public class FirewallExemptionController {

    /**
     * Also the target of the link a firewall 403 carries.
     *
     * <p>Defined from {@link FirewallApiPaths#EXEMPTIONS} rather than spelled out
     * here: {@code FirewallBlockResponse} builds that link in
     * {@code megarepo-repository}, which cannot see this class, and the path has to
     * be one string or the 403 eventually points somewhere that no longer exists.
     */
    public static final String BASE_PATH = FirewallApiPaths.EXEMPTIONS;

    /** Fixed like {@code FirewallAdminController}'s, so a continuation token stays meaningful. */
    private static final int PAGE_SIZE = 50;

    /** Authority the {@code nx-admin} role reaches the security context as. */
    private static final String ADMIN_AUTHORITY = "ROLE_nx-admin";

    private final ExemptionService exemptions;
    private final ExemptionProperties properties;
    private final RepositoryJpaRepository repositories;

    public FirewallExemptionController(
            ExemptionService exemptions,
            ExemptionProperties properties,
            RepositoryJpaRepository repositories) {
        this.exemptions = exemptions;
        this.properties = properties;
        this.repositories = repositories;
    }

    // ── Requesting ──────────────────────────────────────────────────────

    /**
     * Files a request. Created {@code REQUESTED}; nothing is let through yet.
     *
     * <p>201 rather than 200: a resource was created, and the developer who filed
     * it needs its id to follow what happened to their request.
     */
    @PostMapping
    public ResponseEntity<FirewallExemptionXO> request(
            @Valid @RequestBody FirewallExemptionRequestXO body) {

        requireRequestPermission();
        FirewallExemption created = exemptions.request(new ExemptionRequest(
                body.componentKey(),
                body.scope(),
                body.repositoryId(),
                body.ruleType(),
                body.advisoryIds(),
                body.requestedExpiry(),
                body.justification(),
                currentUser()));
        return ResponseEntity.status(HttpStatus.CREATED).body(toXO(created, Instant.now()));
    }

    // ── Deciding ────────────────────────────────────────────────────────

    /**
     * Grants it.
     *
     * @param body {@code expiresAt} null means never — a decision, not a default.
     *     Beyond {@code max-validity} is refused as a typo (400), and an illegal
     *     transition is a 409 through {@code GlobalExceptionHandler}
     */
    @PostMapping("/{id}/approve")
    public ResponseEntity<FirewallExemptionXO> approve(
            @PathVariable UUID id, @Valid @RequestBody(required = false) FirewallExemptionDecisionXO body) {

        FirewallExemptionDecisionXO decision = body == null ? new FirewallExemptionDecisionXO(null, null) : body;
        FirewallExemption approved =
                exemptions.approve(id, currentUser(), decision.note(), decision.expiresAt());
        return ResponseEntity.ok(toXO(approved, Instant.now()));
    }

    /** Refuses it. Kept, so the next requester can see it was asked before. */
    @PostMapping("/{id}/reject")
    public ResponseEntity<FirewallExemptionXO> reject(
            @PathVariable UUID id, @Valid @RequestBody(required = false) FirewallExemptionDecisionXO body) {

        String note = body == null ? null : body.note();
        return ResponseEntity.ok(toXO(exemptions.reject(id, currentUser(), note), Instant.now()));
    }

    /**
     * Withdraws an approved exemption before it expires.
     *
     * <p>Distinct from deleting it — which would destroy the record of a decision
     * that was live in production — and from backdating its expiry, which would
     * make the log claim it lapsed by itself. There is no DELETE on this
     * resource for exactly that reason.
     */
    @PostMapping("/{id}/revoke")
    public ResponseEntity<FirewallExemptionXO> revoke(
            @PathVariable UUID id, @Valid @RequestBody(required = false) FirewallExemptionDecisionXO body) {

        String note = body == null ? null : body.note();
        return ResponseEntity.ok(toXO(exemptions.revoke(id, currentUser(), note), Instant.now()));
    }

    // ── Reading ─────────────────────────────────────────────────────────

    /**
     * The management list, newest request first.
     *
     * @param repositoryId an exact match on the column: global exemptions, which
     *     also apply in that repository, are <em>not</em> included. A management
     *     list and an applicability check are different questions, and conflating
     *     them is how an operator revokes an exemption they thought was local
     */
    @GetMapping
    public ResponseEntity<PageResponse<FirewallExemptionXO>> list(
            @RequestParam(required = false) FirewallExemptionState state,
            @RequestParam(required = false) UUID repositoryId,
            @RequestParam(required = false) String search,
            @RequestParam(required = false, defaultValue = "false") boolean expiringOnly,
            @RequestParam(required = false) String continuationToken) {

        int pageNumber = decodePage(continuationToken);
        Page<FirewallExemption> page = exemptions.list(
                new ExemptionQuery(state, repositoryId, search, expiringOnly),
                PageRequest.of(pageNumber, PAGE_SIZE));

        Instant now = Instant.now();
        Map<UUID, String> names = repositoryNames(page.getContent());
        List<FirewallExemptionXO> items =
                page.getContent().stream().map(exemption -> toXO(exemption, now, names)).toList();
        String next = page.hasNext() ? encodePage(pageNumber + 1) : null;
        return ResponseEntity.ok(new PageResponse<>(items, next));
    }

    @GetMapping("/{id}")
    public ResponseEntity<FirewallExemptionXO> get(@PathVariable UUID id) {
        FirewallExemption exemption = exemptions
                .find(id)
                .orElseThrow(() -> new NotFoundException("Exemption not found: " + id));
        return ResponseEntity.ok(toXO(exemption, Instant.now()));
    }

    /**
     * Counts per state, plus the two settings a client cannot guess.
     *
     * <p>{@code defaultValidity} is here because the approval dialog has to
     * pre-fill it: an approver who is offered "never" as the path of least
     * resistance takes it, which is how the V8 whitelist filled up.
     * {@code legacy} counts the rows migration V18 carried over, which the UI
     * marks so an operator can replace them with purl-based exemptions.
     */
    @GetMapping("/summary")
    public ResponseEntity<ExemptionOverviewXO> summary() {
        ExemptionService.ExemptionSummary summary = exemptions.summary();
        return ResponseEntity.ok(new ExemptionOverviewXO(
                summary.requested(),
                summary.approved(),
                summary.rejected(),
                summary.expired(),
                summary.revoked(),
                summary.legacy(),
                properties.defaultValidity(),
                properties.maxValidity(),
                properties.selfServiceRequests()));
    }

    /**
     * The Exemptions page's header.
     *
     * @param legacy rows carried over from the V8 whitelist by V18
     * @param defaultValidity what the approval dialog pre-fills, never what the
     *     API applies on its own
     * @param maxValidity the longest bounded expiry the API accepts; an explicit
     *     "never" is a different statement and is unaffected
     * @param selfServiceRequests whether a non-administrator may file a request
     */
    public record ExemptionOverviewXO(
            long requested,
            long approved,
            long rejected,
            long expired,
            long revoked,
            long legacy,
            Duration defaultValidity,
            Duration maxValidity,
            boolean selfServiceRequests) {}

    // ── Helpers ─────────────────────────────────────────────────────────

    /**
     * @throws AccessDeniedException (403) when self-service is off and the caller
     *     is not an administrator. Not a filter-chain rule because the answer
     *     depends on a property an operator can change at deploy time, and a
     *     chain that has to be rebuilt to reflect configuration is a chain that
     *     will not be
     */
    private void requireRequestPermission() {
        if (properties.selfServiceRequests() || isAdmin()) {
            return;
        }
        throw new AccessDeniedException(
                "Self-service exemption requests are switched off "
                        + "(megarepo.firewall.exemption.self-service-requests=false). "
                        + "Ask an administrator to file the request.");
    }

    private static boolean isAdmin() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        Set<String> authorities = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
        return authorities.contains(ADMIN_AUTHORITY);
    }

    private Map<UUID, String> repositoryNames(List<FirewallExemption> page) {
        Set<UUID> ids = page.stream()
                .map(FirewallExemption::repositoryId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> names = new HashMap<>();
        for (RepositoryEntity repository : repositories.findAllById(ids)) {
            names.put(repository.getId(), repository.getName());
        }
        return names;
    }

    private FirewallExemptionXO toXO(FirewallExemption exemption, Instant now) {
        return toXO(exemption, now, repositoryNames(List.of(exemption)));
    }

    /**
     * @param names resolved repository names; a global exemption has none, which
     *     the UI renders as "all repositories" rather than as a blank cell
     */
    private static FirewallExemptionXO toXO(
            FirewallExemption exemption, Instant now, Map<UUID, String> names) {

        return new FirewallExemptionXO(
                exemption.id(),
                exemption.componentKey(),
                exemption.keyKind(),
                exemption.scope(),
                exemption.repositoryId(),
                exemption.repositoryId() == null ? null : names.get(exemption.repositoryId()),
                exemption.ruleType(),
                exemption.advisoryIds(),
                exemption.state(),
                exemption.expiresAt(),
                // Computed rather than left to the client: the sweep runs daily,
                // and a list showing a lapsed exemption as active would be
                // exactly wrong for up to a day.
                exemption.expiresAt() != null && !exemption.expiresAt().isAfter(now),
                exemption.expiryNotifiedAt(),
                exemption.justification(),
                exemption.requestedBy(),
                exemption.requestedAt(),
                exemption.approvedBy(),
                exemption.approvedAt(),
                exemption.decisionNote());
    }

    private static String currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null ? authentication.getName() : "anonymous";
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
