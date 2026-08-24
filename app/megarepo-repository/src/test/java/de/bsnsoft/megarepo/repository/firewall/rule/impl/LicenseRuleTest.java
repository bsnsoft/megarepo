package de.bsnsoft.megarepo.repository.firewall.rule.impl;

import de.bsnsoft.megarepo.core.firewall.FirewallAction;
import de.bsnsoft.megarepo.core.firewall.FirewallRuleType;
import de.bsnsoft.megarepo.repository.firewall.facts.ComponentFacts;
import de.bsnsoft.megarepo.repository.firewall.facts.ComponentFactsService;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRuleContext;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRuleOutcome;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRuleSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@code LICENSE}: allow and deny lists against what a package declares, the
 * three different ways of having no license, and the expressions the rule
 * refuses to guess at.
 */
class LicenseRuleTest {

    private ComponentFactsService facts;
    private LicenseRule rule;

    @BeforeEach
    void setUp() {
        facts = mock(ComponentFactsService.class);
        rule = new LicenseRule(RuleContexts.provider(facts));
    }

    // ------------------------------------------------------------------ SPI

    @Test
    @DisplayName("never quarantines — a license verdict does not change by waiting")
    void doesNotQuarantine() {
        assertThat(rule.ruleType()).isEqualTo(FirewallRuleType.LICENSE);
        assertThat(rule.quarantineOnMatch()).isFalse();
        assertThat(rule.appliesToUnidentifiedComponents()).isFalse();
    }

    // ------------------------------------------------------------- deny list

    @Test
    @DisplayName("a denied license matches and the reason quotes the declaration")
    void deniedLicenseMatches() {
        FirewallRuleContext context = RuleContexts.proxied().declares("GPL-3.0-only").build();

        FirewallRuleOutcome outcome = rule.evaluate(context, denied("GPL-3.0-only"));

        assertThat(outcome.matched()).isTrue();
        assertThat(outcome.violation().ruleType()).isEqualTo(FirewallRuleType.LICENSE);
        assertThat(outcome.violation().action()).isEqualTo(FirewallAction.BLOCK);
        assertThat(outcome.violation().advisoryIds()).isEmpty();
        assertThat(outcome.violation().reason())
                .isEqualTo("declares GPL-3.0-only, which the policy denies");
    }

    @Test
    @DisplayName("a license that is not denied passes")
    void otherLicensePasses() {
        FirewallRuleContext context = RuleContexts.proxied().declares("MIT").build();

        assertThat(rule.evaluate(context, denied("GPL-3.0-only")).kind())
                .isEqualTo(FirewallRuleOutcome.Kind.NOT_MATCHED);
    }

    @Test
    @DisplayName("comparison ignores case and collapsed whitespace, and nothing else")
    void comparisonIsCaseInsensitive() {
        assertThat(rule.evaluate(RuleContexts.proxied().declares("gpl-3.0-ONLY").build(),
                        denied("GPL-3.0-only"))
                .matched())
                .isTrue();
        assertThat(rule.evaluate(
                        RuleContexts.proxied().declares("Apache  License   2.0").build(),
                        denied("Apache License 2.0"))
                .matched())
                .isTrue();
        assertThat(rule.evaluate(RuleContexts.proxied().declares("Apache-2.0").build(),
                        denied("Apache License 2.0"))
                .matched())
                .as("no alias table: a compliance decision is not ours to guess")
                .isFalse();
    }

    // ------------------------------------------------------------ allow list

    @Test
    @DisplayName("an allow list refuses everything it does not name")
    void allowListRefusesTheRest() {
        FirewallRuleContext context = RuleContexts.proxied().declares("LGPL-2.1-only").build();

        FirewallRuleOutcome outcome = rule.evaluate(context, allowed("MIT", "Apache-2.0"));

        assertThat(outcome.matched()).isTrue();
        assertThat(outcome.violation().reason())
                .isEqualTo("declares LGPL-2.1-only, which is not on the policy's list of allowed licenses");
    }

    @Test
    @DisplayName("an allowed license passes")
    void allowedLicensePasses() {
        FirewallRuleContext context = RuleContexts.proxied().declares("mit").build();

        assertThat(rule.evaluate(context, allowed("MIT", "Apache-2.0")).matched()).isFalse();
    }

    @Test
    @DisplayName("deny wins over allow when a policy lists the same id twice")
    void denyWinsOverAllow() {
        FirewallRuleContext context = RuleContexts.proxied().declares("MIT").build();
        FirewallRuleSettings both = settings(Map.of(
                LicenseRule.CONFIG_ALLOWED, List.of("MIT"),
                LicenseRule.CONFIG_DENIED, List.of("MIT")));

        assertThat(rule.evaluate(context, both).matched()).isTrue();
    }

    @Test
    @DisplayName("every declared entry has to pass — a bare list is not a choice")
    void allDeclaredEntriesMustPass() {
        FirewallRuleContext context =
                RuleContexts.proxied().declares("MIT", "GPL-3.0-only").build();

        FirewallRuleOutcome outcome = rule.evaluate(context, denied("GPL-3.0-only"));

        assertThat(outcome.matched())
                .as("a permissive entry alongside a denied one must not clear the component")
                .isTrue();
        assertThat(outcome.violation().reason()).isEqualTo("declares GPL-3.0-only, which the policy denies");
    }

    // ------------------------------------------------------ SPDX expressions

    @Test
    @DisplayName("OR is a real choice: a denied arm does not condemn the package")
    void orIsAChoice() {
        FirewallRuleContext context =
                RuleContexts.proxied().declares("(MIT OR GPL-3.0-only)").build();

        assertThat(rule.evaluate(context, denied("GPL-3.0-only")).matched())
                .as("the consumer may take the MIT arm")
                .isFalse();
    }

    @Test
    @DisplayName("AND is a conjunction: a denied part condemns the whole")
    void andIsAConjunction() {
        FirewallRuleContext context =
                RuleContexts.proxied().declares("MIT AND GPL-3.0-only").build();

        FirewallRuleOutcome outcome = rule.evaluate(context, denied("GPL-3.0-only"));

        assertThat(outcome.matched()).isTrue();
        assertThat(outcome.violation().reason())
                .isEqualTo("declares MIT AND GPL-3.0-only, which the policy denies");
    }

    @Test
    @DisplayName("an allow list is satisfied by one acceptable arm of a choice")
    void allowListAcceptsOneArm() {
        FirewallRuleContext context =
                RuleContexts.proxied().declares("(MIT OR Apache-2.0)").build();

        assertThat(rule.evaluate(context, allowed("Apache-2.0")).matched()).isFalse();
        assertThat(rule.evaluate(context, allowed("BSD-3-Clause")).matched()).isTrue();
    }

    @Test
    @DisplayName("nested brackets and mixed operators keep SPDX precedence")
    void nestedExpressions() {
        FirewallRuleContext context = RuleContexts.proxied()
                .declares("(MIT OR Apache-2.0) AND GPL-3.0-only")
                .build();

        assertThat(rule.evaluate(context, denied("GPL-3.0-only")).matched()).isTrue();
        assertThat(rule.evaluate(context, denied("BSD-3-Clause")).matched()).isFalse();
    }

    @Test
    @DisplayName("a WITH exception is one identifier, not a license plus a word")
    void withExceptionIsOneIdentifier() {
        FirewallRuleContext context = RuleContexts.proxied()
                .declares("GPL-2.0-only WITH Classpath-exception-2.0")
                .build();

        assertThat(rule.evaluate(context, denied("GPL-2.0-only WITH Classpath-exception-2.0")).matched())
                .isTrue();
        assertThat(rule.evaluate(context, denied("GPL-2.0-only")).matched())
                .as("the exception is what makes that combination acceptable, so it is a different id")
                .isFalse();
    }

    @Test
    @DisplayName("a lower-case 'and' in a prose license name is not an operator")
    void proseIsNotAnExpression() {
        FirewallRuleContext context = RuleContexts.proxied()
                .declares("Eclipse Public License and Common Public License")
                .build();

        assertThat(rule.evaluate(
                        context, allowed("Eclipse Public License and Common Public License"))
                .matched())
                .isFalse();
        assertThat(rule.evaluate(context, allowed("MIT")).matched()).isTrue();
    }

    @Test
    @DisplayName("an expression the rule cannot read is indeterminate, never a match")
    void unreadableExpressionIsIndeterminate() {
        FirewallRuleContext context = RuleContexts.proxied().declares("(MIT OR").build();

        FirewallRuleOutcome outcome = rule.evaluate(context, denied("GPL-3.0-only"));

        assertThat(outcome.indeterminate())
                .as("a build must not be denied over a typo in somebody else's metadata")
                .isTrue();
        assertThat(outcome.reason())
                .isEqualTo("the declared license expression (MIT OR could not be read");
    }

    @Test
    @DisplayName("a dangling bracket is unreadable too")
    void unbalancedBracketsAreUnreadable() {
        assertThat(rule.evaluate(RuleContexts.proxied().declares("MIT)").build(), allowed("MIT"))
                        .indeterminate())
                .isTrue();
        assertThat(rule.evaluate(RuleContexts.proxied().declares("()").build(), allowed("MIT"))
                        .indeterminate())
                .isTrue();
    }

    @Test
    @DisplayName("a denied sibling still decides when another entry is unreadable")
    void denialWinsOverAnUnreadableSibling() {
        FirewallRuleContext context =
                RuleContexts.proxied().declares("GPL-3.0-only", "(MIT OR").build();

        assertThat(rule.evaluate(context, denied("GPL-3.0-only")).matched())
                .as("that verdict does not depend on the part we could not read")
                .isTrue();
    }

    // ------------------------------------------------ the three kinds of none

    @Test
    @DisplayName("facts not looked up yet: indeterminate, and a resolution is requested")
    void unresolvedFactsAreIndeterminate() {
        FirewallRuleContext context = RuleContexts.proxied().build();

        FirewallRuleOutcome outcome = rule.evaluate(context, denied("GPL-3.0-only"));

        assertThat(outcome.indeterminate()).isTrue();
        assertThat(outcome.reason())
                .isEqualTo("the declared licenses of pkg:maven/com.acme/util@1.0.0 have not been resolved yet");
        verify(facts).requestResolution(context.identity());
    }

    @Test
    @DisplayName("a resolution in flight is indeterminate too")
    void pendingFactsAreIndeterminate() {
        FirewallRuleContext context = RuleContexts.proxied()
                .facts(ComponentFacts.pending(RuleContexts.maven().key()))
                .build();

        assertThat(rule.evaluate(context, denied("GPL-3.0-only")).indeterminate()).isTrue();
    }

    @Test
    @DisplayName("a package that declares no license is allowed by default")
    void undeclaredIsAllowedByDefault() {
        FirewallRuleContext context = RuleContexts.proxied().declares().build();

        assertThat(rule.evaluate(context, denied("GPL-3.0-only")).kind())
                .isEqualTo(FirewallRuleOutcome.Kind.NOT_MATCHED);
    }

    @Test
    @DisplayName("a policy may insist on a declaration")
    void undeclaredCanBeRefused() {
        FirewallRuleContext context = RuleContexts.proxied().declares().build();
        FirewallRuleSettings strict = settings(Map.of(
                LicenseRule.CONFIG_DENIED, List.of("GPL-3.0-only"),
                LicenseRule.CONFIG_ALLOW_UNDECLARED, false));

        FirewallRuleOutcome outcome = rule.evaluate(context, strict);

        assertThat(outcome.matched()).isTrue();
        assertThat(outcome.violation().reason())
                .isEqualTo("declares no license, and the policy requires one");
    }

    @Test
    @DisplayName("metadata that could not be read is not a package declaring nothing")
    void unavailableIsNotUndeclared() {
        FirewallRuleContext context = RuleContexts.proxied()
                .facts(RuleContexts.unavailable(RuleContexts.maven().key()))
                .build();
        FirewallRuleSettings strict = settings(Map.of(
                LicenseRule.CONFIG_DENIED, List.of("GPL-3.0-only"),
                LicenseRule.CONFIG_ALLOW_UNDECLARED, false));

        FirewallRuleOutcome outcome = rule.evaluate(context, strict);

        assertThat(outcome.kind())
                .as("a firewall fault serves the artifact; it does not deny it")
                .isEqualTo(FirewallRuleOutcome.Kind.NOT_MATCHED);
    }

    // --------------------------------------------------------- configuration

    @Test
    @DisplayName("a rule with no lists is inert, including on the indeterminate path")
    void unconfiguredRuleIsInert() {
        FirewallRuleContext unresolved = RuleContexts.proxied().build();

        FirewallRuleOutcome outcome = rule.evaluate(unresolved, settings(Map.of()));

        assertThat(outcome.kind())
                .as("an empty LICENSE row must not quarantine a fail-closed repository")
                .isEqualTo(FirewallRuleOutcome.Kind.NOT_MATCHED);
        verify(facts, never()).requestResolution(any());
    }

    @Test
    @DisplayName("but a rule that only insists on a declaration still has something to enforce")
    void allowUndeclaredFalseAloneIsNotInert() {
        FirewallRuleContext unresolved = RuleContexts.proxied().build();
        FirewallRuleSettings strict =
                settings(Map.of(LicenseRule.CONFIG_ALLOW_UNDECLARED, false));

        assertThat(rule.evaluate(unresolved, strict).indeterminate()).isTrue();
    }

    @Test
    @DisplayName("a single string is read as a one-element list")
    void singleStringConfigIsAccepted() {
        FirewallRuleContext context = RuleContexts.proxied().declares("GPL-3.0-only").build();
        FirewallRuleSettings byHand =
                settings(Map.of(LicenseRule.CONFIG_DENIED, "GPL-3.0-only"));

        assertThat(rule.evaluate(context, byHand).matched()).isTrue();
    }

    @Test
    @DisplayName("an unreadable allowUndeclared falls back to permissive")
    void malformedFlagFallsBack() {
        FirewallRuleContext context = RuleContexts.proxied().declares().build();
        FirewallRuleSettings malformed = settings(Map.of(
                LicenseRule.CONFIG_DENIED, List.of("GPL-3.0-only"),
                LicenseRule.CONFIG_ALLOW_UNDECLARED, "maybe"));

        assertThat(rule.evaluate(context, malformed).matched())
                .as("a policy typo must not deny every package that declares nothing")
                .isFalse();
    }

    @Test
    @DisplayName("a component with no coordinates has no package metadata to judge")
    void unidentifiedComponentIsNotJudged() {
        FirewallRuleContext context = RuleContexts.proxied().identity(RuleContexts.hash()).build();

        assertThat(rule.evaluate(context, denied("GPL-3.0-only")).kind())
                .isEqualTo(FirewallRuleOutcome.Kind.NOT_MATCHED);
        verify(facts, never()).requestResolution(any());
    }

    @Test
    @DisplayName("an upload is judged like a download — publishing is an act too")
    void uploadsAreJudged() {
        FirewallRuleContext context =
                RuleContexts.hosted().upload().declares("GPL-3.0-only").build();

        assertThat(rule.evaluate(context, denied("GPL-3.0-only")).matched()).isTrue();
    }

    @Test
    @DisplayName("without a facts store the rule still answers instead of failing")
    void withoutFactsServiceStillAnswers() {
        LicenseRule standalone = new LicenseRule(RuleContexts.provider(null));

        assertThat(standalone.evaluate(RuleContexts.proxied().build(), denied("GPL-3.0-only"))
                        .indeterminate())
                .isTrue();
    }

    private static FirewallRuleSettings denied(String... licenses) {
        return settings(Map.of(LicenseRule.CONFIG_DENIED, List.of(licenses)));
    }

    private static FirewallRuleSettings allowed(String... licenses) {
        return settings(Map.of(LicenseRule.CONFIG_ALLOWED, List.of(licenses)));
    }

    private static FirewallRuleSettings settings(Map<String, Object> config) {
        return FirewallRuleSettings.of(FirewallRuleType.LICENSE, FirewallAction.BLOCK, config);
    }
}
