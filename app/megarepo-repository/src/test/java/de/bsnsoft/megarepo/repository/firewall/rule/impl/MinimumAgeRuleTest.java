package de.bsnsoft.megarepo.repository.firewall.rule.impl;

import de.bsnsoft.megarepo.core.firewall.FirewallAction;
import de.bsnsoft.megarepo.core.firewall.FirewallQuarantineReason;
import de.bsnsoft.megarepo.core.firewall.FirewallRuleType;
import de.bsnsoft.megarepo.repository.firewall.facts.ComponentFacts;
import de.bsnsoft.megarepo.repository.firewall.facts.ComponentFactsService;
import de.bsnsoft.megarepo.repository.firewall.identity.ComponentIdentity;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRuleContext;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRuleOutcome;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRuleSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@code MIN_AGE} on its own: when a component is too young, when it is old
 * enough, and — the part the design exists for — when the rule refuses to guess.
 */
class MinimumAgeRuleTest {

    private ComponentFactsService facts;
    private MinimumAgeRule rule;

    @BeforeEach
    void setUp() {
        facts = mock(ComponentFactsService.class);
        rule = new MinimumAgeRule(RuleContexts.provider(facts));
    }

    // ------------------------------------------------------------------ SPI

    @Test
    @DisplayName("holds rather than refuses, under MIN_AGE_NOT_MET")
    void quarantinesUnderItsOwnReason() {
        assertThat(rule.ruleType()).isEqualTo(FirewallRuleType.MIN_AGE);
        assertThat(rule.quarantineOnMatch())
                .as("a component gets older on its own, which is what quarantine is for")
                .isTrue();
        assertThat(rule.quarantineReason()).isEqualTo(FirewallQuarantineReason.MIN_AGE_NOT_MET);
        assertThat(rule.appliesToUnidentifiedComponents())
                .as("a raw blob has no publication date")
                .isFalse();
    }

    // -------------------------------------------------------------- verdicts

    @Test
    @DisplayName("a package published two hours ago fails a seven-day policy")
    void tooYoungMatches() {
        FirewallRuleContext context = RuleContexts.proxied()
                .publishedAt(RuleContexts.NOW.minus(Duration.ofHours(2)))
                .build();

        FirewallRuleOutcome outcome = rule.evaluate(context, minAge("P7D", FirewallAction.BLOCK));

        assertThat(outcome.matched()).isTrue();
        assertThat(outcome.violation().ruleType()).isEqualTo(FirewallRuleType.MIN_AGE);
        assertThat(outcome.violation().action()).isEqualTo(FirewallAction.BLOCK);
        assertThat(outcome.violation().advisoryIds())
                .as("nothing about this verdict comes from an advisory")
                .isEmpty();
        assertThat(outcome.violation().reason())
                .as("a build log has to say how young it is, how old it must be, and when it clears")
                .isEqualTo("published 2 hours ago, less than the minimum age of 7 days required by "
                        + "the policy; acceptable from 2026-08-31T10:00:00Z");
    }

    @Test
    @DisplayName("a package published eight days ago passes a seven-day policy")
    void oldEnoughDoesNotMatch() {
        FirewallRuleContext context = RuleContexts.proxied()
                .publishedAt(RuleContexts.NOW.minus(Duration.ofDays(8)))
                .build();

        assertThat(rule.evaluate(context, minAge("P7D", FirewallAction.BLOCK)).kind())
                .isEqualTo(FirewallRuleOutcome.Kind.NOT_MATCHED);
    }

    @Test
    @DisplayName("exactly at the threshold the component is old enough")
    void thresholdIsInclusive() {
        FirewallRuleContext context = RuleContexts.proxied()
                .publishedAt(RuleContexts.NOW.minus(Duration.ofDays(7)))
                .build();

        assertThat(rule.evaluate(context, minAge("P7D", FirewallAction.BLOCK)).matched())
                .as("released at the second it becomes eligible, not one sweep later")
                .isFalse();
    }

    @Test
    @DisplayName("one second short of the threshold still matches")
    void oneSecondShortMatches() {
        FirewallRuleContext context = RuleContexts.proxied()
                .publishedAt(RuleContexts.NOW.minus(Duration.ofDays(7)).plusSeconds(1))
                .build();

        assertThat(rule.evaluate(context, minAge("P7D", FirewallAction.BLOCK)).matched()).isTrue();
    }

    @Test
    @DisplayName("a publication date in the future cannot satisfy the rule")
    void futureDateMatches() {
        FirewallRuleContext context = RuleContexts.proxied()
                .publishedAt(RuleContexts.NOW.plus(Duration.ofDays(3)))
                .build();

        FirewallRuleOutcome outcome = rule.evaluate(context, minAge("P7D", FirewallAction.BLOCK));

        assertThat(outcome.matched()).isTrue();
        assertThat(outcome.violation().reason())
                .isEqualTo("declares a publication date in the future (2026-08-27T12:00:00Z), so it "
                        + "cannot be shown to meet the minimum age of 7 days required by the policy");
    }

    // --------------------------------------------------------- indeterminate

    @Test
    @DisplayName("facts not looked up yet: indeterminate, and a resolution is requested")
    void unknownFactsAreIndeterminate() {
        FirewallRuleContext context = RuleContexts.proxied().build();

        FirewallRuleOutcome outcome = rule.evaluate(context, minAge("P7D", FirewallAction.BLOCK));

        assertThat(outcome.indeterminate())
                .as("neither answer is honest while the date is unknown")
                .isTrue();
        assertThat(outcome.reason())
                .isEqualTo("the publication date of pkg:maven/com.acme/util@1.0.0 has not been resolved yet");
        verify(facts).requestResolution(context.identity());
    }

    @Test
    @DisplayName("a resolution in flight is indeterminate too")
    void pendingFactsAreIndeterminate() {
        FirewallRuleContext context = RuleContexts.proxied()
                .facts(ComponentFacts.pending(RuleContexts.maven().key()))
                .build();

        assertThat(rule.evaluate(context, minAge("P7D", FirewallAction.BLOCK)).indeterminate()).isTrue();
    }

    @Test
    @DisplayName("resolved metadata that states no date is a settled answer, not a hold")
    void resolvedWithoutDateDoesNotMatch() {
        FirewallRuleContext context = RuleContexts.proxied()
                .facts(RuleContexts.resolved(RuleContexts.maven().key(), null))
                .build();

        FirewallRuleOutcome outcome = rule.evaluate(context, minAge("P7D", FirewallAction.BLOCK));

        assertThat(outcome.indeterminate())
                .as("a date that is never coming must not hold the component forever")
                .isFalse();
        assertThat(outcome.matched()).isFalse();
    }

    @Test
    @DisplayName("an ecosystem that publishes no dates is a settled answer as well")
    void unavailableFactsDoNotMatch() {
        FirewallRuleContext context = RuleContexts.proxied()
                .facts(RuleContexts.unavailable(RuleContexts.maven().key()))
                .build();

        FirewallRuleOutcome outcome = rule.evaluate(context, minAge("P7D", FirewallAction.BLOCK));

        assertThat(outcome.kind()).isEqualTo(FirewallRuleOutcome.Kind.NOT_MATCHED);
    }

    @Test
    @DisplayName("without a facts store the rule still answers instead of failing")
    void withoutFactsServiceStillAnswers() {
        MinimumAgeRule standalone = new MinimumAgeRule(RuleContexts.provider(null));

        FirewallRuleOutcome outcome =
                standalone.evaluate(RuleContexts.proxied().build(), minAge("P7D", FirewallAction.BLOCK));

        assertThat(outcome.indeterminate()).isTrue();
    }

    @Test
    @DisplayName("a facts store that throws does not take the download with it")
    void factsServiceFailureIsContained() {
        doThrow(new IllegalStateException("down")).when(facts).requestResolution(any());

        FirewallRuleOutcome outcome =
                rule.evaluate(RuleContexts.proxied().build(), minAge("P7D", FirewallAction.BLOCK));

        assertThat(outcome.indeterminate()).isTrue();
    }

    // --------------------------------------------------------- configuration

    @Test
    @DisplayName("a bare number of days means days")
    void bareNumberIsDays() {
        FirewallRuleContext context = RuleContexts.proxied()
                .publishedAt(RuleContexts.NOW.minus(Duration.ofDays(3)))
                .build();

        assertThat(rule.evaluate(context, minAge(7, FirewallAction.BLOCK)).matched()).isTrue();
        assertThat(rule.evaluate(context, minAge(2, FirewallAction.BLOCK)).matched()).isFalse();
    }

    @Test
    @DisplayName("hours are expressible")
    void isoHoursAreHonoured() {
        FirewallRuleContext context = RuleContexts.proxied()
                .publishedAt(RuleContexts.NOW.minus(Duration.ofHours(30)))
                .build();

        assertThat(rule.evaluate(context, minAge("PT36H", FirewallAction.BLOCK)).matched()).isTrue();
        assertThat(rule.evaluate(context, minAge("PT24H", FirewallAction.BLOCK)).matched()).isFalse();
    }

    @Test
    @DisplayName("an unreadable minAge falls back to the default and does not match")
    void malformedConfigFallsBackToTheDefault() {
        FirewallRuleContext olderThanTheDefault = RuleContexts.proxied()
                .publishedAt(RuleContexts.NOW.minus(MinimumAgeRule.DEFAULT_MIN_AGE).minusSeconds(1))
                .build();

        assertThat(rule.evaluate(olderThanTheDefault, minAge("soon", FirewallAction.BLOCK)).matched())
                .as("a policy typo must not deny a component the default would have served")
                .isFalse();

        FirewallRuleContext younger = RuleContexts.proxied()
                .publishedAt(RuleContexts.NOW.minus(Duration.ofHours(1)))
                .build();
        assertThat(rule.evaluate(younger, minAge("soon", FirewallAction.BLOCK)).matched())
                .as("the fallback is the default age, not 'off'")
                .isTrue();
    }

    @Test
    @DisplayName("a minimum age of zero is inert, including on the indeterminate path")
    void zeroMinimumAgeIsInert() {
        FirewallRuleContext unresolved = RuleContexts.proxied().build();

        FirewallRuleOutcome outcome = rule.evaluate(unresolved, minAge(0, FirewallAction.BLOCK));

        assertThat(outcome.kind())
                .as("a rule that cannot match must not quarantine a fail-closed repository")
                .isEqualTo(FirewallRuleOutcome.Kind.NOT_MATCHED);
        verify(facts, never()).requestResolution(any());
    }

    @Test
    @DisplayName("a negative minimum age is inert as well")
    void negativeMinimumAgeIsInert() {
        FirewallRuleContext context = RuleContexts.proxied()
                .publishedAt(RuleContexts.NOW.minusSeconds(5))
                .build();

        assertThat(rule.evaluate(context, minAge("-P1D", FirewallAction.BLOCK)).matched()).isFalse();
    }

    // ------------------------------------------------------------ boundaries

    @Test
    @DisplayName("an upload is not judged on its age — it is being published now")
    void uploadIsNotJudged() {
        FirewallRuleContext context = RuleContexts.hosted()
                .upload()
                .publishedAt(RuleContexts.NOW.minusSeconds(5))
                .build();

        assertThat(rule.evaluate(context, minAge("P7D", FirewallAction.BLOCK)).matched())
                .as("otherwise every release is refused on the day it is made")
                .isFalse();
    }

    @Test
    @DisplayName("a component with no coordinates is not held for a date it cannot have")
    void unidentifiedComponentIsNotHeld() {
        FirewallRuleContext hashed =
                RuleContexts.proxied().identity(RuleContexts.hash()).build();

        assertThat(rule.evaluate(hashed, minAge("P7D", FirewallAction.BLOCK)).kind())
                .isEqualTo(FirewallRuleOutcome.Kind.NOT_MATCHED);
        verify(facts, never()).requestResolution(any());
    }

    @Test
    @DisplayName("a WARN rule reports WARN — the rule decides, the engine enforces")
    void actionIsThePolicysNotTheRules() {
        FirewallRuleContext context = RuleContexts.proxied()
                .publishedAt(RuleContexts.NOW.minus(Duration.ofHours(1)))
                .build();

        FirewallRuleOutcome outcome = rule.evaluate(context, minAge("P7D", FirewallAction.WARN));

        assertThat(outcome.violation().action()).isEqualTo(FirewallAction.WARN);
    }

    @Test
    @DisplayName("a pre-existing component still matches — declining to deny it is the engine's job")
    void preExistingStillMatches() {
        FirewallRuleContext context = RuleContexts.proxied()
                .preExisting()
                .publishedAt(RuleContexts.NOW.minus(Duration.ofHours(1)))
                .build();

        assertThat(rule.evaluate(context, minAge("P7D", FirewallAction.BLOCK)).matched()).isTrue();
    }

    @Test
    @DisplayName("durations read like a sentence, not like an ISO string")
    void durationsAreHumanReadable() {
        assertThat(MinimumAgeRule.humanize(Duration.ofDays(7))).isEqualTo("7 days");
        assertThat(MinimumAgeRule.humanize(Duration.ofHours(36))).isEqualTo("1 day 12 hours");
        assertThat(MinimumAgeRule.humanize(Duration.ofHours(1))).isEqualTo("1 hour");
        assertThat(MinimumAgeRule.humanize(Duration.ofMinutes(95))).isEqualTo("1 hour 35 minutes");
        assertThat(MinimumAgeRule.humanize(Duration.ofMinutes(4))).isEqualTo("4 minutes");
        assertThat(MinimumAgeRule.humanize(Duration.ofSeconds(9))).isEqualTo("9 seconds");
        assertThat(MinimumAgeRule.humanize(Duration.ZERO)).isEqualTo("0 seconds");
    }

    private static FirewallRuleSettings minAge(Object value, FirewallAction action) {
        return FirewallRuleSettings.of(
                FirewallRuleType.MIN_AGE, action, Map.of(MinimumAgeRule.CONFIG_MIN_AGE, value));
    }

    /** Guards the fixture: the identity the reason strings quote. */
    @Test
    @DisplayName("the fixture component is the purl the assertions name")
    void fixtureIdentity() {
        assertThat(RuleContexts.maven().key()).isEqualTo("pkg:maven/com.acme/util@1.0.0");
        assertThat((ComponentIdentity) RuleContexts.maven()).isInstanceOf(ComponentIdentity.Purl.class);
    }
}
