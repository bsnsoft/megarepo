package de.bsnsoft.megarepo.repository.firewall.rule.impl;

import de.bsnsoft.megarepo.core.firewall.FirewallAction;
import de.bsnsoft.megarepo.core.firewall.FirewallRuleType;
import de.bsnsoft.megarepo.repository.advisory.MatchConfidence;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRuleContext;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRuleOutcome;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRuleSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code CVSS_THRESHOLD} on its own — the rule an operator migrating from the V8
 * NVD firewall expects to find unchanged.
 *
 * <p>Phase 1 implemented this inside {@code FirewallPolicyEvaluator}'s switch and
 * asserted it through the engine. It is a bean now, so the threshold arithmetic,
 * the confidence filter and the malformed-config fallback are asserted here,
 * where a failure names the rule rather than the engine.
 */
class CvssThresholdRuleTest {

    private final CvssThresholdRule rule = new CvssThresholdRule();

    // ------------------------------------------------------------------ SPI

    @Test
    @DisplayName("refuses outright rather than holding: a CVSS score does not fall by waiting")
    void neverQuarantines() {
        assertThat(rule.ruleType()).isEqualTo(FirewallRuleType.CVSS_THRESHOLD);
        assertThat(rule.quarantineOnMatch())
                .as("a release button next to a critical advisory is an invitation (design 5.1)")
                .isFalse();
        assertThat(rule.appliesToUnidentifiedComponents())
                .as("advisory feeds name packages, not content digests")
                .isFalse();
    }

    // ------------------------------------------------------------- threshold

    @Test
    @DisplayName("a score above the threshold matches, and the reason states both numbers")
    void aboveThresholdMatches() {
        FirewallRuleContext context = RuleContexts.proxied()
                .findings(RuleContexts.finding("GHSA-jfh8-c2jp-5v3q", 10.0, MatchConfidence.EXACT))
                .build();

        FirewallRuleOutcome outcome = rule.evaluate(context, cvss(9.0, FirewallAction.BLOCK));

        assertThat(outcome.matched()).isTrue();
        assertThat(outcome.violation().ruleType()).isEqualTo(FirewallRuleType.CVSS_THRESHOLD);
        assertThat(outcome.violation().action()).isEqualTo(FirewallAction.BLOCK);
        assertThat(outcome.violation().reason())
                .as("a build log has to be able to say what the numbers were")
                .isEqualTo("CVSS 10 is at or above the configured threshold of 9");
        assertThat(outcome.violation().advisoryIds()).containsExactly("GHSA-jfh8-c2jp-5v3q");
    }

    @Test
    @DisplayName("exactly at the threshold matches — 'at or above' is what the message promises")
    void thresholdIsInclusive() {
        FirewallRuleContext context = RuleContexts.proxied()
                .findings(RuleContexts.finding("CVE-2021-44228", 9.0, MatchConfidence.EXACT))
                .build();

        assertThat(rule.evaluate(context, cvss(9.0, FirewallAction.BLOCK)).matched()).isTrue();
    }

    @Test
    @DisplayName("one tenth below the threshold matches nothing")
    void belowThresholdDoesNotMatch() {
        FirewallRuleContext context = RuleContexts.proxied()
                .findings(RuleContexts.finding("CVE-2020-1", 8.9, MatchConfidence.EXACT))
                .build();

        assertThat(rule.evaluate(context, cvss(9.0, FirewallAction.BLOCK)).kind())
                .isEqualTo(FirewallRuleOutcome.Kind.NOT_MATCHED);
    }

    @Test
    @DisplayName("an advisory with no CVSS score is not scored as zero and not scored as ten")
    void nullScoreIsSkipped() {
        FirewallRuleContext context = RuleContexts.proxied()
                .findings(RuleContexts.finding("MAL-2024-1234", null, MatchConfidence.EXACT))
                .build();

        assertThat(rule.evaluate(context, cvss(9.0, FirewallAction.BLOCK)).matched())
                .as("an unscored advisory is KNOWN_MALICIOUS's business, not this rule's")
                .isFalse();
    }

    @Test
    @DisplayName("the worst qualifying score is the one the message quotes")
    void worstScoreIsReported() {
        FirewallRuleContext context = RuleContexts.proxied()
                .findings(
                        RuleContexts.finding("CVE-2021-44228", 9.2, MatchConfidence.EXACT),
                        RuleContexts.finding("GHSA-jfh8-c2jp-5v3q", 9.8, MatchConfidence.EXACT),
                        RuleContexts.finding("CVE-2020-1", 4.0, MatchConfidence.EXACT))
                .build();

        FirewallRuleOutcome outcome = rule.evaluate(context, cvss(9.0, FirewallAction.BLOCK));

        assertThat(outcome.violation().reason())
                .isEqualTo("CVSS 9.8 is at or above the configured threshold of 9");
        assertThat(outcome.violation().advisoryIds())
                .as("the advisory below the threshold contributed nothing and is not named")
                .containsExactly("CVE-2021-44228", "GHSA-jfh8-c2jp-5v3q");
    }

    @Test
    @DisplayName("no findings at all, nothing to compare against")
    void noFindingsDoNotMatch() {
        assertThat(rule.evaluate(RuleContexts.proxied().build(), cvss(9.0, FirewallAction.BLOCK)).kind())
                .isEqualTo(FirewallRuleOutcome.Kind.NOT_MATCHED);
    }

    // ------------------------------------------------------------ confidence

    @Test
    @DisplayName("a BLOCK rule ignores a CPE-derived match: a product-name collision must not fail a build")
    void blockDemandsAnExactMatch() {
        FirewallRuleContext context = RuleContexts.proxied()
                .findings(RuleContexts.finding("CVE-2021-44228", 10.0, MatchConfidence.HEURISTIC))
                .build();

        assertThat(rule.evaluate(context, cvss(9.0, FirewallAction.BLOCK)).matched())
                .as("this is the V8 firewall's false-positive class, and it stays out")
                .isFalse();
    }

    @Test
    @DisplayName("a WARN rule does record the CPE-derived match it would not block on")
    void warnAcceptsAHeuristicMatch() {
        FirewallRuleContext context = RuleContexts.proxied()
                .findings(RuleContexts.finding("CVE-2021-44228", 10.0, MatchConfidence.HEURISTIC))
                .build();

        FirewallRuleOutcome outcome = rule.evaluate(context, cvss(9.0, FirewallAction.WARN));

        assertThat(outcome.matched()).isTrue();
        assertThat(outcome.violation().action())
                .as("the rule reports what the policy asked for; enforcing is the engine's job")
                .isEqualTo(FirewallAction.WARN);
    }

    @Test
    @DisplayName("minConfidence can be lowered per rule when an operator wants CPE matches to count")
    void minConfidenceIsConfigurable() {
        FirewallRuleContext context = RuleContexts.proxied()
                .findings(RuleContexts.finding("CVE-2021-44228", 10.0, MatchConfidence.HEURISTIC))
                .build();

        FirewallRuleSettings lowered = FirewallRuleSettings.of(
                FirewallRuleType.CVSS_THRESHOLD,
                FirewallAction.BLOCK,
                Map.of("minScore", 9.0, "minConfidence", "HEURISTIC"));

        assertThat(rule.evaluate(context, lowered).matched()).isTrue();
    }

    // --------------------------------------------------------- configuration

    @Test
    @DisplayName("a non-numeric minScore falls back to the default instead of denying everything")
    void malformedMinScoreFallsBack() {
        FirewallRuleSettings malformed = FirewallRuleSettings.of(
                FirewallRuleType.CVSS_THRESHOLD,
                FirewallAction.BLOCK,
                Map.of("minScore", "not a number"));

        FirewallRuleContext belowDefault = RuleContexts.proxied()
                .findings(RuleContexts.finding("CVE-2020-1", 7.5, MatchConfidence.EXACT))
                .build();
        FirewallRuleContext aboveDefault = RuleContexts.proxied()
                .findings(RuleContexts.finding("CVE-2021-44228", 9.8, MatchConfidence.EXACT))
                .build();

        assertThat(rule.evaluate(belowDefault, malformed).matched())
                .as("a policy typo must not deny every download in the repository")
                .isFalse();
        assertThat(rule.evaluate(aboveDefault, malformed).matched())
                .as("the fallback is the default threshold, not 'off'")
                .isTrue();
        assertThat(CvssThresholdRule.DEFAULT_MIN_SCORE).isEqualTo(9.0);
    }

    @Test
    @DisplayName("a numeric string is read as the number an operator meant")
    void numericStringIsAccepted() {
        FirewallRuleContext context = RuleContexts.proxied()
                .findings(RuleContexts.finding("CVE-2021-44228", 8.0, MatchConfidence.EXACT))
                .build();

        FirewallRuleSettings asText = FirewallRuleSettings.of(
                FirewallRuleType.CVSS_THRESHOLD, FirewallAction.BLOCK, Map.of("minScore", "7.5"));

        assertThat(rule.evaluate(context, asText).matched()).isTrue();
    }

    @Test
    @DisplayName("scores read like scores: 10 not 10.0, 9.8 not 9.800000000000001")
    void scoresAreFormattedForHumans() {
        assertThat(CvssThresholdRule.format(10.0)).isEqualTo("10");
        assertThat(CvssThresholdRule.format(9.0)).isEqualTo("9");
        assertThat(CvssThresholdRule.format(9.8)).isEqualTo("9.8");
        assertThat(CvssThresholdRule.format(7.55)).isEqualTo("7.6");
    }

    // ------------------------------------------------------------ boundaries

    @Test
    @DisplayName("a pre-existing component still matches — declining to deny it is the engine's job")
    void preExistingStillMatches() {
        FirewallRuleContext context = RuleContexts.proxied()
                .preExisting()
                .findings(RuleContexts.finding("GHSA-jfh8-c2jp-5v3q", 10.0, MatchConfidence.EXACT))
                .build();

        assertThat(rule.evaluate(context, cvss(9.0, FirewallAction.BLOCK)).matched()).isTrue();
    }

    private static FirewallRuleSettings cvss(double minScore, FirewallAction action) {
        return FirewallRuleSettings.of(
                FirewallRuleType.CVSS_THRESHOLD, action, Map.of("minScore", minScore));
    }
}
