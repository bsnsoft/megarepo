package de.bsnsoft.megarepo.repository.firewall;

import de.bsnsoft.megarepo.core.firewall.FirewallAction;
import de.bsnsoft.megarepo.core.firewall.FirewallFailMode;
import de.bsnsoft.megarepo.core.firewall.FirewallMode;
import de.bsnsoft.megarepo.core.firewall.FirewallRuleType;
import de.bsnsoft.megarepo.database.entity.FirewallPolicyEntity;
import de.bsnsoft.megarepo.database.entity.FirewallPolicyRuleEntity;
import de.bsnsoft.megarepo.database.repository.FirewallPolicyJpaRepository;
import de.bsnsoft.megarepo.database.repository.FirewallPolicyRuleJpaRepository;
import de.bsnsoft.megarepo.repository.advisory.AdvisoryFinding;
import de.bsnsoft.megarepo.repository.advisory.AdvisoryMatch;
import de.bsnsoft.megarepo.repository.advisory.MatchConfidence;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * The policy engine on its own: which rules match, what they say, and — the
 * part that matters most — what they refuse to do.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FirewallPolicyEvaluatorTest {

    private static final UUID POLICY_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Mock private FirewallPolicyJpaRepository policies;
    @Mock private FirewallPolicyRuleJpaRepository rules;

    private FirewallPolicyEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new FirewallPolicyEvaluator(policies, rules);
        FirewallPolicyEntity policy = new FirewallPolicyEntity();
        policy.setId(POLICY_ID);
        policy.setName("Default");
        policy.setDefault(true);
        when(policies.findByIsDefaultTrue()).thenReturn(Optional.of(policy));
    }

    @Test
    @DisplayName("a critical advisory trips the CVSS rule and the decision blocks")
    void cvssThresholdBlocks() {
        givenRules(cvssRule(FirewallAction.BLOCK, 9.0));

        FirewallDecision decision = evaluator.evaluate(
                settings(), List.of(finding("GHSA-jfh8-c2jp-5v3q", 10.0, MatchConfidence.EXACT)), false);

        assertThat(decision.blocked()).isTrue();
        assertThat(decision.reason()).isEqualTo(FirewallDecision.Reason.POLICY);
        assertThat(decision.policyName()).isEqualTo("Default");
        assertThat(decision.violations()).hasSize(1);
        assertThat(decision.violations().get(0).ruleType()).isEqualTo(FirewallRuleType.CVSS_THRESHOLD);
        assertThat(decision.violations().get(0).reason())
                .as("a build log has to be able to say what the numbers were")
                .isEqualTo("CVSS 10 is at or above the configured threshold of 9");
        assertThat(decision.advisoryIds()).containsExactly("GHSA-jfh8-c2jp-5v3q");
    }

    @Test
    @DisplayName("a finding below the threshold matches nothing")
    void belowThresholdIsClean() {
        givenRules(cvssRule(FirewallAction.BLOCK, 9.0));

        FirewallDecision decision = evaluator.evaluate(
                settings(), List.of(finding("CVE-2020-1", 7.5, MatchConfidence.EXACT)), false);

        assertThat(decision.blocked()).isFalse();
        assertThat(decision.violations()).isEmpty();
        assertThat(decision.reason()).isEqualTo(FirewallDecision.Reason.ALLOWED);
    }

    @Test
    @DisplayName("WARN never blocks — it matches, it is recorded, and the download goes out")
    void warnRecordsWithoutBlocking() {
        givenRules(cvssRule(FirewallAction.WARN, 9.0));

        FirewallDecision decision = evaluator.evaluate(
                settings(), List.of(finding("GHSA-jfh8-c2jp-5v3q", 10.0, MatchConfidence.EXACT)), false);

        assertThat(decision.blocked()).isFalse();
        assertThat(decision.violations()).hasSize(1);
        assertThat(decision.violations().get(0).action()).isEqualTo(FirewallAction.WARN);
        assertThat(decision.blockingViolations()).isEmpty();
    }

    @Test
    @DisplayName("an OSV MAL- entry blocks on its own, with no CVSS score involved")
    void knownMaliciousBlocksWithoutAScore() {
        givenRules(maliciousRule(FirewallAction.BLOCK));

        FirewallDecision decision = evaluator.evaluate(
                settings(), List.of(finding("MAL-2024-1234", null, MatchConfidence.EXACT)), false);

        assertThat(decision.blocked()).isTrue();
        assertThat(decision.violations().get(0).ruleType()).isEqualTo(FirewallRuleType.KNOWN_MALICIOUS);
        assertThat(decision.violations().get(0).reason())
                .isEqualTo("advisory MAL-2024-1234 flags this component as malicious");
    }

    @Test
    @DisplayName("a CPE-derived match does not block: a product-name collision must not break a build")
    void heuristicMatchesDoNotBlockByDefault() {
        givenRules(cvssRule(FirewallAction.BLOCK, 9.0));

        FirewallDecision decision = evaluator.evaluate(
                settings(), List.of(finding("CVE-2021-44228", 10.0, MatchConfidence.HEURISTIC)), false);

        assertThat(decision.blocked())
                .as("NVD matched on the product name alone; that is not grounds for a silent block")
                .isFalse();
        assertThat(decision.violations()).isEmpty();
    }

    @Test
    @DisplayName("a WARN rule does record the CPE-derived match it would not block on")
    void heuristicMatchesStillWarn() {
        givenRules(cvssRule(FirewallAction.WARN, 9.0));

        FirewallDecision decision = evaluator.evaluate(
                settings(), List.of(finding("CVE-2021-44228", 10.0, MatchConfidence.HEURISTIC)), false);

        assertThat(decision.violations()).hasSize(1);
        assertThat(decision.blocked()).isFalse();
    }

    @Test
    @DisplayName("minConfidence can be lowered per rule when an operator wants CPE matches to count")
    void minConfidenceIsConfigurable() {
        FirewallPolicyRuleEntity rule = cvssRule(FirewallAction.BLOCK, 9.0);
        rule.setConfig(Map.of("minScore", 9.0, "minConfidence", "HEURISTIC"));
        givenRules(rule);

        FirewallDecision decision = evaluator.evaluate(
                settings(), List.of(finding("CVE-2021-44228", 10.0, MatchConfidence.HEURISTIC)), false);

        assertThat(decision.blocked()).isTrue();
    }

    @Test
    @DisplayName("a component that was already in the repository is recorded but not blocked")
    void preExistingComponentIsNeverBlocked() {
        givenRules(cvssRule(FirewallAction.BLOCK, 9.0));

        FirewallDecision decision = evaluator.evaluate(
                settings(), List.of(finding("GHSA-jfh8-c2jp-5v3q", 10.0, MatchConfidence.EXACT)), true);

        assertThat(decision.blocked())
                .as("switching enforcement on must not break builds against artifacts already cached")
                .isFalse();
        assertThat(decision.reason()).isEqualTo(FirewallDecision.Reason.PRE_EXISTING);
        assertThat(decision.violations())
                .as("the rule still matched, and the audit trail has to say so")
                .hasSize(1);
        assertThat(decision.violations().get(0).action())
                .as("the violation records what the rule asked for")
                .isEqualTo(FirewallAction.BLOCK);
    }

    @Test
    @DisplayName("an unimplemented rule type is skipped, never treated as a match")
    void unimplementedRuleTypesAreSkipped() {
        FirewallPolicyRuleEntity license = new FirewallPolicyRuleEntity();
        license.setPolicyId(POLICY_ID);
        license.setRuleType(FirewallRuleType.LICENSE);
        license.setAction(FirewallAction.BLOCK);
        license.setConfig(Map.of("deny", List.of("GPL-3.0")));
        givenRules(license);

        FirewallDecision decision = evaluator.evaluate(
                settings(), List.of(finding("GHSA-jfh8-c2jp-5v3q", 10.0, MatchConfidence.EXACT)), false);

        assertThat(decision.blocked()).isFalse();
        assertThat(decision.violations()).isEmpty();
    }

    @Test
    @DisplayName("a disabled rule is not even loaded")
    void onlyEnabledRulesAreLoaded() {
        when(rules.findByPolicyIdAndEnabledTrue(POLICY_ID)).thenReturn(List.of());

        FirewallDecision decision = evaluator.evaluate(
                settings(), List.of(finding("GHSA-jfh8-c2jp-5v3q", 10.0, MatchConfidence.EXACT)), false);

        assertThat(decision.blocked()).isFalse();
    }

    @Test
    @DisplayName("with no policy at all the built-in rules apply rather than silently allowing everything")
    void missingPolicyFallsBackToBuiltInRules() {
        when(policies.findByIsDefaultTrue()).thenReturn(Optional.empty());

        FirewallDecision decision = evaluator.evaluate(
                settings(), List.of(finding("MAL-2024-1234", null, MatchConfidence.EXACT)), false);

        assertThat(decision.blocked()).isTrue();
        assertThat(decision.policyName()).isEqualTo("built-in default");
        assertThat(decision.policyId()).isNull();
    }

    @Test
    @DisplayName("a non-numeric threshold falls back to the default instead of denying everything")
    void malformedConfigFallsBack() {
        FirewallPolicyRuleEntity rule = cvssRule(FirewallAction.BLOCK, 9.0);
        rule.setConfig(Map.of("minScore", "not a number"));
        givenRules(rule);

        FirewallDecision belowDefault = evaluator.evaluate(
                settings(), List.of(finding("CVE-2020-1", 7.5, MatchConfidence.EXACT)), false);
        FirewallDecision aboveDefault = evaluator.evaluate(
                settings(), List.of(finding("CVE-2021-44228", 9.8, MatchConfidence.EXACT)), false);

        assertThat(belowDefault.blocked()).isFalse();
        assertThat(aboveDefault.blocked()).isTrue();
    }

    @Test
    @DisplayName("two rules that both match produce two violations, each with its own reason")
    void bothRulesCanMatch() {
        givenRules(cvssRule(FirewallAction.BLOCK, 9.0), maliciousRule(FirewallAction.BLOCK));

        FirewallDecision decision = evaluator.evaluate(
                settings(),
                List.of(
                        finding("GHSA-jfh8-c2jp-5v3q", 10.0, MatchConfidence.EXACT),
                        finding("MAL-2024-1234", null, MatchConfidence.EXACT)),
                false);

        assertThat(decision.blocked()).isTrue();
        assertThat(decision.violations())
                .extracting(FirewallRuleViolation::ruleType)
                .containsExactly(FirewallRuleType.CVSS_THRESHOLD, FirewallRuleType.KNOWN_MALICIOUS);
        assertThat(decision.advisoryIds())
                .containsExactly("GHSA-jfh8-c2jp-5v3q", "MAL-2024-1234");
    }

    @Test
    @DisplayName("no findings, no verdict to reach")
    void noFindingsNoViolations() {
        givenRules(cvssRule(FirewallAction.BLOCK, 9.0), maliciousRule(FirewallAction.BLOCK));

        FirewallDecision decision = evaluator.evaluate(settings(), List.of(), false);

        assertThat(decision.blocked()).isFalse();
        assertThat(decision.violations()).isEmpty();
    }

    private void givenRules(FirewallPolicyRuleEntity... entities) {
        when(rules.findByPolicyIdAndEnabledTrue(POLICY_ID)).thenReturn(List.of(entities));
    }

    private static FirewallRepositorySettings settings() {
        return new FirewallRepositorySettings(
                FirewallMode.QUARANTINE, FirewallFailMode.FAIL_OPEN, null, true);
    }

    private static FirewallPolicyRuleEntity cvssRule(FirewallAction action, double minScore) {
        FirewallPolicyRuleEntity rule = new FirewallPolicyRuleEntity();
        rule.setPolicyId(POLICY_ID);
        rule.setRuleType(FirewallRuleType.CVSS_THRESHOLD);
        rule.setAction(action);
        rule.setConfig(Map.of("minScore", minScore));
        return rule;
    }

    private static FirewallPolicyRuleEntity maliciousRule(FirewallAction action) {
        FirewallPolicyRuleEntity rule = new FirewallPolicyRuleEntity();
        rule.setPolicyId(POLICY_ID);
        rule.setRuleType(FirewallRuleType.KNOWN_MALICIOUS);
        rule.setAction(action);
        rule.setConfig(Map.of());
        return rule;
    }

    private static AdvisoryFinding finding(String id, Double score, MatchConfidence confidence) {
        return new AdvisoryFinding(
                id, "summary", score == null ? null : "CRITICAL", score, null, null, null,
                List.of(new AdvisoryMatch(id, "OSV", confidence, ">=1.0, <2.0")));
    }
}
