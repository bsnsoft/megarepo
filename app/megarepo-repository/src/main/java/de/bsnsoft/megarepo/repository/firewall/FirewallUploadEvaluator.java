package de.bsnsoft.megarepo.repository.firewall;

import de.bsnsoft.megarepo.core.firewall.FirewallAction;
import de.bsnsoft.megarepo.core.firewall.FirewallMode;
import de.bsnsoft.megarepo.core.firewall.FirewallQuarantineReason;
import de.bsnsoft.megarepo.core.firewall.FirewallRuleType;
import de.bsnsoft.megarepo.core.repository.RepositoryType;
import de.bsnsoft.megarepo.database.entity.AssetEntity;
import de.bsnsoft.megarepo.database.entity.ComponentEntity;
import de.bsnsoft.megarepo.database.entity.FirewallPolicyEntity;
import de.bsnsoft.megarepo.database.entity.FirewallPolicyRuleEntity;
import de.bsnsoft.megarepo.database.repository.AssetJpaRepository;
import de.bsnsoft.megarepo.database.repository.FirewallPolicyJpaRepository;
import de.bsnsoft.megarepo.database.repository.FirewallPolicyRuleJpaRepository;
import de.bsnsoft.megarepo.repository.advisory.AdvisoryFinding;
import de.bsnsoft.megarepo.repository.advisory.AdvisoryLookupService;
import de.bsnsoft.megarepo.repository.firewall.facts.ComponentFacts;
import de.bsnsoft.megarepo.repository.firewall.facts.ComponentFactsService;
import de.bsnsoft.megarepo.repository.firewall.identity.ComponentIdentity;
import de.bsnsoft.megarepo.repository.firewall.identity.PurlBuilder;
import de.bsnsoft.megarepo.repository.firewall.quarantine.QuarantineService;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRuleContext;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRuleOutcome;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRuleRegistry;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRuleSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Judges an upload into a hosted repository, the way the enforcement path judges
 * a download.
 *
 * <h2>Why uploads are evaluated at all</h2>
 *
 * Phase 1 never looked at one, and the gap is not academic: the component a
 * developer publishes into a hosted repository is the component every consumer
 * of that repository then pulls, and a repository whose downloads are gated
 * while its uploads are not is a repository with an unlocked back door. The
 * customer asked for hosted uploads to be evaluated, and this is that
 * evaluation.
 *
 * <h2>What this class is not</h2>
 *
 * It is <b>not wired into {@code RepositoryRouter}</b>, on purpose. This package
 * delivers the verdict; wiring the PUT path — deciding where in the upload
 * sequence it runs and what a refusal does to already-written bytes — belongs to
 * the enforcement-wiring package, which owns the router and the block response.
 * Everything here is a bean with a method and no side effect on the request
 * besides the verdict it returns and the queue entry a hold produces.
 *
 * <h2>When it declines to decide</h2>
 *
 * The same two switches as a download: the global enforcement switch, which
 * ships off, and the repository's mode, which has to be {@code QUARANTINE}.
 * Anything else returns {@link FirewallEvaluation.Outcome#NOT_ENFORCING} with no
 * query issued, so an installation that upgrades into this code cannot start
 * refusing a publish it accepted yesterday. A proxy or group repository is never
 * evaluated here either — nothing is published into one.
 *
 * <h2>Failure serves the publish</h2>
 *
 * Every path catches {@link RuntimeException} and answers "allow". A rule that
 * cannot be evaluated is {@code INDETERMINATE} and the repository's fail mode
 * decides; a defect in the firewall is not a verdict and must not cost a
 * developer their release.
 */
@Service
public class FirewallUploadEvaluator {

    private static final Logger log = LoggerFactory.getLogger(FirewallUploadEvaluator.class);

    /**
     * The rules applied when no policy row exists at all.
     *
     * <p>The same two the download path falls back to, and for the same reason:
     * V16 seeds a default policy, so reaching this means somebody deleted it, and
     * an armed repository that silently allows every publish is the one outcome
     * the operator who armed it would not expect. Keeping the two paths in step
     * matters more than the three lines it costs — an upload allowed by a
     * fallback the download path would have refused is a hole with a plausible
     * explanation.
     */
    private static final List<FirewallRuleSettings> BUILT_IN_RULES = List.of(
            FirewallRuleSettings.of(FirewallRuleType.CVSS_THRESHOLD, FirewallAction.BLOCK,
                    java.util.Map.of("minScore", 9.0)),
            FirewallRuleSettings.of(FirewallRuleType.KNOWN_MALICIOUS, FirewallAction.BLOCK));

    private final FirewallEvaluationService evaluationService;
    private final FirewallEnforcementSettingsService enforcementSettings;
    private final FirewallPolicyJpaRepository policies;
    private final FirewallPolicyRuleJpaRepository policyRules;
    private final FirewallRuleRegistry registry;
    private final AdvisoryLookupService advisories;
    private final AssetJpaRepository assets;
    private final PurlBuilder purlBuilder;
    private final QuarantineService quarantine;
    private final ObjectProvider<ComponentFactsService> facts;

    public FirewallUploadEvaluator(
            FirewallEvaluationService evaluationService,
            FirewallEnforcementSettingsService enforcementSettings,
            FirewallPolicyJpaRepository policies,
            FirewallPolicyRuleJpaRepository policyRules,
            FirewallRuleRegistry registry,
            AdvisoryLookupService advisories,
            AssetJpaRepository assets,
            PurlBuilder purlBuilder,
            QuarantineService quarantine,
            ObjectProvider<ComponentFactsService> facts) {
        this.evaluationService = evaluationService;
        this.enforcementSettings = enforcementSettings;
        this.policies = policies;
        this.policyRules = policyRules;
        this.registry = registry;
        this.advisories = advisories;
        this.assets = assets;
        this.purlBuilder = purlBuilder;
        this.quarantine = quarantine;
        this.facts = facts;
    }

    /**
     * What is being published, as much of it as the caller can state.
     *
     * @param repositoryId the hosted repository being published into
     * @param repositoryName its name, for the audit trail and the 403 body
     * @param repositoryType its type; only {@link RepositoryType#HOSTED} is
     *     evaluated
     * @param path the artifact path being written
     * @param identity what the component is. The caller builds it — the format
     *     handler is the only thing that knows how to read coordinates out of an
     *     upload — or uses {@link #evaluate(UUID, String, RepositoryType, String,
     *     ComponentEntity, String, FirewallRequestContext)}, which derives it
     */
    public record UploadCandidate(
            UUID repositoryId,
            String repositoryName,
            RepositoryType repositoryType,
            String path,
            ComponentIdentity identity) {}

    /**
     * Decides whether one upload may be published.
     *
     * <p>Never throws.
     *
     * @param candidate what is being published
     * @param context who is publishing it
     * @return the evaluation. {@link FirewallEvaluation#blocked()} is the answer,
     *     and {@link FirewallEvaluation#decision()} carries the policy name and
     *     the matched rules the block response needs
     */
    public FirewallEvaluation evaluate(UploadCandidate candidate, FirewallRequestContext context) {
        FirewallRepositorySettings settings = FirewallRepositorySettings.fallback(FirewallMode.OFF);
        if (candidate == null) {
            return notEnforcing(null, null, null, settings);
        }
        try {
            if (candidate.repositoryId() == null
                    || candidate.repositoryType() != RepositoryType.HOSTED
                    || !enforcementSettings.enforcementEnabled()) {
                return notEnforcing(candidate.repositoryId(), candidate.repositoryName(),
                        candidate.path(), settings);
            }

            settings = evaluationService.resolveSettings(candidate.repositoryId());
            if (!settings.enforces()) {
                return notEnforcing(candidate.repositoryId(), candidate.repositoryName(),
                        candidate.path(), settings);
            }
            return decide(candidate, settings, context);

        } catch (RuntimeException e) {
            log.warn("Repository firewall upload evaluation failed for {}/{} — the upload was allowed",
                    candidate.repositoryName(), candidate.path(), e);
            return notEnforcing(candidate.repositoryId(), candidate.repositoryName(),
                    candidate.path(), settings)
                    .withOutcome(FirewallEvaluation.Outcome.FAILED);
        }
    }

    /**
     * The same, deriving the identity from a component row.
     *
     * <p>For a caller that has already built the {@link ComponentEntity} — the
     * format handlers do, as the last step before the asset is written.
     */
    public FirewallEvaluation evaluate(
            UUID repositoryId,
            String repositoryName,
            RepositoryType repositoryType,
            String path,
            ComponentEntity component,
            String sha256,
            FirewallRequestContext context) {

        ComponentIdentity identity;
        try {
            identity = component == null
                    ? new ComponentIdentity.Unidentified(null, null, null, null)
                    : purlBuilder.identify(component, sha256);
        } catch (RuntimeException e) {
            log.debug("Could not identify the uploaded component at {}/{}", repositoryName, path, e);
            identity = new ComponentIdentity.Unidentified(null, null, null, null);
        }
        return evaluate(
                new UploadCandidate(repositoryId, repositoryName, repositoryType, path, identity),
                context);
    }

    // ------------------------------------------------------------------

    private FirewallEvaluation decide(
            UploadCandidate candidate,
            FirewallRepositorySettings settings,
            FirewallRequestContext context) {

        if (isPreExisting(candidate)) {
            // Re-publishing a path whose asset predates the moment enforcement
            // was switched on. The customer's grandfathering rule does not stop
            // at downloads: an operator flipping the switch must not turn a
            // working release job into a failing one.
            FirewallEvaluation evaluation = new FirewallEvaluation(
                    candidate.repositoryId(), candidate.repositoryName(), candidate.path(),
                    settings, candidate.identity(), List.of(),
                    FirewallEvaluation.Outcome.CLEAN, true,
                    FirewallDecision.preExisting(null, null, List.of()));
            return evaluation;
        }

        FirewallPolicyEntity policy = resolvePolicy(settings);
        UUID policyId = policy == null ? null : policy.getId();
        String policyName = policy == null ? "built-in default" : policy.getName();
        List<FirewallRuleSettings> rules = policyId == null
                ? BUILT_IN_RULES
                : toSettings(policyRules.findByPolicyIdAndEnabledTrue(policyId));

        ComponentIdentity identity = candidate.identity();
        List<AdvisoryFinding> findings =
                identity.isResolvable() ? advisories.findAdvisories(identity) : List.of();

        FirewallRuleContext ruleContext = new FirewallRuleContext(
                candidate.repositoryId(),
                candidate.repositoryName(),
                candidate.repositoryType(),
                candidate.path(),
                identity,
                findings,
                lookupFacts(identity),
                settings,
                true,
                false,
                Instant.now());

        List<FirewallRuleViolation> violations = new ArrayList<>();
        FirewallQuarantineReason holdReason = null;
        String indeterminate = null;

        for (FirewallRuleSettings ruleSettings : rules) {
            FirewallRuleOutcome outcome = registry.evaluate(ruleContext, ruleSettings);

            if (outcome.indeterminate() && indeterminate == null) {
                indeterminate = outcome.reason();
            }
            if (!outcome.matched()) {
                continue;
            }
            violations.add(outcome.violation());
            if (ruleSettings.blocks() && holdReason == null) {
                holdReason = registry.find(ruleSettings.ruleType())
                        .filter(implementation -> implementation.quarantineOnMatch())
                        .map(implementation -> implementation.quarantineReason())
                        .orElse(null);
            }
        }

        boolean blocks = violations.stream().anyMatch(FirewallRuleViolation::blocks);
        if (blocks) {
            FirewallEvaluation refused = new FirewallEvaluation(
                    candidate.repositoryId(), candidate.repositoryName(), candidate.path(),
                    settings, identity, findings, FirewallEvaluation.Outcome.MATCHED, false,
                    FirewallDecision.blocked(policyId, policyName, violations));
            if (holdReason != null) {
                hold(refused, holdReason, context);
            }
            return refused;
        }

        if (indeterminate != null && settings.failsClosed()) {
            FirewallEvaluation refused = new FirewallEvaluation(
                    candidate.repositoryId(), candidate.repositoryName(), candidate.path(),
                    settings, identity, findings, FirewallEvaluation.Outcome.UNAVAILABLE, false,
                    FirewallDecision.unavailable(true));
            hold(refused, FirewallQuarantineReason.EVALUATION_INCOMPLETE, context);
            return refused;
        }

        return new FirewallEvaluation(
                candidate.repositoryId(), candidate.repositoryName(), candidate.path(),
                settings, identity, findings,
                findings.isEmpty() ? FirewallEvaluation.Outcome.CLEAN : FirewallEvaluation.Outcome.MATCHED,
                false,
                FirewallDecision.allowed(policyId, policyName, violations));
    }

    /**
     * Records the hold, so a refused publish shows up in the queue with a reason
     * rather than only as a 403 in somebody's CI log.
     *
     * <p>Quarantining an upload holds the <em>component</em>, not stored bytes —
     * nothing was published. When the entry is released the publisher retries and
     * succeeds, which is the same shape as a held download becoming servable.
     * {@link QuarantineService#quarantine} decides for itself whether an entry is
     * written at all: it is off when quarantine is disabled, and it never holds a
     * pre-existing component.
     */
    private void hold(
            FirewallEvaluation evaluation,
            FirewallQuarantineReason reason,
            FirewallRequestContext context) {
        try {
            quarantine.quarantine(evaluation, reason, context);
        } catch (RuntimeException e) {
            log.warn("Could not record the quarantine entry for the refused upload {}/{}",
                    evaluation.repositoryName(), evaluation.path(), e);
        }
    }

    private ComponentFacts lookupFacts(ComponentIdentity identity) {
        ComponentFactsService service = facts.getIfAvailable();
        if (service == null) {
            return ComponentFacts.unknown(identity.key());
        }
        try {
            ComponentFacts looked = service.lookup(identity);
            return looked == null ? ComponentFacts.unknown(identity.key()) : looked;
        } catch (RuntimeException e) {
            log.debug("Component facts lookup failed for {}", identity.key(), e);
            return ComponentFacts.unknown(identity.key());
        }
    }

    /**
     * Whether this path already holds an artifact stored before enforcement was
     * switched on.
     *
     * <p>One indexed read, and only on a repository that is actually being
     * enforced. The alternative — declaring every upload new by definition —
     * would make a re-publish of a long-standing artifact fail the first time an
     * operator arms the firewall, which is precisely the class of breakage the
     * watermark exists to prevent.
     */
    private boolean isPreExisting(UploadCandidate candidate) {
        Instant watermark = enforcementSettings.enforcingSince();
        if (watermark == null || candidate.path() == null) {
            return false;
        }
        Optional<AssetEntity> asset =
                assets.findByRepositoryIdAndPath(candidate.repositoryId(), candidate.path());
        return asset.map(AssetEntity::getCreatedAt)
                .map(createdAt -> createdAt.isBefore(watermark))
                .orElse(false);
    }

    private static List<FirewallRuleSettings> toSettings(List<FirewallPolicyRuleEntity> rules) {
        List<FirewallRuleSettings> settings = new ArrayList<>(rules.size());
        for (FirewallPolicyRuleEntity rule : rules) {
            settings.add(new FirewallRuleSettings(
                    rule.getId(), rule.getRuleType(), rule.getAction(), rule.getConfig(),
                    rule.isEnabled()));
        }
        return settings;
    }

    private FirewallPolicyEntity resolvePolicy(FirewallRepositorySettings settings) {
        if (settings != null && settings.policyId() != null) {
            Optional<FirewallPolicyEntity> assigned = policies.findById(settings.policyId());
            if (assigned.isPresent()) {
                return assigned.get();
            }
            log.warn("Repository firewall policy {} is assigned but does not exist; "
                    + "falling back to the default policy", settings.policyId());
        }
        return policies.findByIsDefaultTrue().orElse(null);
    }

    private static FirewallEvaluation notEnforcing(
            UUID repositoryId,
            String repositoryName,
            String path,
            FirewallRepositorySettings settings) {
        return new FirewallEvaluation(
                repositoryId, repositoryName, path, settings, null, List.of(),
                FirewallEvaluation.Outcome.NOT_ENFORCING, false, FirewallDecision.notEvaluated());
    }
}
