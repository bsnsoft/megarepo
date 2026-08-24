package de.bsnsoft.megarepo.repository.firewall.quarantine;

import com.github.packageurl.MalformedPackageURLException;
import com.github.packageurl.PackageURL;
import de.bsnsoft.megarepo.core.firewall.FirewallQuarantineReason;
import de.bsnsoft.megarepo.core.firewall.FirewallQuarantineResolution;
import de.bsnsoft.megarepo.core.firewall.FirewallRuleType;
import de.bsnsoft.megarepo.core.repository.RepositoryType;
import de.bsnsoft.megarepo.database.entity.FirewallPolicyEntity;
import de.bsnsoft.megarepo.database.entity.FirewallPolicyRuleEntity;
import de.bsnsoft.megarepo.database.entity.FirewallQuarantineEntity;
import de.bsnsoft.megarepo.database.entity.RepositoryEntity;
import de.bsnsoft.megarepo.database.repository.FirewallPolicyJpaRepository;
import de.bsnsoft.megarepo.database.repository.FirewallPolicyRuleJpaRepository;
import de.bsnsoft.megarepo.database.repository.RepositoryJpaRepository;
import de.bsnsoft.megarepo.repository.advisory.AdvisoryFinding;
import de.bsnsoft.megarepo.repository.advisory.AdvisoryLookupService;
import de.bsnsoft.megarepo.repository.firewall.FirewallEvaluationService;
import de.bsnsoft.megarepo.repository.firewall.FirewallRepositorySettings;
import de.bsnsoft.megarepo.repository.firewall.FirewallRuleViolation;
import de.bsnsoft.megarepo.repository.firewall.exemption.ExemptionService;
import de.bsnsoft.megarepo.repository.firewall.exemption.FirewallExemption;
import de.bsnsoft.megarepo.repository.firewall.facts.ComponentFacts;
import de.bsnsoft.megarepo.repository.firewall.facts.ComponentFactsService;
import de.bsnsoft.megarepo.repository.firewall.identity.ComponentIdentity;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRuleContext;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRuleOutcome;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRuleRegistry;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRuleSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Decides what should happen to one held component, right now.
 *
 * <h2>Why re-evaluation is a class of its own</h2>
 *
 * The three quarantine reasons exist because their verdicts change without
 * anybody doing anything: a component gets older, advisory data arrives, an
 * evaluation that could not finish finishes. Something therefore has to ask the
 * question again, and "again" means the <em>current</em> policy against the
 * <em>current</em> data — not a replay of the decision that created the entry.
 *
 * <p>This class only answers; {@link DefaultQuarantineService} writes. Splitting
 * them keeps every state transition in one file (so an illegal one cannot be
 * written by accident from two places) and makes the interesting half — what the
 * rules now say — testable without a database.
 *
 * <h2>What it reads</h2>
 *
 * The same local tables the request path reads, through the same rule SPI:
 * the repository's firewall config, its policy and rules, the advisory mirror,
 * and the component-facts cache. No network. It runs on a background thread
 * rather than a request thread, but reaching out to a registry from here would
 * still put a third party between an operator and their queue.
 *
 * <h2>Dependencies that may not exist yet</h2>
 *
 * {@link ComponentFactsService} and {@link ExemptionService} are contract
 * interfaces implemented by sibling work packages. Both are injected as
 * {@link ObjectProvider} so that this package works on a build where they have
 * not landed: no facts means every fact is {@code UNKNOWN}, which is what the
 * rules already have to cope with, and no exemption service means no exemption
 * releases anything — the conservative direction in both cases.
 */
@Component
public class QuarantineReevaluator {

    private static final Logger log = LoggerFactory.getLogger(QuarantineReevaluator.class);

    /**
     * The config key {@code MIN_AGE} reads its threshold from, and the default it
     * falls back to.
     *
     * <p>Read here for <em>scheduling only</em> — to point {@code next_evaluation_at}
     * at the exact moment a component becomes old enough instead of polling it
     * every quarter of an hour. The rule itself remains the only thing that
     * decides whether the age is met; if this key ever stops matching the rule's,
     * the entry simply falls back to the ordinary backoff and is re-evaluated a
     * little later than it could have been.
     */
    private static final String MIN_AGE_KEY = "minAge";

    private static final Duration DEFAULT_MIN_AGE = Duration.ofDays(7);

    /** Reasons whose release has a more specific name than "nothing matched any more". */
    private static final Set<FirewallQuarantineReason> DATA_DRIVEN_REASONS =
            EnumSet.of(FirewallQuarantineReason.UNKNOWN_COMPONENT,
                    FirewallQuarantineReason.EVALUATION_INCOMPLETE);

    private final FirewallEvaluationService evaluationService;
    private final FirewallPolicyJpaRepository policies;
    private final FirewallPolicyRuleJpaRepository policyRules;
    private final FirewallRuleRegistry registry;
    private final AdvisoryLookupService advisories;
    private final RepositoryJpaRepository repositories;
    private final ObjectProvider<ComponentFactsService> facts;
    private final ObjectProvider<ExemptionService> exemptions;
    private final QuarantineProperties properties;

    public QuarantineReevaluator(
            FirewallEvaluationService evaluationService,
            FirewallPolicyJpaRepository policies,
            FirewallPolicyRuleJpaRepository policyRules,
            FirewallRuleRegistry registry,
            AdvisoryLookupService advisories,
            RepositoryJpaRepository repositories,
            ObjectProvider<ComponentFactsService> facts,
            ObjectProvider<ExemptionService> exemptions,
            QuarantineProperties properties) {
        this.evaluationService = evaluationService;
        this.policies = policies;
        this.policyRules = policyRules;
        this.registry = registry;
        this.advisories = advisories;
        this.repositories = repositories;
        this.facts = facts;
        this.exemptions = exemptions;
        this.properties = properties;
    }

    /**
     * Runs the current policy against current data for one held entry.
     *
     * <p>Never throws: an entry whose re-evaluation fails stays held and is
     * looked at again after the ordinary backoff. Releasing on a defect would
     * serve a component nobody cleared; blocking on one would refuse a component
     * nobody condemned.
     *
     * @param entity the held row
     * @param now the clock for this pass
     * @return what should happen to it
     */
    public Verdict reevaluate(FirewallQuarantineEntity entity, Instant now) {
        try {
            return decide(entity, now);
        } catch (RuntimeException e) {
            log.warn("Re-evaluation of quarantined {} in {} failed; the entry stays held",
                    entity.getComponentKey(), entity.getRepositoryName(), e);
            return Verdict.hold(backoff(entity, now), "the re-evaluation could not be completed");
        }
    }

    private Verdict decide(FirewallQuarantineEntity entity, Instant now) {
        ComponentIdentity identity = identityOf(entity.getComponentKey());

        Optional<FirewallExemption> exemption = liveExemption(entity, identity, now);
        if (exemption.isPresent()) {
            return Verdict.releasedByExemption(
                    exemption.get().id(),
                    "an approved exemption (%s) now covers this component"
                            .formatted(exemption.get().justification()));
        }

        FirewallRepositorySettings settings = evaluationService.resolveSettings(entity.getRepositoryId());
        FirewallPolicyEntity policy = resolvePolicy(settings);
        List<FirewallPolicyRuleEntity> rules = policy == null
                ? List.of()
                : policyRules.findByPolicyIdAndEnabledTrue(policy.getId());

        if (rules.isEmpty()) {
            // No policy, or a policy with nothing enabled in it. Nothing can be
            // holding this component any more — and the built-in fallback rules
            // the request path uses when a policy is missing (CVSS, malicious)
            // never quarantine, so there is nothing here to reproduce.
            return Verdict.release(FirewallQuarantineResolution.POLICY_CHANGED,
                    "no enabled policy rule applies to this repository any more");
        }

        FirewallRuleContext context = context(entity, identity, settings, now);

        List<FirewallRuleViolation> blocking = new ArrayList<>();
        List<FirewallRuleViolation> holding = new ArrayList<>();
        FirewallRuleSettings minAgeRule = null;
        String indeterminate = null;
        boolean reasonRuleEvaluated = false;
        boolean reasonRuleConfigured = false;

        for (FirewallPolicyRuleEntity rule : rules) {
            FirewallRuleSettings ruleSettings = toSettings(rule);
            if (ruleSettings.ruleType() == FirewallRuleType.MIN_AGE) {
                minAgeRule = ruleSettings;
            }
            if (namesReason(ruleSettings.ruleType(), entity.getReasonCode())) {
                reasonRuleConfigured = true;
                reasonRuleEvaluated = registry.isImplemented(ruleSettings.ruleType());
            }

            FirewallRuleOutcome outcome = registry.evaluate(context, ruleSettings);
            if (outcome.indeterminate() && indeterminate == null) {
                indeterminate = outcome.reason();
            }
            if (!outcome.matched() || !ruleSettings.blocks()) {
                // A WARN rule records and serves; there is nothing here to hold.
                continue;
            }
            FirewallRuleViolation violation = outcome.violation();
            if (holdsOnMatch(ruleSettings.ruleType())) {
                holding.add(violation);
            } else {
                blocking.add(violation);
            }
        }

        if (!blocking.isEmpty()) {
            // The answer changed for the worse: something that was merely
            // unproven is now a policy violation. BLOCKED rather than still
            // QUARANTINED, because there is nothing left to wait for.
            return Verdict.block(FirewallQuarantineResolution.POLICY_VIOLATION,
                    "re-evaluation found a blocking violation: " + blocking.get(0).reason());
        }
        if (!holding.isEmpty()) {
            return Verdict.hold(
                    nextEvaluation(entity, context, minAgeRule, holding, now),
                    holding.get(0).reason());
        }
        if (indeterminate != null) {
            if (settings.failsClosed()) {
                return Verdict.hold(backoff(entity, now), indeterminate);
            }
            // Fail-open: this repository serves a component it cannot judge, so
            // holding one is a state the operator has since configured away.
            return Verdict.release(FirewallQuarantineResolution.RE_EVALUATED_CLEAN,
                    "the evaluation is still incomplete (%s) and %s serves what it cannot judge"
                            .formatted(indeterminate, entity.getRepositoryName()));
        }

        return Verdict.release(releaseResolution(entity, reasonRuleConfigured, reasonRuleEvaluated),
                releaseNote(entity, context, reasonRuleConfigured, reasonRuleEvaluated));
    }

    // ------------------------------------------------------------------
    // Inputs
    // ------------------------------------------------------------------

    /**
     * Reconstructs the identity from the stored key.
     *
     * <p>{@code component_key} is {@code ComponentIdentity.key()} and the three
     * forms are distinguishable by prefix — that is the property the identity
     * type promises. Rebuilding it from the key rather than from the component
     * row is deliberate: a cleanup task may have deleted the cached artifact
     * (the column is {@code ON DELETE SET NULL} precisely so the queue survives
     * that), and an entry whose component row is gone still has to be
     * re-evaluated and released.
     */
    static ComponentIdentity identityOf(String componentKey) {
        String key = componentKey == null ? "" : componentKey.trim();
        if (key.startsWith("pkg:")) {
            try {
                return new ComponentIdentity.Purl(new PackageURL(key));
            } catch (MalformedPackageURLException e) {
                // A key that says it is a purl and is not stays unidentified. It
                // must not fall through to the hash branch, where "pkg" would
                // become a digest algorithm and the entry would be re-evaluated
                // against an identity nothing ever wrote.
                log.debug("Quarantined component key '{}' is not a parseable purl", key, e);
                return unidentified(key);
            }
        }
        int colon = key.indexOf(':');
        if (colon > 0 && colon < key.length() - 1 && !key.startsWith("unidentified:")) {
            return new ComponentIdentity.Hash(key.substring(0, colon), key.substring(colon + 1));
        }
        return unidentified(key);
    }

    private static ComponentIdentity unidentified(String key) {
        return new ComponentIdentity.Unidentified(null, null, key.isEmpty() ? null : key, null);
    }

    private FirewallRuleContext context(
            FirewallQuarantineEntity entity,
            ComponentIdentity identity,
            FirewallRepositorySettings settings,
            Instant now) {

        List<AdvisoryFinding> findings =
                identity.isResolvable() ? advisories.findAdvisories(identity) : List.of();

        ComponentFacts componentFacts = ComponentFacts.unknown(identity.key());
        ComponentFactsService factsService = facts.getIfAvailable();
        if (factsService != null) {
            try {
                ComponentFacts looked = factsService.lookup(identity);
                if (looked != null) {
                    componentFacts = looked;
                }
            } catch (RuntimeException e) {
                log.debug("Component facts lookup failed for {}", identity.key(), e);
            }
        }

        return new FirewallRuleContext(
                entity.getRepositoryId(),
                entity.getRepositoryName(),
                repositoryType(entity.getRepositoryId()),
                entity.getPath(),
                identity,
                findings,
                componentFacts,
                settings,
                false,
                // A quarantined component was, by construction, not pre-existing
                // when it was held: the grandfathering check runs before the
                // entry is ever created. Re-stating it here would let a
                // watermark moved forward release a decided entry as a side
                // effect.
                false,
                now);
    }

    private RepositoryType repositoryType(UUID repositoryId) {
        if (repositoryId == null) {
            return null;
        }
        return repositories.findById(repositoryId)
                .map(RepositoryEntity::getType)
                .map(QuarantineReevaluator::parseType)
                .orElse(null);
    }

    private static RepositoryType parseType(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return RepositoryType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private Optional<FirewallExemption> liveExemption(
            FirewallQuarantineEntity entity, ComponentIdentity identity, Instant now) {

        ExemptionService service = exemptions.getIfAvailable();
        if (service == null) {
            return Optional.empty();
        }
        try {
            List<FirewallExemption> applicable =
                    service.findApplicable(entity.getRepositoryId(), identity, now);
            return applicable == null || applicable.isEmpty()
                    ? Optional.empty()
                    : Optional.of(applicable.get(0));
        } catch (RuntimeException e) {
            log.debug("Exemption lookup failed for {}", identity.key(), e);
            return Optional.empty();
        }
    }

    /**
     * The policy that currently governs the repository.
     *
     * <p>Deliberately re-resolved rather than taken from the entry's stored
     * {@code policy_id}: the entry records which policy held the component, and
     * "the policy was unassigned" is one of the things re-evaluation exists to
     * notice.
     */
    private FirewallPolicyEntity resolvePolicy(FirewallRepositorySettings settings) {
        if (settings != null && settings.policyId() != null) {
            Optional<FirewallPolicyEntity> assigned = policies.findById(settings.policyId());
            if (assigned.isPresent()) {
                return assigned.get();
            }
        }
        return policies.findByIsDefaultTrue().orElse(null);
    }

    private static FirewallRuleSettings toSettings(FirewallPolicyRuleEntity rule) {
        return new FirewallRuleSettings(
                rule.getId(), rule.getRuleType(), rule.getAction(), rule.getConfig(), rule.isEnabled());
    }

    /** Whether a matched blocking rule of this type holds rather than refuses. */
    private boolean holdsOnMatch(FirewallRuleType ruleType) {
        return registry.find(ruleType).map(rule -> rule.quarantineOnMatch()).orElse(false);
    }

    /** Whether this rule type is the one that produced the entry's reason. */
    private static boolean namesReason(FirewallRuleType ruleType, FirewallQuarantineReason reason) {
        if (ruleType == null || reason == null) {
            return false;
        }
        return switch (reason) {
            case MIN_AGE_NOT_MET -> ruleType == FirewallRuleType.MIN_AGE;
            case UNKNOWN_COMPONENT -> ruleType == FirewallRuleType.UNKNOWN_COMPONENT;
            case EVALUATION_INCOMPLETE, POLICY_VIOLATION -> false;
        };
    }

    // ------------------------------------------------------------------
    // Naming the release
    // ------------------------------------------------------------------

    /**
     * Which {@link FirewallQuarantineResolution} a clean re-evaluation records.
     *
     * <p>The customer asked for automatic release "with a recorded reason", and a
     * reason that says {@code RE_EVALUATED_CLEAN} for every release answers
     * nothing. So the three cases are kept apart:
     *
     * <ul>
     *   <li>the rule that held it is still configured and still enforced, and it
     *       no longer matches — the component genuinely changed:
     *       {@code AGE_REACHED} or {@code ADVISORY_DATA_ARRIVED};</li>
     *   <li>the rule is gone from the policy or disabled — the <em>policy</em>
     *       changed, not the component: {@code POLICY_CHANGED};</li>
     *   <li>the rule is configured but no bean in this build implements it, so
     *       nothing was actually re-checked: {@code RE_EVALUATED_CLEAN}, and the
     *       note says so.</li>
     * </ul>
     */
    private static FirewallQuarantineResolution releaseResolution(
            FirewallQuarantineEntity entity, boolean configured, boolean evaluated) {

        FirewallQuarantineReason reason = entity.getReasonCode();
        if (reason == FirewallQuarantineReason.EVALUATION_INCOMPLETE) {
            return FirewallQuarantineResolution.ADVISORY_DATA_ARRIVED;
        }
        if (!configured) {
            return FirewallQuarantineResolution.POLICY_CHANGED;
        }
        if (!evaluated) {
            return FirewallQuarantineResolution.RE_EVALUATED_CLEAN;
        }
        if (reason == FirewallQuarantineReason.MIN_AGE_NOT_MET) {
            return FirewallQuarantineResolution.AGE_REACHED;
        }
        if (DATA_DRIVEN_REASONS.contains(reason)) {
            return FirewallQuarantineResolution.ADVISORY_DATA_ARRIVED;
        }
        return FirewallQuarantineResolution.RE_EVALUATED_CLEAN;
    }

    /** The human sentence beside the machine-readable resolution. */
    private static String releaseNote(
            FirewallQuarantineEntity entity,
            FirewallRuleContext context,
            boolean configured,
            boolean evaluated) {

        FirewallQuarantineReason reason = entity.getReasonCode();
        if (reason == FirewallQuarantineReason.MIN_AGE_NOT_MET && configured && evaluated) {
            Optional<Duration> age = context.facts().age(context.evaluatedAt());
            if (age.isPresent()) {
                return "published %s, %d day(s) old, the configured minimum age is reached"
                        .formatted(context.facts().publishedAt(), age.get().toDays());
            }
            return "the minimum age rule no longer objects to this component";
        }
        if (reason == FirewallQuarantineReason.UNKNOWN_COMPONENT && configured && evaluated) {
            return context.findings().isEmpty()
                    ? "the component is no longer treated as unknown by the current policy"
                    : "advisory data for this component has arrived (%d finding(s))"
                            .formatted(context.findings().size());
        }
        if (reason == FirewallQuarantineReason.EVALUATION_INCOMPLETE) {
            return "the evaluation completed and nothing blocking was found";
        }
        if (!configured) {
            return "the %s rule that held this component is no longer enabled in the policy"
                    .formatted(reason);
        }
        if (!evaluated) {
            return "the %s rule is configured but not enforced by this build".formatted(reason);
        }
        return "the current policy no longer objects to this component";
    }

    // ------------------------------------------------------------------
    // Scheduling
    // ------------------------------------------------------------------

    /**
     * When the sweep should look at this entry again.
     *
     * <p>A {@code MIN_AGE} hold is scheduled for the exact moment the component
     * becomes old enough. That is the whole reason {@code next_evaluation_at} is
     * a column: an entry a developer is waiting on should be released at the
     * minute the policy says, not at the next quarter of an hour after it, and a
     * queue of ten thousand such entries should not be re-polled every tick to
     * find out that none of them is ready.
     *
     * <p>Everything else backs off — twice the previous wait, floored at
     * {@code min-reevaluation-interval} and capped at
     * {@code max-reevaluation-interval}. Capped rather than unbounded because an
     * {@code UNKNOWN_COMPONENT} hold has no predictable release time, and an
     * exponential backoff with no ceiling eventually stops looking altogether.
     */
    private Instant nextEvaluation(
            FirewallQuarantineEntity entity,
            FirewallRuleContext context,
            FirewallRuleSettings minAgeRule,
            List<FirewallRuleViolation> holding,
            Instant now) {

        boolean heldByAge = holding.stream()
                .anyMatch(violation -> violation.ruleType() == FirewallRuleType.MIN_AGE);
        if (heldByAge && minAgeRule != null && context.facts().isSettled()
                && context.facts().publishedAt() != null) {

            Duration minAge = minAgeRule.duration(MIN_AGE_KEY, DEFAULT_MIN_AGE);
            Instant ripe = context.facts().publishedAt().plus(minAge);
            if (ripe.isAfter(now)) {
                return ripe;
            }
        }
        return backoff(entity, now);
    }

    /** Twice the previous wait, clamped into the configured window. */
    private Instant backoff(FirewallQuarantineEntity entity, Instant now) {
        Duration min = properties.minReevaluationInterval();
        Duration max = properties.maxReevaluationInterval();

        Duration previous = Duration.ZERO;
        if (entity.getLastEvaluatedAt() != null && entity.getNextEvaluationAt() != null) {
            previous = Duration.between(entity.getLastEvaluatedAt(), entity.getNextEvaluationAt());
        }
        Duration next = previous.isNegative() || previous.isZero() ? min : previous.multipliedBy(2);
        if (next.compareTo(min) < 0) {
            next = min;
        }
        if (next.compareTo(max) > 0) {
            next = max;
        }
        return now.plus(next);
    }

    // ------------------------------------------------------------------
    // Result
    // ------------------------------------------------------------------

    /** What should happen to a re-evaluated entry. */
    public enum Outcome {

        /** Still held; {@link Verdict#nextEvaluationAt()} says when to look again. */
        HOLD,

        /** It may be served. */
        RELEASE,

        /** It is refused for good — a re-evaluation found a genuine violation. */
        BLOCK
    }

    /**
     * One re-evaluation's answer.
     *
     * @param outcome what should happen
     * @param resolution the machine-readable reason, null for {@link Outcome#HOLD}
     * @param note the sentence an operator reads, never null
     * @param exemptionId the exemption that released it, when one did
     * @param nextEvaluationAt when to look again, set only for {@link Outcome#HOLD}
     */
    public record Verdict(
            Outcome outcome,
            FirewallQuarantineResolution resolution,
            String note,
            UUID exemptionId,
            Instant nextEvaluationAt) {

        static Verdict hold(Instant nextEvaluationAt, String note) {
            return new Verdict(Outcome.HOLD, null, note, null, nextEvaluationAt);
        }

        static Verdict release(FirewallQuarantineResolution resolution, String note) {
            return new Verdict(Outcome.RELEASE, resolution, note, null, null);
        }

        static Verdict releasedByExemption(UUID exemptionId, String note) {
            return new Verdict(Outcome.RELEASE, FirewallQuarantineResolution.EXEMPTION_GRANTED,
                    note, exemptionId, null);
        }

        static Verdict block(FirewallQuarantineResolution resolution, String note) {
            return new Verdict(Outcome.BLOCK, resolution, note, null, null);
        }

        /** Whether this verdict leaves the entry held. */
        public boolean holds() {
            return outcome == Outcome.HOLD;
        }
    }
}
