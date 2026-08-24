package de.bsnsoft.megarepo.repository.firewall.rule.impl;

import de.bsnsoft.megarepo.core.firewall.FirewallAction;
import de.bsnsoft.megarepo.core.firewall.FirewallQuarantineReason;
import de.bsnsoft.megarepo.core.firewall.FirewallRuleType;
import de.bsnsoft.megarepo.repository.advisory.MatchConfidence;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRuleContext;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRuleOutcome;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRuleSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code UNKNOWN_COMPONENT}: what counts as "the firewall knows nothing about
 * this", and — the distinction the whole design turns on — why that is never the
 * same statement as "the component facts have not been resolved".
 */
class UnknownComponentRuleTest {

    private final UnknownComponentRule rule = new UnknownComponentRule();

    // ------------------------------------------------------------------ SPI

    @Test
    @DisplayName("holds under UNKNOWN_COMPONENT and is the one rule that judges unidentifiable artifacts")
    void spiDeclarations() {
        assertThat(rule.ruleType()).isEqualTo(FirewallRuleType.UNKNOWN_COMPONENT);
        assertThat(rule.quarantineOnMatch()).isTrue();
        assertThat(rule.quarantineReason()).isEqualTo(FirewallQuarantineReason.UNKNOWN_COMPONENT);
        assertThat(rule.appliesToUnidentifiedComponents()).isTrue();
    }

    // -------------------------------------------------------------- verdicts

    @Test
    @DisplayName("a proxied component no advisory names is unknown")
    void silenceMatches() {
        FirewallRuleOutcome outcome = rule.evaluate(RuleContexts.proxied().build(), block());

        assertThat(outcome.matched()).isTrue();
        assertThat(outcome.violation().ruleType()).isEqualTo(FirewallRuleType.UNKNOWN_COMPONENT);
        assertThat(outcome.violation().advisoryIds()).isEmpty();
        assertThat(outcome.violation().reason())
                .isEqualTo("no advisory source has any entry for pkg:maven/com.acme/util@1.0.0");
    }

    @Test
    @DisplayName("an advisory that named the package by purl makes it known")
    void exactFindingIsKnowledge() {
        FirewallRuleContext context = RuleContexts.proxied()
                .findings(RuleContexts.finding("GHSA-1", MatchConfidence.EXACT))
                .build();

        assertThat(rule.evaluate(context, block()).kind())
                .isEqualTo(FirewallRuleOutcome.Kind.NOT_MATCHED);
    }

    @Test
    @DisplayName("a BLOCK rule does not accept a CPE-derived match as knowledge, and says so")
    void heuristicFindingDoesNotSatisfyABlockingRule() {
        FirewallRuleContext context = RuleContexts.proxied()
                .findings(RuleContexts.finding("CVE-1", MatchConfidence.HEURISTIC))
                .build();

        FirewallRuleOutcome outcome = rule.evaluate(context, block());

        assertThat(outcome.matched())
                .as("a name collision must not certify an unknown package as known")
                .isTrue();
        assertThat(outcome.violation().reason())
                .isEqualTo("no advisory source names pkg:maven/com.acme/util@1.0.0 with exact "
                        + "confidence (1 weaker match was disregarded)");
    }

    @Test
    @DisplayName("a WARN rule accepts one — warn about everything, hold only on what was identified")
    void heuristicFindingSatisfiesAWarningRule() {
        FirewallRuleContext context = RuleContexts.proxied()
                .findings(RuleContexts.finding("CVE-1", MatchConfidence.HEURISTIC))
                .build();

        assertThat(rule.evaluate(context, settings(FirewallAction.WARN, Map.of())).matched()).isFalse();
    }

    @Test
    @DisplayName("minConfidence overrides the default for the operator who disagrees")
    void minConfidenceIsOverridable() {
        FirewallRuleContext context = RuleContexts.proxied()
                .findings(RuleContexts.finding("CVE-1", MatchConfidence.HEURISTIC))
                .build();

        FirewallRuleSettings lenient =
                settings(FirewallAction.BLOCK, Map.of("minConfidence", "HEURISTIC"));

        assertThat(rule.evaluate(context, lenient).matched()).isFalse();
    }

    @Test
    @DisplayName("several disregarded findings are counted in the plural")
    void disregardedFindingsAreCounted() {
        FirewallRuleContext context = RuleContexts.proxied()
                .findings(
                        RuleContexts.finding("CVE-1", MatchConfidence.HEURISTIC),
                        RuleContexts.finding("CVE-2", MatchConfidence.HEURISTIC))
                .build();

        assertThat(rule.evaluate(context, block()).violation().reason())
                .endsWith("(2 weaker matches were disregarded)");
    }

    // ------------------------------------------------------ where it applies

    @Test
    @DisplayName("a component published here is ours, not unknown")
    void hostedComponentsAreNotJudged() {
        assertThat(rule.evaluate(RuleContexts.hosted().build(), block()).matched())
                .as("no advisory feed will ever name an internal package, so the entry could never clear")
                .isFalse();
    }

    @Test
    @DisplayName("an operator can opt hosted components back in")
    void hostedComponentsCanBeOptedIn() {
        FirewallRuleSettings inclusive = settings(
                FirewallAction.BLOCK,
                Map.of(UnknownComponentRule.CONFIG_INCLUDE_HOSTED, true));

        assertThat(rule.evaluate(RuleContexts.hosted().build(), inclusive).matched()).isTrue();
    }

    @Test
    @DisplayName("an upload is never refused for being unheard of")
    void uploadsAreNeverJudged() {
        FirewallRuleSettings inclusive = settings(
                FirewallAction.BLOCK,
                Map.of(UnknownComponentRule.CONFIG_INCLUDE_HOSTED, true));

        assertThat(rule.evaluate(RuleContexts.hosted().upload().build(), inclusive).matched())
                .as("every release would be denied on the day it is made")
                .isFalse();
    }

    // ------------------------------------------------- unidentified artifacts

    @Test
    @DisplayName("an artifact with neither coordinates nor a digest matches")
    void unidentifiedMatches() {
        FirewallRuleContext context =
                RuleContexts.proxied().identity(RuleContexts.unidentified("raw")).build();

        assertThat(rule.evaluate(context, block()).violation().reason())
                .isEqualTo("could not be identified as a package — no coordinates and no content "
                        + "digest, so no advisory source can be asked about it");
    }

    @Test
    @DisplayName("formats that structurally have no coordinates can be exempted, case-insensitively")
    void allowedFormatsAreExempt() {
        FirewallRuleContext context =
                RuleContexts.proxied().identity(RuleContexts.unidentified("raw")).build();
        FirewallRuleSettings exempt = settings(
                FirewallAction.BLOCK,
                Map.of(UnknownComponentRule.CONFIG_ALLOW_UNIDENTIFIED_FORMATS, List.of("RAW")));

        assertThat(rule.evaluate(context, exempt).matched()).isFalse();
    }

    @Test
    @DisplayName("a format that is not on the list still matches")
    void otherFormatsStillMatch() {
        FirewallRuleContext context =
                RuleContexts.proxied().identity(RuleContexts.unidentified("docker")).build();
        FirewallRuleSettings exempt = settings(
                FirewallAction.BLOCK,
                Map.of(UnknownComponentRule.CONFIG_ALLOW_UNIDENTIFIED_FORMATS, List.of("raw")));

        assertThat(rule.evaluate(context, exempt).matched()).isTrue();
    }

    @Test
    @DisplayName("a digest identifies an artifact exactly and describes it not at all")
    void hashIdentityMatches() {
        FirewallRuleContext context = RuleContexts.proxied().identity(RuleContexts.hash()).build();
        FirewallRuleSettings exempt = settings(
                FirewallAction.BLOCK,
                Map.of(UnknownComponentRule.CONFIG_ALLOW_UNIDENTIFIED_FORMATS, List.of("raw")));

        FirewallRuleOutcome outcome = rule.evaluate(context, exempt);

        assertThat(outcome.violation().reason())
                .isEqualTo("is identified only by its sha256 digest; no advisory source can be "
                        + "queried for a content hash");
        assertThat(outcome.matched())
                .as("the format exemption cannot reach a hash identity — the context carries no format")
                .isTrue();
    }

    // ------------------------------------------- unknown component vs no facts

    @Test
    @DisplayName("unresolved component facts are none of this rule's business")
    void factsStateIsIrrelevant() {
        FirewallRuleContext unresolvedFacts = RuleContexts.proxied()
                .findings(RuleContexts.finding("GHSA-1", MatchConfidence.EXACT))
                .build();

        assertThat(unresolvedFacts.facts().isIndeterminate())
                .as("the fixture really does have unresolved facts")
                .isTrue();
        assertThat(rule.evaluate(unresolvedFacts, block()).kind())
                .as("advisory silence is this rule's subject; a missing publication date is not")
                .isEqualTo(FirewallRuleOutcome.Kind.NOT_MATCHED);
    }

    @Test
    @DisplayName("the rule never reports INDETERMINATE, whatever the facts say")
    void neverIndeterminate() {
        assertThat(rule.evaluate(RuleContexts.proxied().build(), block()).indeterminate())
                .as("no advisory data is a verdict, not a missing input")
                .isFalse();
        assertThat(rule.evaluate(RuleContexts.hosted().build(), block()).indeterminate()).isFalse();
        assertThat(rule.evaluate(
                        RuleContexts.proxied().identity(RuleContexts.hash()).build(), block())
                .indeterminate())
                .isFalse();
    }

    @Test
    @DisplayName("a pre-existing component still matches — the engine is what declines to deny it")
    void preExistingStillMatches() {
        assertThat(rule.evaluate(RuleContexts.proxied().preExisting().build(), block()).matched())
                .isTrue();
    }

    private static FirewallRuleSettings block() {
        return settings(FirewallAction.BLOCK, Map.of());
    }

    private static FirewallRuleSettings settings(FirewallAction action, Map<String, Object> config) {
        return FirewallRuleSettings.of(FirewallRuleType.UNKNOWN_COMPONENT, action, config);
    }
}
