package de.bsnsoft.megarepo.repository.firewall;

import de.bsnsoft.megarepo.core.firewall.FirewallAction;
import de.bsnsoft.megarepo.core.firewall.FirewallMode;
import de.bsnsoft.megarepo.core.firewall.FirewallQuarantineReason;
import de.bsnsoft.megarepo.core.firewall.FirewallRuleType;
import de.bsnsoft.megarepo.database.entity.FirewallPolicyEntity;
import de.bsnsoft.megarepo.database.entity.FirewallPolicyRuleEntity;
import de.bsnsoft.megarepo.database.repository.FirewallPolicyJpaRepository;
import de.bsnsoft.megarepo.database.repository.FirewallPolicyRuleJpaRepository;
import de.bsnsoft.megarepo.repository.firewall.exemption.ExemptionService;
import de.bsnsoft.megarepo.repository.firewall.exemption.FirewallExemption;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRule;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRuleContext;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRuleOutcome;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRuleRegistry;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRuleSettings;
import de.bsnsoft.megarepo.repository.firewall.rule.impl.CvssThresholdRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The policy engine: turns one component into a verdict.
 *
 * <h2>What changed in Phase 2</h2>
 *
 * Phase 1 switched on {@link FirewallRuleType} and implemented two rules inline.
 * It now asks {@link FirewallRuleRegistry} instead, so the set of rules a build
 * enforces is the set of {@link FirewallRule} beans on its classpath and adding
 * one touches nothing here. The two Phase 1 rules moved to
 * {@link CvssThresholdRule} and
 * {@link de.bsnsoft.megarepo.repository.firewall.rule.impl.KnownMaliciousRule}
 * unchanged, including the {@code BLOCK ⇒ EXACT} confidence default, which is now
 * {@link FirewallRuleSettings#minConfidence()}.
 *
 * <h2>Decision assembly</h2>
 *
 * In this order, and the order is the design:
 *
 * <ol>
 *   <li>every configured rule is evaluated against the same
 *       {@link FirewallRuleContext} — read once, for all of them;</li>
 *   <li>a matched rule that asks to block is weighed against the exemptions: an
 *       approved one suppresses it, and <em>its id goes onto the violation</em>,
 *       because "a BLOCK rule matched and the download went out" is not a
 *       readable audit trail on its own;</li>
 *   <li>a matched blocking rule that does not ask for a hold refuses the download
 *       outright — no queue entry. A critical advisory does not become acceptable
 *       by waiting (design §5.1);</li>
 *   <li>otherwise a matched blocking rule with
 *       {@link FirewallRule#quarantineOnMatch()} holds the component under that
 *       rule's reason;</li>
 *   <li>an {@code INDETERMINATE} outcome hands the decision to the repository's
 *       fail mode: {@code FAIL_CLOSED} holds under
 *       {@link FirewallQuarantineReason#EVALUATION_INCOMPLETE}, {@code FAIL_OPEN}
 *       serves;</li>
 *   <li>and a pre-existing component is never denied by any of it.</li>
 * </ol>
 *
 * <p>Point 3 is deliberately checked before point 4. A component that trips both
 * {@code KNOWN_MALICIOUS} and {@code MIN_AGE} is refused, not queued: putting it
 * in a queue would offer an operator a release button for a package the policy
 * says is malicious, which is the one thing design §5.1 rules out.
 *
 * <h2>What it does not do</h2>
 *
 * It writes nothing. The quarantine entry behind a
 * {@link FirewallDecision.Reason#QUARANTINED} decision is created by
 * {@link FirewallEnforcementService}, which owns the request path and the
 * off-thread recording; this class stays a read-only function of its inputs so a
 * test can call it without a transaction and an operator can reason about it
 * without one either.
 */
@Service
public class FirewallPolicyEvaluator {

    private static final Logger log = LoggerFactory.getLogger(FirewallPolicyEvaluator.class);

    /**
     * Used when no policy exists at all — neither assigned to the repository nor
     * marked as the global default. V16 seeds one, so this is the "someone
     * deleted it" case: an enforcing repository with no policy would otherwise
     * silently allow everything, which is the one outcome an operator who
     * switched enforcement on would not expect.
     */
    private static final List<FirewallRuleSettings> BUILT_IN_RULES = List.of(
            FirewallRuleSettings.of(
                    FirewallRuleType.CVSS_THRESHOLD,
                    FirewallAction.BLOCK,
                    Map.of("minScore", CvssThresholdRule.DEFAULT_MIN_SCORE)),
            FirewallRuleSettings.of(FirewallRuleType.KNOWN_MALICIOUS, FirewallAction.BLOCK));

    /** The policy name reported when the built-in fallback applied. */
    public static final String BUILT_IN_POLICY_NAME = "built-in default";

    private final FirewallPolicyJpaRepository policies;
    private final FirewallPolicyRuleJpaRepository rules;
    private final FirewallRuleRegistry registry;
    private final ExemptionService exemptions;

    public FirewallPolicyEvaluator(
            FirewallPolicyJpaRepository policies,
            FirewallPolicyRuleJpaRepository rules,
            FirewallRuleRegistry registry,
            ExemptionService exemptions) {
        this.policies = policies;
        this.rules = rules;
        this.registry = registry;
        this.exemptions = exemptions;
    }

    /**
     * Evaluates the repository's policy against one component.
     *
     * <p>Reads two small local tables plus, when a blocking rule matched, the
     * exemption index. No network, and no work proportional to the size of the
     * artifact.
     *
     * @param context everything readable about the component, already fetched by
     *     the caller. Its {@code settings.policyId()} selects the policy, falling
     *     back to the global default
     * @return never null. A {@link FirewallDecision.Reason#QUARANTINED} decision
     *     carries {@link FirewallDecision.Hold#pending} — the entry itself has not
     *     been written yet
     */
    @Transactional(readOnly = true)
    public FirewallDecision evaluate(FirewallRuleContext context) {
        // A context with no repository settings only reaches here from a caller
        // that built one by hand. FAIL_OPEN is what "unstated" means everywhere
        // else in the firewall, and FirewallRepositorySettings.fallback gives
        // exactly that.
        FirewallRepositorySettings settings = context.settings() == null
                ? FirewallRepositorySettings.fallback(FirewallMode.QUARANTINE)
                : context.settings();
        FirewallPolicyEntity policy = resolvePolicy(settings);
        UUID policyId = policy == null ? null : policy.getId();
        String policyName = policy == null ? BUILT_IN_POLICY_NAME : policy.getName();

        List<FirewallRuleSettings> applicable = policyId == null
                ? BUILT_IN_RULES
                : toSettings(rules.findByPolicyIdAndEnabledTrue(policyId));

        List<FirewallRuleViolation> violations = new ArrayList<>();
        boolean refusedOutright = false;
        FirewallQuarantineReason holdReason = null;
        String undecidable = null;

        for (FirewallRuleSettings ruleSettings : applicable) {
            FirewallRuleOutcome outcome = registry.evaluate(context, ruleSettings);

            if (outcome.matched()) {
                FirewallRuleViolation violation = weighAgainstExemptions(context, outcome.violation());
                violations.add(violation);
                if (!violation.denies()) {
                    continue;
                }
                if (quarantinesOnMatch(ruleSettings.ruleType())) {
                    if (holdReason == null) {
                        holdReason = quarantineReason(ruleSettings.ruleType());
                    }
                } else {
                    refusedOutright = true;
                }
                continue;
            }

            if (outcome.indeterminate() && undecidable == null && settings.failsClosed()
                    && exemptionFor(context, ruleSettings.ruleType()).isEmpty()) {
                // Only under FAIL_CLOSED is the answer worth a query: fail-open
                // serves either way, and the exemption lookup is the one thing
                // here that costs an index read.
                undecidable = outcome.reason();
            }
        }

        boolean wouldDeny = refusedOutright || holdReason != null || undecidable != null;

        if (context.preExisting() && wouldDeny) {
            // The customer's hardest constraint. Checked here and in
            // QuarantineService, and in nothing else.
            return FirewallDecision.preExisting(policyId, policyName, violations);
        }
        if (refusedOutright) {
            return FirewallDecision.blocked(policyId, policyName, violations);
        }
        if (holdReason != null) {
            return FirewallDecision.quarantined(
                    policyId, policyName, violations, FirewallDecision.Hold.pending(holdReason));
        }
        if (undecidable != null) {
            log.debug("Firewall could not decide about {} in {} ({}); fail-closed — holding it",
                    context.componentKey(), context.repositoryName(), undecidable);
            return FirewallDecision.quarantined(
                    policyId,
                    policyName,
                    violations,
                    FirewallDecision.Hold.pending(FirewallQuarantineReason.EVALUATION_INCOMPLETE));
        }
        if (!violations.isEmpty() && violations.stream().anyMatch(FirewallRuleViolation::exempted)) {
            return FirewallDecision.exempted(policyId, policyName, violations);
        }
        return FirewallDecision.allowed(policyId, policyName, violations);
    }

    /**
     * Whether a policy that is enforced anywhere carries an enabled rule of this
     * type.
     *
     * <p>Used by the startup audit, not by the request path.
     */
    @Transactional(readOnly = true)
    public boolean anyPolicyEnables(FirewallRuleType ruleType) {
        try {
            return rules.findAll().stream()
                    .anyMatch(rule -> rule.isEnabled() && rule.getRuleType() == ruleType);
        } catch (RuntimeException e) {
            log.debug("Could not inspect the configured policy rules", e);
            return false;
        }
    }

    // ------------------------------------------------------------------

    /**
     * Asks the exemption store whether this matched rule is covered, and marks the
     * violation when it is.
     *
     * <p>Only for a rule that would actually deny: a {@code WARN} rule withholds
     * nothing, and stamping an exemption id on it would claim an exemption was
     * spent on a finding that cost nobody anything.
     *
     * <p>A store that cannot be read is treated as "no exemption", which denies a
     * download somebody had permission for — recoverable — rather than serving one
     * nobody approved. It is logged as a failed lookup rather than recorded as a
     * clean one: the violation row must never say "checked and free" when nothing
     * was checked.
     */
    private FirewallRuleViolation weighAgainstExemptions(
            FirewallRuleContext context, FirewallRuleViolation violation) {

        if (!violation.blocks()) {
            return violation;
        }
        return exemptionFor(context, violation.ruleType())
                .map(exemption -> {
                    log.info("Firewall exemption {} covers {} in {} for rule {} — served",
                            exemption.id(), context.componentKey(), context.repositoryName(),
                            violation.ruleType());
                    return violation.exemptedBy(exemption.id());
                })
                .orElse(violation);
    }

    private Optional<FirewallExemption> exemptionFor(
            FirewallRuleContext context, FirewallRuleType ruleType) {
        if (ruleType == null) {
            return Optional.empty();
        }
        try {
            return exemptions.findApplicable(
                    context.repositoryId(), context.identity(), ruleType, context.evaluatedAt());
        } catch (RuntimeException e) {
            log.warn("Could not read the firewall exemptions for {} in {} — treating the "
                            + "component as unexempted",
                    context.componentKey(), context.repositoryName(), e);
            return Optional.empty();
        }
    }

    private boolean quarantinesOnMatch(FirewallRuleType ruleType) {
        return registry.find(ruleType).map(FirewallRule::quarantineOnMatch).orElse(false);
    }

    private FirewallQuarantineReason quarantineReason(FirewallRuleType ruleType) {
        return registry.find(ruleType)
                .map(FirewallRule::quarantineReason)
                .orElse(FirewallQuarantineReason.EVALUATION_INCOMPLETE);
    }

    /**
     * The policy assigned to the repository, else the global default, else null.
     *
     * <p>An assigned policy that no longer exists falls back to the default
     * rather than to "no policy": a dangling {@code policy_id} is a data
     * problem, not an instruction to stop enforcing.
     */
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

    private static List<FirewallRuleSettings> toSettings(List<FirewallPolicyRuleEntity> rows) {
        List<FirewallRuleSettings> settings = new ArrayList<>(rows.size());
        for (FirewallPolicyRuleEntity row : rows) {
            settings.add(new FirewallRuleSettings(
                    row.getId(), row.getRuleType(), row.getAction(), row.getConfig(), row.isEnabled()));
        }
        return settings;
    }
}
