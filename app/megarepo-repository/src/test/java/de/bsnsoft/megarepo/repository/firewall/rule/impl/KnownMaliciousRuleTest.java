package de.bsnsoft.megarepo.repository.firewall.rule.impl;

import de.bsnsoft.megarepo.core.firewall.FirewallAction;
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
 * {@code KNOWN_MALICIOUS} on its own.
 *
 * <p>The rule exists because a package written to steal credentials has no CVSS
 * score to compare against, so {@code CVSS_THRESHOLD} alone would serve it. Most
 * of what is asserted here is therefore about advisory <em>ids</em> rather than
 * numbers: which prefixes count, and what a build log is told about them.
 */
class KnownMaliciousRuleTest {

    private final KnownMaliciousRule rule = new KnownMaliciousRule();

    // ------------------------------------------------------------------ SPI

    @Test
    @DisplayName("refuses outright rather than holding: waiting does not make a credential stealer safe")
    void neverQuarantines() {
        assertThat(rule.ruleType()).isEqualTo(FirewallRuleType.KNOWN_MALICIOUS);
        assertThat(rule.quarantineOnMatch())
                .as("a queue entry would offer an operator a release button for a malicious package")
                .isFalse();
        assertThat(rule.appliesToUnidentifiedComponents()).isFalse();
    }

    // ---------------------------------------------------------------- prefix

    @Test
    @DisplayName("an OSV MAL- entry blocks on its own, with no CVSS score involved")
    void malPrefixMatchesWithoutAScore() {
        FirewallRuleContext context = RuleContexts.proxied()
                .findings(RuleContexts.finding("MAL-2024-1234", null, MatchConfidence.EXACT))
                .build();

        FirewallRuleOutcome outcome = rule.evaluate(context, malicious(FirewallAction.BLOCK));

        assertThat(outcome.matched()).isTrue();
        assertThat(outcome.violation().ruleType()).isEqualTo(FirewallRuleType.KNOWN_MALICIOUS);
        assertThat(outcome.violation().reason())
                .isEqualTo("advisory MAL-2024-1234 flags this component as malicious");
        assertThat(outcome.violation().advisoryIds()).containsExactly("MAL-2024-1234");
    }

    @Test
    @DisplayName("an ordinary CVE is not a malicious-package report, however severe")
    void otherPrefixesDoNotMatch() {
        FirewallRuleContext context = RuleContexts.proxied()
                .findings(RuleContexts.finding("CVE-2021-44228", 10.0, MatchConfidence.EXACT))
                .build();

        assertThat(rule.evaluate(context, malicious(FirewallAction.BLOCK)).kind())
                .as("a 10.0 vulnerability is CVSS_THRESHOLD's business, not this rule's")
                .isEqualTo(FirewallRuleOutcome.Kind.NOT_MATCHED);
    }

    @Test
    @DisplayName("the prefix is matched case-insensitively — feeds are not consistent about it")
    void prefixMatchIgnoresCase() {
        FirewallRuleContext context = RuleContexts.proxied()
                .findings(RuleContexts.finding("mal-2024-1234", null, MatchConfidence.EXACT))
                .build();

        assertThat(rule.evaluate(context, malicious(FirewallAction.BLOCK)).matched()).isTrue();
    }

    @Test
    @DisplayName("only the malicious ids are named, not every id the component has")
    void onlyMaliciousIdsAreReported() {
        FirewallRuleContext context = RuleContexts.proxied()
                .findings(
                        RuleContexts.finding("CVE-2021-44228", 10.0, MatchConfidence.EXACT),
                        RuleContexts.finding("MAL-2024-1234", null, MatchConfidence.EXACT))
                .build();

        FirewallRuleOutcome outcome = rule.evaluate(context, malicious(FirewallAction.BLOCK));

        assertThat(outcome.violation().advisoryIds())
                .as("naming the CVE here would claim it was the malicious-package report")
                .containsExactly("MAL-2024-1234");
    }

    @Test
    @DisplayName("several malicious ids read as a sentence, sorted so the message is reproducible")
    void severalIdsAreListed() {
        FirewallRuleContext context = RuleContexts.proxied()
                .findings(RuleContexts.merged(
                        null, MatchConfidence.EXACT, "MAL-2024-9999", "MAL-2024-1234"))
                .build();

        FirewallRuleOutcome outcome = rule.evaluate(context, malicious(FirewallAction.BLOCK));

        assertThat(outcome.violation().reason())
                .isEqualTo("advisories MAL-2024-1234, MAL-2024-9999 flag this component as malicious");
        assertThat(outcome.violation().advisoryIds())
                .containsExactly("MAL-2024-1234", "MAL-2024-9999");
    }

    @Test
    @DisplayName("no findings at all, nothing to read a prefix off")
    void noFindingsDoNotMatch() {
        assertThat(rule.evaluate(RuleContexts.proxied().build(), malicious(FirewallAction.BLOCK)).kind())
                .isEqualTo(FirewallRuleOutcome.Kind.NOT_MATCHED);
    }

    // ------------------------------------------------------------ confidence

    @Test
    @DisplayName("a BLOCK rule ignores a CPE-derived match, here as everywhere else")
    void blockDemandsAnExactMatch() {
        FirewallRuleContext context = RuleContexts.proxied()
                .findings(RuleContexts.finding("MAL-2024-1234", null, MatchConfidence.HEURISTIC))
                .build();

        assertThat(rule.evaluate(context, malicious(FirewallAction.BLOCK)).matched())
                .as("NVD matched on a product name; that is not grounds for calling a package malicious")
                .isFalse();
    }

    @Test
    @DisplayName("a WARN rule does record the CPE-derived match it would not block on")
    void warnAcceptsAHeuristicMatch() {
        FirewallRuleContext context = RuleContexts.proxied()
                .findings(RuleContexts.finding("MAL-2024-1234", null, MatchConfidence.HEURISTIC))
                .build();

        FirewallRuleOutcome outcome = rule.evaluate(context, malicious(FirewallAction.WARN));

        assertThat(outcome.matched()).isTrue();
        assertThat(outcome.violation().action()).isEqualTo(FirewallAction.WARN);
    }

    // --------------------------------------------------------- configuration

    @Test
    @DisplayName("the prefix list is configurable, and it replaces the default rather than extending it")
    void prefixesAreConfigurable() {
        FirewallRuleContext osv = RuleContexts.proxied()
                .findings(RuleContexts.finding("OSV-2024-5", null, MatchConfidence.EXACT))
                .build();
        FirewallRuleContext mal = RuleContexts.proxied()
                .findings(RuleContexts.finding("MAL-2024-1234", null, MatchConfidence.EXACT))
                .build();

        FirewallRuleSettings osvOnly = FirewallRuleSettings.of(
                FirewallRuleType.KNOWN_MALICIOUS,
                FirewallAction.BLOCK,
                Map.of("idPrefixes", List.of("OSV-")));

        assertThat(rule.evaluate(osv, osvOnly).matched()).isTrue();
        assertThat(rule.evaluate(mal, osvOnly).matched())
                .as("an operator who names one prefix gets that prefix, not that prefix plus ours")
                .isFalse();
    }

    @Test
    @DisplayName("a single string is read as a one-element list, which is how operators write it")
    void aBarePrefixStringIsAccepted() {
        FirewallRuleContext context = RuleContexts.proxied()
                .findings(RuleContexts.finding("PYSEC-2024-7", null, MatchConfidence.EXACT))
                .build();

        FirewallRuleSettings bare = FirewallRuleSettings.of(
                FirewallRuleType.KNOWN_MALICIOUS, FirewallAction.BLOCK,
                Map.of("idPrefixes", "PYSEC-"));

        assertThat(rule.evaluate(context, bare).matched()).isTrue();
    }

    @Test
    @DisplayName("an unusable idPrefixes falls back to MAL- instead of disabling the rule silently")
    void malformedPrefixesFallBack() {
        FirewallRuleContext context = RuleContexts.proxied()
                .findings(RuleContexts.finding("MAL-2024-1234", null, MatchConfidence.EXACT))
                .build();

        FirewallRuleSettings emptyList = FirewallRuleSettings.of(
                FirewallRuleType.KNOWN_MALICIOUS, FirewallAction.BLOCK,
                Map.of("idPrefixes", List.of()));
        FirewallRuleSettings blankEntries = FirewallRuleSettings.of(
                FirewallRuleType.KNOWN_MALICIOUS, FirewallAction.BLOCK,
                Map.of("idPrefixes", List.of("  ", "")));
        FirewallRuleSettings blankString = FirewallRuleSettings.of(
                FirewallRuleType.KNOWN_MALICIOUS, FirewallAction.BLOCK,
                Map.of("idPrefixes", "   "));

        assertThat(rule.evaluate(context, emptyList).matched())
                .as("a policy that configures nothing usable must still stop malicious packages")
                .isTrue();
        assertThat(rule.evaluate(context, blankEntries).matched()).isTrue();
        assertThat(rule.evaluate(context, blankString).matched()).isTrue();
        assertThat(KnownMaliciousRule.DEFAULT_ID_PREFIXES).containsExactly("MAL-");
    }

    // ------------------------------------------------------------ boundaries

    @Test
    @DisplayName("a pre-existing component still matches — declining to deny it is the engine's job")
    void preExistingStillMatches() {
        FirewallRuleContext context = RuleContexts.proxied()
                .preExisting()
                .findings(RuleContexts.finding("MAL-2024-1234", null, MatchConfidence.EXACT))
                .build();

        assertThat(rule.evaluate(context, malicious(FirewallAction.BLOCK)).matched()).isTrue();
    }

    private static FirewallRuleSettings malicious(FirewallAction action) {
        return FirewallRuleSettings.of(FirewallRuleType.KNOWN_MALICIOUS, action);
    }
}
