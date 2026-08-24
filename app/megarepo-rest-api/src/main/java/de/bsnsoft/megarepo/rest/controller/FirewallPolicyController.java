package de.bsnsoft.megarepo.rest.controller;

import de.bsnsoft.megarepo.core.exception.ConflictException;
import de.bsnsoft.megarepo.core.exception.NotFoundException;
import de.bsnsoft.megarepo.core.exception.ValidationException;
import de.bsnsoft.megarepo.core.firewall.FirewallMode;
import de.bsnsoft.megarepo.core.firewall.FirewallRuleType;
import de.bsnsoft.megarepo.database.entity.FirewallPolicyEntity;
import de.bsnsoft.megarepo.database.entity.FirewallPolicyRuleEntity;
import de.bsnsoft.megarepo.database.entity.FirewallRepositoryConfigEntity;
import de.bsnsoft.megarepo.database.repository.FirewallPolicyJpaRepository;
import de.bsnsoft.megarepo.database.repository.FirewallPolicyRuleJpaRepository;
import de.bsnsoft.megarepo.database.repository.FirewallRepositoryConfigJpaRepository;
import de.bsnsoft.megarepo.repository.firewall.FirewallEnforcementSettingsService;
import de.bsnsoft.megarepo.repository.firewall.quarantine.QuarantineService;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRuleRegistry;
import de.bsnsoft.megarepo.rest.dto.firewall.FirewallPolicyRuleXO;
import de.bsnsoft.megarepo.rest.dto.firewall.FirewallPolicyUpsertXO;
import de.bsnsoft.megarepo.rest.dto.firewall.FirewallPolicyXO;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Named policies and the rules inside them.
 *
 * <h2>What a policy is for</h2>
 *
 * A repository in {@link FirewallMode#QUARANTINE} has to resolve to <em>some</em>
 * set of rules, and this is where those sets are written. One of them is the
 * global default; a repository may point at a different one, and when it does
 * <b>the assigned policy replaces the default rather than stacking on top of
 * it</b> — with stacking, "assign a lenient policy" would still enforce every
 * rule of the strict default underneath, which is not a result anybody can
 * predict from the UI.
 *
 * <h2>A replace is a replace</h2>
 *
 * {@code PUT} takes the complete rule set. Partial rule updates would need a
 * per-rule identity the editor does not have while a rule is being added, and
 * "the rule I deleted came back" is a worse failure than re-sending five rows.
 *
 * <h2>Editing an armed policy is confirmed, like arming one</h2>
 *
 * {@code FirewallAdminController} demands a typed phrase before a repository
 * starts refusing downloads. The same reasoning applies one level up: an edit to
 * a policy that an enforcing repository is using can fail a build in the next
 * second, and it is reached from a form full of near-identical rows. The guard
 * is only raised when the change can actually deny something — the master switch
 * is on <em>and</em> a repository in QUARANTINE resolves to this policy. Editing
 * a policy nobody is enforcing needs nothing, because a guard on the harmless
 * case is a guard people learn to type through.
 *
 * <h2>Every edit wakes the quarantine queue</h2>
 *
 * {@link QuarantineService#invalidatePolicy} is called on every write that can
 * change a verdict. Without it, loosening a policy leaves components held for a
 * rule that no longer exists until the next sweep, and the operator who just
 * fixed the policy watches the build keep failing for another quarter of an
 * hour. It only schedules a re-evaluation — the sweep still decides — so
 * calling it once too often costs a little work and never a wrong release.
 *
 * <h2>Access</h2>
 *
 * Under {@code /api/v1/admin/firewall/**}, which {@code SecurityConfig}
 * restricts to {@code nx-admin}. Authorization lives in the filter chain, not in
 * {@code @PreAuthorize} — method security is not enabled, so the annotation
 * would be decoration. {@code FirewallPolicyControllerTest} asserts the mapping
 * stays under that prefix, because moving it would silently downgrade it.
 */
@RestController
@RequestMapping(FirewallPolicyController.BASE_PATH)
public class FirewallPolicyController {

    static final String BASE_PATH = "/api/v1/admin/firewall/policies";

    private static final Logger log = LoggerFactory.getLogger(FirewallPolicyController.class);

    private final FirewallPolicyJpaRepository policies;
    private final FirewallPolicyRuleJpaRepository rules;
    private final FirewallRepositoryConfigJpaRepository configs;
    private final FirewallEnforcementSettingsService enforcementSettings;
    private final FirewallRuleRegistry registry;
    private final QuarantineService quarantine;

    public FirewallPolicyController(
            FirewallPolicyJpaRepository policies,
            FirewallPolicyRuleJpaRepository rules,
            FirewallRepositoryConfigJpaRepository configs,
            FirewallEnforcementSettingsService enforcementSettings,
            FirewallRuleRegistry registry,
            QuarantineService quarantine) {
        this.policies = policies;
        this.rules = rules;
        this.configs = configs;
        this.enforcementSettings = enforcementSettings;
        this.registry = registry;
        this.quarantine = quarantine;
    }

    /** The phrase that confirms an edit to {@code policyName}. */
    public static String requiredConfirmation(String policyName) {
        return "CHANGE POLICY " + policyName;
    }

    // ── Reading ─────────────────────────────────────────────────────────

    /**
     * Every policy, default first and then by name.
     *
     * <p>Not paged. Policies are a handful of hand-written documents, not a log —
     * and the editor needs the whole list anyway to offer them for assignment.
     */
    @GetMapping
    public ResponseEntity<List<FirewallPolicyXO>> list() {
        Usage usage = usage();
        List<FirewallPolicyXO> items = policies.findAll().stream()
                .map(policy -> toXO(policy, rules.findByPolicyId(policy.getId()), usage))
                .sorted(Comparator.comparing(FirewallPolicyXO::isDefault)
                        .reversed()
                        .thenComparing(FirewallPolicyXO::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
        return ResponseEntity.ok(items);
    }

    @GetMapping("/{id}")
    public ResponseEntity<FirewallPolicyXO> get(@PathVariable UUID id) {
        FirewallPolicyEntity policy = require(id);
        return ResponseEntity.ok(toXO(policy, rules.findByPolicyId(id), usage()));
    }

    // ── Writing ─────────────────────────────────────────────────────────

    /**
     * Creates a policy with its rules.
     *
     * <p>A fresh policy governs nothing, so no confirmation is asked for — unless
     * {@code makeDefault} is set, which hands it every repository that has not
     * chosen one.
     */
    @PostMapping
    @Transactional
    public ResponseEntity<FirewallPolicyXO> create(@Valid @RequestBody FirewallPolicyUpsertXO body) {
        List<FirewallPolicyRuleXO> requested = validateRules(body.rules());
        policies.findByName(body.name().trim()).ifPresent(existing -> {
            throw new ConflictException("A policy named '" + existing.getName() + "' already exists.");
        });

        Optional<FirewallPolicyEntity> previousDefault = policies.findByIsDefaultTrue();
        if (body.makeDefault()) {
            requireConfirmationIfEnforced(body.confirmation(), body.name().trim(), null, true);
        }

        FirewallPolicyEntity policy = new FirewallPolicyEntity();
        policy.setName(body.name().trim());
        policy.setDescription(trimToNull(body.description()));
        policy.setCreatedAt(Instant.now());
        policy.setCreatedBy(currentUser());
        policy.setDefault(false);
        FirewallPolicyEntity saved = policies.save(policy);

        List<FirewallPolicyRuleEntity> stored = replaceRules(saved.getId(), requested);
        if (body.makeDefault()) {
            moveDefault(saved, previousDefault);
        }

        log.info("Firewall policy '{}' created by {} with {} rule(s){}",
                saved.getName(), currentUser(), stored.size(), body.makeDefault() ? ", as the default" : "");

        // A new policy holds nothing, but the one it displaced as default does:
        // components it was holding may be acceptable under the new rules.
        if (body.makeDefault()) {
            previousDefault.ifPresent(previous -> quarantine.invalidatePolicy(previous.getId()));
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(toXO(saved, stored, usage()));
    }

    /**
     * Replaces a policy: its name, its description, its complete rule set, and
     * optionally the default flag.
     *
     * <p>{@code makeDefault: false} on the policy that currently holds the flag
     * is <em>not</em> read as "clear it". Something has to be the default —
     * a repository in QUARANTINE with no policy of its own has to resolve to
     * something — so the flag moves to another policy rather than being turned
     * off. See {@link #makeDefault(UUID, FirewallPolicyUpsertXO)}.
     */
    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<FirewallPolicyXO> replace(
            @PathVariable UUID id, @Valid @RequestBody FirewallPolicyUpsertXO body) {

        FirewallPolicyEntity policy = require(id);
        List<FirewallPolicyRuleXO> requested = validateRules(body.rules());

        String name = body.name().trim();
        policies.findByName(name).ifPresent(other -> {
            if (!other.getId().equals(id)) {
                throw new ConflictException("A policy named '" + name + "' already exists.");
            }
        });

        boolean becomingDefault = body.makeDefault() && !policy.isDefault();
        requireConfirmationIfEnforced(body.confirmation(), policy.getName(), id, becomingDefault);

        Optional<FirewallPolicyEntity> previousDefault =
                becomingDefault ? policies.findByIsDefaultTrue() : Optional.empty();

        policy.setName(name);
        policy.setDescription(trimToNull(body.description()));
        FirewallPolicyEntity saved = policies.save(policy);
        List<FirewallPolicyRuleEntity> stored = replaceRules(id, requested);
        if (becomingDefault) {
            moveDefault(saved, previousDefault);
        }

        log.info("Firewall policy '{}' replaced by {} with {} rule(s)",
                saved.getName(), currentUser(), stored.size());

        // Held components were judged by the rules that just changed; the sweep
        // re-runs the new ones and releases what has become acceptable.
        quarantine.invalidatePolicy(id);
        previousDefault.ifPresent(previous -> quarantine.invalidatePolicy(previous.getId()));

        return ResponseEntity.ok(toXO(saved, stored, usage()));
    }

    /**
     * Moves the global default flag onto this policy, clearing it from whichever
     * policy held it — in one transaction, because the schema permits exactly one
     * and asking a client to run two calls in the right order is asking for the
     * window in between.
     *
     * @param confirmation a query parameter rather than a body field, here and on
     *     {@link #delete}: neither call has anything else to send, and a body on
     *     a {@code DELETE} is dropped by enough HTTP clients that a guard
     *     depending on one would be a guard that fails open for some callers and
     *     400s for others
     */
    @PostMapping("/{id}/default")
    @Transactional
    public ResponseEntity<FirewallPolicyXO> makeDefault(
            @PathVariable UUID id,
            @RequestParam(required = false) String confirmation) {

        FirewallPolicyEntity policy = require(id);
        if (policy.isDefault()) {
            return ResponseEntity.ok(toXO(policy, rules.findByPolicyId(id), usage()));
        }

        requireConfirmationIfEnforced(confirmation, policy.getName(), id, true);

        Optional<FirewallPolicyEntity> previousDefault = policies.findByIsDefaultTrue();
        moveDefault(policy, previousDefault);

        log.info("Firewall policy '{}' is now the default (set by {})", policy.getName(), currentUser());

        quarantine.invalidatePolicy(id);
        previousDefault.ifPresent(previous -> quarantine.invalidatePolicy(previous.getId()));

        return ResponseEntity.ok(toXO(policy, rules.findByPolicyId(id), usage()));
    }

    /**
     * Deletes a policy. Its rules go with it (ON DELETE CASCADE) and any
     * repository pointing at it falls back to the default (ON DELETE SET NULL).
     *
     * <p>The default policy itself cannot be deleted: a repository in QUARANTINE
     * with no policy assigned has to resolve to something, and an instance with
     * no default would make the enforcement switch a no-op that looks like it is
     * working. Make another policy the default first.
     */
    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<Void> delete(
            @PathVariable UUID id,
            @RequestParam(required = false) String confirmation) {

        FirewallPolicyEntity policy = require(id);
        if (policy.isDefault()) {
            throw new ConflictException(
                    "'" + policy.getName() + "' is the global default policy and cannot be deleted. "
                            + "A repository in QUARANTINE with no policy of its own resolves to it. "
                            + "Make another policy the default first, then delete this one.");
        }

        requireConfirmationIfEnforced(confirmation, policy.getName(), id, false);

        // Before the delete, not after: firewall_quarantine.policy_id is
        // ON DELETE SET NULL, so once the row is gone the entries this policy
        // decided can no longer be found by it and would sit in the queue on a
        // verdict nothing can reproduce.
        int rescheduled = quarantine.invalidatePolicy(id);

        rules.deleteByPolicyId(id);
        policies.delete(policy);

        log.info("Firewall policy '{}' deleted by {}; {} quarantined entries rescheduled",
                policy.getName(), currentUser(), rescheduled);

        return ResponseEntity.noContent().build();
    }

    // ── Validation ──────────────────────────────────────────────────────

    /**
     * @return the rules to store, never null
     * @throws ValidationException (400) for a rule the engine could not act on.
     *     {@code implemented} is ignored on input — it is the server's answer
     *     about its own build, and a client that sends it is describing
     *     something it cannot know
     */
    private static List<FirewallPolicyRuleXO> validateRules(List<FirewallPolicyRuleXO> requested) {
        if (requested == null) {
            return List.of();
        }
        for (FirewallPolicyRuleXO rule : requested) {
            if (rule == null) {
                throw new ValidationException("A policy rule must not be null.");
            }
            if (rule.ruleType() == FirewallRuleType.ADVISORY_MATCH) {
                throw new ValidationException(
                        "ADVISORY_MATCH is not a policy rule. It is the observation that some "
                                + "advisory names a component, recorded in the violation log; "
                                + "nothing evaluates it, so an action configured for it would "
                                + "never be applied. Use CVSS_THRESHOLD or KNOWN_MALICIOUS to act "
                                + "on advisories.");
            }
        }
        return requested;
    }

    /**
     * Demands the typed phrase when — and only when — this change can alter what
     * a repository is denying right now.
     *
     * @param policyId the policy being changed, or null when it does not exist
     *     yet
     * @param becomingDefault whether this change hands the policy every
     *     repository that has not chosen one
     * @throws ValidationException (400) naming the expected phrase
     */
    private void requireConfirmationIfEnforced(
            String provided, String policyName, UUID policyId, boolean becomingDefault) {

        if (!wouldChangeEnforcedBehaviour(policyId, becomingDefault)) {
            return;
        }
        String expected = requiredConfirmation(policyName);
        if (provided == null || !expected.equals(provided.trim())) {
            throw new ValidationException(
                    "'" + policyName + "' is in force on at least one repository that is currently "
                            + "blocking, so this change can fail a build in the next second. To "
                            + "confirm, send confirmation=\"" + expected + "\".");
        }
    }

    /**
     * Whether a repository is refusing downloads on the strength of this policy.
     *
     * <p>Both halves are needed: the global switch has to be on — with it off,
     * a QUARANTINE repository denies nothing at all — and a repository has to be
     * resolving to this policy, either because it names it or because it names
     * none and this policy is (or is becoming) the default.
     */
    private boolean wouldChangeEnforcedBehaviour(UUID policyId, boolean becomingDefault) {
        if (!enforcementSettings.enforcementEnabled()) {
            return false;
        }
        List<FirewallRepositoryConfigEntity> quarantining = configs.findByMode(FirewallMode.QUARANTINE);
        if (quarantining.isEmpty()) {
            return false;
        }
        boolean isDefault = becomingDefault
                || (policyId != null
                        && policies.findByIsDefaultTrue()
                                .map(current -> current.getId().equals(policyId))
                                .orElse(false));
        for (FirewallRepositoryConfigEntity config : quarantining) {
            if (config.getPolicyId() == null) {
                if (isDefault) {
                    return true;
                }
            } else if (config.getPolicyId().equals(policyId)) {
                return true;
            }
        }
        return false;
    }

    // ── Persistence helpers ─────────────────────────────────────────────

    /**
     * Writes the complete rule set, replacing whatever was there.
     *
     * <p>Delete-then-insert rather than a merge: rule rows carry no identity the
     * editor can preserve across an add, and reconciling by position would
     * reassign a config to the wrong rule type the moment somebody reorders the
     * form.
     */
    private List<FirewallPolicyRuleEntity> replaceRules(UUID policyId, List<FirewallPolicyRuleXO> requested) {
        rules.deleteByPolicyId(policyId);
        List<FirewallPolicyRuleEntity> stored = new ArrayList<>();
        for (FirewallPolicyRuleXO rule : requested) {
            FirewallPolicyRuleEntity entity = new FirewallPolicyRuleEntity();
            entity.setPolicyId(policyId);
            entity.setRuleType(rule.ruleType());
            entity.setAction(rule.action());
            entity.setConfig(rule.config() == null ? new HashMap<>() : new HashMap<>(rule.config()));
            entity.setEnabled(rule.enabled());
            stored.add(rules.save(entity));
        }
        return stored;
    }

    /** Sets the flag here and clears it there, inside the caller's transaction. */
    private void moveDefault(FirewallPolicyEntity policy, Optional<FirewallPolicyEntity> previousDefault) {
        previousDefault.ifPresent(previous -> {
            if (!previous.getId().equals(policy.getId())) {
                previous.setDefault(false);
                // Flushed before the flag is set here: idx_firewall_policy_single_default
                // is a partial unique index, and two rows claiming it inside one
                // statement order would be rejected by the database.
                policies.saveAndFlush(previous);
            }
        });
        policy.setDefault(true);
        policies.save(policy);
    }

    private FirewallPolicyEntity require(UUID id) {
        return policies.findById(id)
                .orElseThrow(() -> new NotFoundException("Firewall policy not found: " + id));
    }

    // ── Mapping ─────────────────────────────────────────────────────────

    /**
     * How many repositories each policy governs, read once for the whole
     * response.
     *
     * <p>A repository with no policy of its own resolves to the default, so the
     * default's counts include those — the number an operator needs before
     * editing it is "how many repositories will this affect", not "how many
     * happened to name it".
     */
    private Usage usage() {
        Map<UUID, int[]> counts = new HashMap<>();
        int unassigned = 0;
        int unassignedEnforcing = 0;
        for (FirewallRepositoryConfigEntity config : configs.findAll()) {
            boolean enforcing = config.getMode() == FirewallMode.QUARANTINE;
            if (config.getPolicyId() == null) {
                unassigned++;
                if (enforcing) {
                    unassignedEnforcing++;
                }
                continue;
            }
            int[] entry = counts.computeIfAbsent(config.getPolicyId(), key -> new int[2]);
            entry[0]++;
            if (enforcing) {
                entry[1]++;
            }
        }
        return new Usage(counts, unassigned, unassignedEnforcing);
    }

    private record Usage(Map<UUID, int[]> byPolicy, int unassigned, int unassignedEnforcing) {

        int assigned(FirewallPolicyEntity policy) {
            int direct = byPolicy.getOrDefault(policy.getId(), EMPTY)[0];
            return policy.isDefault() ? direct + unassigned : direct;
        }

        int enforcing(FirewallPolicyEntity policy) {
            int direct = byPolicy.getOrDefault(policy.getId(), EMPTY)[1];
            return policy.isDefault() ? direct + unassignedEnforcing : direct;
        }

        private static final int[] EMPTY = new int[2];
    }

    private FirewallPolicyXO toXO(
            FirewallPolicyEntity policy, List<FirewallPolicyRuleEntity> policyRules, Usage usage) {

        List<FirewallPolicyRuleXO> ruleXOs = policyRules.stream()
                .map(rule -> new FirewallPolicyRuleXO(
                        rule.getId(),
                        rule.getRuleType(),
                        rule.getAction(),
                        rule.getConfig() == null ? Map.of() : Map.copyOf(rule.getConfig()),
                        rule.isEnabled(),
                        // Asked of the registry, never stored: whether a rule is
                        // enforced is a property of the running build, and a
                        // persisted flag would go stale on the next release.
                        registry.isImplemented(rule.getRuleType())))
                .sorted(Comparator.comparing(rule -> rule.ruleType().name()))
                .toList();

        return new FirewallPolicyXO(
                policy.getId(),
                policy.getName(),
                policy.getDescription(),
                policy.isDefault(),
                ruleXOs,
                usage.assigned(policy),
                usage.enforcing(policy),
                policy.getCreatedAt(),
                policy.getCreatedBy());
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String currentUser() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : "anonymous";
    }
}
