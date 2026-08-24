package de.bsnsoft.megarepo.repository.firewall;

import de.bsnsoft.megarepo.core.firewall.FirewallAction;
import de.bsnsoft.megarepo.core.firewall.FirewallRuleType;
import de.bsnsoft.megarepo.database.entity.FirewallPolicyEntity;
import de.bsnsoft.megarepo.database.entity.FirewallPolicyRuleEntity;
import de.bsnsoft.megarepo.database.repository.FirewallPolicyJpaRepository;
import de.bsnsoft.megarepo.database.repository.FirewallPolicyRuleJpaRepository;
import de.bsnsoft.megarepo.repository.advisory.AdvisoryFinding;
import de.bsnsoft.megarepo.repository.advisory.MatchConfidence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;

/**
 * The policy engine: turns advisory findings into a verdict.
 *
 * <h2>Two rules, on purpose</h2>
 *
 * <ul>
 *   <li>{@link FirewallRuleType#CVSS_THRESHOLD} — an advisory at or above a CVSS
 *       score. The rule the V8 NVD firewall already had, so an operator
 *       migrating from it finds the behaviour they know.</li>
 *   <li>{@link FirewallRuleType#KNOWN_MALICIOUS} — the component is named by a
 *       malicious-package advisory (OSV publishes those under {@code MAL-}).
 *       Signed off by the customer as a blocking signal in its own right: a
 *       package that exists to steal credentials has no CVSS score to compare
 *       against.</li>
 * </ul>
 *
 * <p>Every other constant in {@link FirewallRuleType} is <b>not implemented</b>.
 * A rule row of an unimplemented type is skipped and logged once per evaluation
 * at debug level — never treated as "matched" (which would deny downloads for a
 * rule nobody wrote) and never as an error (which would take the download with
 * it). LICENSE needs license metadata MegaRepo does not extract yet, MIN_AGE
 * needs a publication date per component version, UNKNOWN_COMPONENT is a policy
 * question rather than a data one, and the two heuristics need a popularity
 * corpus.
 *
 * <h2>Why a BLOCK rule defaults to EXACT matches only</h2>
 *
 * {@link MatchConfidence#HEURISTIC} findings come from NVD's CPE data, matched
 * on the product name alone — no ecosystem, no namespace. Two unrelated packages
 * that share a name produce the same match. Recording that is useful; denying a
 * build over it is not, and it is exactly the false-positive class the customer
 * reported about the V8 firewall. So a rule whose action is {@code BLOCK}
 * defaults to {@code minConfidence = EXACT} and a {@code WARN} rule to
 * {@code HEURISTIC}, i.e. warn about everything, block only on advisories that
 * named the package by purl. Both are overridable per rule with
 * {@code "minConfidence"}.
 *
 * <h2>Rule configuration</h2>
 *
 * Read from {@code firewall_policy_rule.config} (JSONB), so a new parameter is
 * never a migration:
 * <pre>
 *   CVSS_THRESHOLD   {"minScore": 9.0, "minConfidence": "EXACT"}
 *   KNOWN_MALICIOUS  {"idPrefixes": ["MAL-"], "minConfidence": "EXACT"}
 * </pre>
 * A malformed value falls back to the default and logs; a policy typo must not
 * be able to deny every download in a repository.
 */
@Service
public class FirewallPolicyEvaluator {

    private static final Logger log = LoggerFactory.getLogger(FirewallPolicyEvaluator.class);

    /** Threshold used when a CVSS_THRESHOLD rule does not configure one. */
    static final double DEFAULT_MIN_SCORE = 9.0;

    /** Advisory id prefixes that mean "this package is malicious". */
    static final List<String> DEFAULT_MALICIOUS_PREFIXES = List.of("MAL-");

    /**
     * Used when no policy exists at all — neither assigned to the repository nor
     * marked as the global default. V16 seeds one, so this is the "someone
     * deleted it" case: an enforcing repository with no policy would otherwise
     * silently allow everything, which is the one outcome an operator who
     * switched enforcement on would not expect.
     */
    private static final List<FirewallPolicyRuleEntity> BUILT_IN_RULES = builtInRules();

    private final FirewallPolicyJpaRepository policies;
    private final FirewallPolicyRuleJpaRepository rules;

    public FirewallPolicyEvaluator(
            FirewallPolicyJpaRepository policies, FirewallPolicyRuleJpaRepository rules) {
        this.policies = policies;
        this.rules = rules;
    }

    /**
     * Evaluates the repository's policy against the findings for one component.
     *
     * <p>Reads two small local tables and nothing else — no network, and no work
     * proportional to the size of the artifact.
     *
     * @param settings the repository's resolved firewall configuration; its
     *     {@code policyId} selects the policy, falling back to the global
     *     default
     * @param findings advisories naming the component, possibly empty
     * @param preExisting whether the component was already stored in the
     *     repository before enforcement was switched on. Blocking rules still
     *     match and are still recorded for such a component — they just do not
     *     deny it.
     * @return never null
     */
    @Transactional(readOnly = true)
    public FirewallDecision evaluate(
            FirewallRepositorySettings settings, List<AdvisoryFinding> findings, boolean preExisting) {

        FirewallPolicyEntity policy = resolvePolicy(settings);
        UUID policyId = policy == null ? null : policy.getId();
        String policyName = policy == null ? "built-in default" : policy.getName();

        List<FirewallPolicyRuleEntity> applicable =
                policyId == null ? BUILT_IN_RULES : rules.findByPolicyIdAndEnabledTrue(policyId);

        List<FirewallRuleViolation> violations = new ArrayList<>();
        for (FirewallPolicyRuleEntity rule : applicable) {
            evaluateRule(rule, findings).ifPresent(violations::add);
        }

        boolean blocks = violations.stream().anyMatch(FirewallRuleViolation::blocks);
        if (!blocks) {
            return FirewallDecision.allowed(policyId, policyName, violations);
        }
        return preExisting
                ? FirewallDecision.preExisting(policyId, policyName, violations)
                : FirewallDecision.blocked(policyId, policyName, violations);
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

    private Optional<FirewallRuleViolation> evaluateRule(
            FirewallPolicyRuleEntity rule, List<AdvisoryFinding> findings) {

        if (rule.getRuleType() == null || findings == null || findings.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> config = rule.getConfig() == null ? Map.of() : rule.getConfig();
        FirewallAction action = rule.getAction() == null ? FirewallAction.WARN : rule.getAction();
        MatchConfidence minConfidence = minConfidence(config, action);

        return switch (rule.getRuleType()) {
            case CVSS_THRESHOLD -> cvssThreshold(config, action, minConfidence, findings);
            case KNOWN_MALICIOUS -> knownMalicious(config, action, minConfidence, findings);
            case ADVISORY_MATCH,
                 LICENSE,
                 MIN_AGE,
                 UNKNOWN_COMPONENT,
                 TYPOSQUAT,
                 NAMESPACE_CONFUSION -> {
                // Not implemented in this increment. Skipping is the only safe
                // reading: a rule the engine cannot evaluate has not matched.
                log.debug("Firewall policy rule type {} is configured but not implemented; skipped",
                        rule.getRuleType());
                yield Optional.empty();
            }
        };
    }

    private static Optional<FirewallRuleViolation> cvssThreshold(
            Map<String, Object> config,
            FirewallAction action,
            MatchConfidence minConfidence,
            List<AdvisoryFinding> findings) {

        double minScore = number(config, "minScore", DEFAULT_MIN_SCORE);
        TreeSet<String> ids = new TreeSet<>();
        double worst = Double.NEGATIVE_INFINITY;

        for (AdvisoryFinding finding : findings) {
            if (!qualifies(finding, minConfidence) || finding.cvssScore() == null) {
                continue;
            }
            if (finding.cvssScore() >= minScore) {
                ids.addAll(finding.advisoryIds());
                worst = Math.max(worst, finding.cvssScore());
            }
        }
        if (ids.isEmpty()) {
            return Optional.empty();
        }
        String reason = "CVSS %s is at or above the configured threshold of %s"
                .formatted(format(worst), format(minScore));
        return Optional.of(new FirewallRuleViolation(
                FirewallRuleType.CVSS_THRESHOLD, action, reason, List.copyOf(ids)));
    }

    private static Optional<FirewallRuleViolation> knownMalicious(
            Map<String, Object> config,
            FirewallAction action,
            MatchConfidence minConfidence,
            List<AdvisoryFinding> findings) {

        List<String> prefixes = prefixes(config);
        TreeSet<String> ids = new TreeSet<>();

        for (AdvisoryFinding finding : findings) {
            if (!qualifies(finding, minConfidence)) {
                continue;
            }
            for (String id : finding.advisoryIds()) {
                if (id != null && startsWithAny(id, prefixes)) {
                    ids.add(id);
                }
            }
        }
        if (ids.isEmpty()) {
            return Optional.empty();
        }
        String reason = ids.size() == 1
                ? "advisory %s flags this component as malicious".formatted(ids.first())
                : "advisories %s flag this component as malicious".formatted(String.join(", ", ids));
        return Optional.of(new FirewallRuleViolation(
                FirewallRuleType.KNOWN_MALICIOUS, action, reason, List.copyOf(ids)));
    }

    /** Whether a finding is trustworthy enough for this rule to act on it. */
    private static boolean qualifies(AdvisoryFinding finding, MatchConfidence minConfidence) {
        // MatchConfidence is declared strongest first, so "at least as strong as"
        // is compareTo <= 0.
        return finding.confidence().compareTo(minConfidence) <= 0;
    }

    /**
     * {@code minConfidence} from the rule config, defaulting by action: BLOCK
     * demands an EXACT (purl) match, WARN accepts heuristic CPE-derived ones.
     */
    private static MatchConfidence minConfidence(Map<String, Object> config, FirewallAction action) {
        Object raw = config.get("minConfidence");
        MatchConfidence byAction =
                action == FirewallAction.BLOCK ? MatchConfidence.EXACT : MatchConfidence.HEURISTIC;
        if (raw == null) {
            return byAction;
        }
        try {
            return MatchConfidence.valueOf(raw.toString().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            log.warn("Firewall policy rule has an unknown minConfidence '{}'; using {}", raw, byAction);
            return byAction;
        }
    }

    private static List<String> prefixes(Map<String, Object> config) {
        Object raw = config.get("idPrefixes");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return DEFAULT_MALICIOUS_PREFIXES;
        }
        List<String> parsed = new ArrayList<>();
        for (Object entry : list) {
            if (entry != null && !entry.toString().isBlank()) {
                parsed.add(entry.toString().trim());
            }
        }
        return parsed.isEmpty() ? DEFAULT_MALICIOUS_PREFIXES : parsed;
    }

    private static boolean startsWithAny(String value, List<String> prefixes) {
        String upper = value.toUpperCase(Locale.ROOT);
        for (String prefix : prefixes) {
            if (upper.startsWith(prefix.toUpperCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static double number(Map<String, Object> config, String key, double fallback) {
        Object raw = config.get(key);
        if (raw instanceof Number value) {
            return value.doubleValue();
        }
        if (raw != null) {
            try {
                return Double.parseDouble(raw.toString().trim());
            } catch (NumberFormatException e) {
                log.warn("Firewall policy rule has a non-numeric {} '{}'; using {}", key, raw, fallback);
            }
        }
        return fallback;
    }

    /** {@code 9.0} rather than {@code 9.0000000001}, and {@code 10} rather than {@code 10.0}. */
    private static String format(double score) {
        if (score == Math.rint(score)) {
            return String.valueOf((long) score);
        }
        return String.valueOf(Math.round(score * 10.0) / 10.0);
    }

    /** The same two rules V16 seeds, for the case where the seeded rows are gone. */
    private static List<FirewallPolicyRuleEntity> builtInRules() {
        FirewallPolicyRuleEntity cvss = new FirewallPolicyRuleEntity();
        cvss.setRuleType(FirewallRuleType.CVSS_THRESHOLD);
        cvss.setAction(FirewallAction.BLOCK);
        cvss.setConfig(Map.of("minScore", DEFAULT_MIN_SCORE));

        FirewallPolicyRuleEntity malicious = new FirewallPolicyRuleEntity();
        malicious.setRuleType(FirewallRuleType.KNOWN_MALICIOUS);
        malicious.setAction(FirewallAction.BLOCK);
        malicious.setConfig(Map.of());

        return List.of(cvss, malicious);
    }
}
