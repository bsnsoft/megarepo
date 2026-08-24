package de.bsnsoft.megarepo.repository.firewall.rule.impl;

import de.bsnsoft.megarepo.core.firewall.FirewallRuleType;
import de.bsnsoft.megarepo.repository.advisory.AdvisoryFinding;
import de.bsnsoft.megarepo.repository.firewall.FirewallRuleViolation;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRule;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRuleContext;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRuleOutcome;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRuleSettings;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.TreeSet;

/**
 * Denies a component named by an advisory at or above a CVSS score.
 *
 * <p>The rule the V8 NVD firewall already had, so an operator migrating from it
 * finds the behaviour they know. Phase 1 implemented it inside
 * {@code FirewallPolicyEvaluator}'s switch; this is the same logic as a bean, so
 * that the engine no longer has to know which rules exist.
 *
 * <h2>Confidence</h2>
 *
 * Handled by {@link FirewallRuleSettings#minConfidence()}, which defaults to
 * {@code EXACT} for a {@code BLOCK} rule and {@code HEURISTIC} for a {@code WARN}
 * one. That default is the reason the V8 firewall's false positives do not come
 * back: an NVD CPE match on the product name alone is worth warning about and not
 * worth failing a build over.
 *
 * <h2>No quarantine</h2>
 *
 * A CVSS score does not fall by waiting. {@link #quarantineOnMatch()} stays false,
 * so a component denied by this rule is refused outright and produces no queue
 * entry — design §5.1: a release button next to a critical advisory is an
 * invitation.
 *
 * <p>Configuration: {@code {"minScore": 9.0, "minConfidence": "EXACT"}}.
 */
@Component
public class CvssThresholdRule implements FirewallRule {

    /** Threshold used when a rule row does not configure one. */
    public static final double DEFAULT_MIN_SCORE = 9.0;

    @Override
    public FirewallRuleType ruleType() {
        return FirewallRuleType.CVSS_THRESHOLD;
    }

    @Override
    public FirewallRuleOutcome evaluate(FirewallRuleContext context, FirewallRuleSettings settings) {
        List<AdvisoryFinding> qualifying = context.findingsAtLeast(settings.minConfidence());
        if (qualifying.isEmpty()) {
            return FirewallRuleOutcome.notMatched();
        }

        double minScore = settings.number("minScore", DEFAULT_MIN_SCORE);
        TreeSet<String> ids = new TreeSet<>();
        double worst = Double.NEGATIVE_INFINITY;

        for (AdvisoryFinding finding : qualifying) {
            if (finding.cvssScore() == null || finding.cvssScore() < minScore) {
                continue;
            }
            ids.addAll(finding.advisoryIds());
            worst = Math.max(worst, finding.cvssScore());
        }
        if (ids.isEmpty()) {
            return FirewallRuleOutcome.notMatched();
        }

        String reason = "CVSS %s is at or above the configured threshold of %s"
                .formatted(format(worst), format(minScore));
        return FirewallRuleOutcome.matched(new FirewallRuleViolation(
                FirewallRuleType.CVSS_THRESHOLD, settings.action(), reason, List.copyOf(ids)));
    }

    /** {@code 9.0} rather than {@code 9.0000000001}, and {@code 10} rather than {@code 10.0}. */
    static String format(double score) {
        if (score == Math.rint(score)) {
            return String.valueOf((long) score);
        }
        return String.valueOf(Math.round(score * 10.0) / 10.0);
    }
}
