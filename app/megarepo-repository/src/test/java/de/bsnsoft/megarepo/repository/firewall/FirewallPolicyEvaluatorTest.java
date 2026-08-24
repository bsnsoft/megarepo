package de.bsnsoft.megarepo.repository.firewall;

import com.github.packageurl.MalformedPackageURLException;
import com.github.packageurl.PackageURL;
import de.bsnsoft.megarepo.core.firewall.FirewallAction;
import de.bsnsoft.megarepo.core.firewall.FirewallComponentKeyKind;
import de.bsnsoft.megarepo.core.firewall.FirewallExemptionScope;
import de.bsnsoft.megarepo.core.firewall.FirewallExemptionState;
import de.bsnsoft.megarepo.core.firewall.FirewallFailMode;
import de.bsnsoft.megarepo.core.firewall.FirewallMode;
import de.bsnsoft.megarepo.core.firewall.FirewallQuarantineReason;
import de.bsnsoft.megarepo.core.firewall.FirewallQuarantineState;
import de.bsnsoft.megarepo.core.firewall.FirewallRuleType;
import de.bsnsoft.megarepo.core.repository.RepositoryType;
import de.bsnsoft.megarepo.database.entity.FirewallPolicyEntity;
import de.bsnsoft.megarepo.database.entity.FirewallPolicyRuleEntity;
import de.bsnsoft.megarepo.database.repository.FirewallPolicyJpaRepository;
import de.bsnsoft.megarepo.database.repository.FirewallPolicyRuleJpaRepository;
import de.bsnsoft.megarepo.repository.advisory.AdvisoryFinding;
import de.bsnsoft.megarepo.repository.advisory.AdvisoryMatch;
import de.bsnsoft.megarepo.repository.advisory.MatchConfidence;
import de.bsnsoft.megarepo.repository.firewall.exemption.ExemptionQuery;
import de.bsnsoft.megarepo.repository.firewall.exemption.ExemptionRequest;
import de.bsnsoft.megarepo.repository.firewall.exemption.ExemptionService;
import de.bsnsoft.megarepo.repository.firewall.exemption.FirewallExemption;
import de.bsnsoft.megarepo.repository.firewall.facts.ComponentFacts;
import de.bsnsoft.megarepo.repository.firewall.identity.ComponentIdentity;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRule;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRuleContext;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRuleOutcome;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRuleRegistry;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRuleSettings;
import de.bsnsoft.megarepo.repository.firewall.rule.impl.CvssThresholdRule;
import de.bsnsoft.megarepo.repository.firewall.rule.impl.KnownMaliciousRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The policy engine on its own: which rules match, what they say, and — the
 * part that matters most — what they refuse to do.
 *
 * <p>Phase 2 turned the engine's {@code switch} into a lookup, so this class now
 * drives a <em>real</em> {@link FirewallRuleRegistry} built from the rule beans
 * under test. That is deliberate: with the rules mocked out, every assertion here
 * would be about the engine talking to itself, and the thing most likely to break
 * — a policy row whose rule nobody implemented, or a rule bean that throws — would
 * be unobservable.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FirewallPolicyEvaluatorTest {

    private static final UUID POLICY_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID REPOSITORY_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID EXEMPTION_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final Instant NOW = Instant.parse("2026-08-24T12:00:00Z");

    @Mock private FirewallPolicyJpaRepository policies;
    @Mock private FirewallPolicyRuleJpaRepository rules;

    private final StubExemptionService exemptions = new StubExemptionService();

    @BeforeEach
    void setUp() {
        FirewallPolicyEntity policy = new FirewallPolicyEntity();
        policy.setId(POLICY_ID);
        policy.setName("Default");
        policy.setDefault(true);
        when(policies.findByIsDefaultTrue()).thenReturn(Optional.of(policy));
    }

    // ------------------------------------------------------------- verdicts

    @Test
    @DisplayName("a critical advisory trips the CVSS rule and the decision blocks")
    void cvssThresholdBlocks() {
        givenRules(cvssRule(FirewallAction.BLOCK, 9.0));

        FirewallDecision decision = evaluator().evaluate(
                context(finding("GHSA-jfh8-c2jp-5v3q", 10.0, MatchConfidence.EXACT)));

        assertThat(decision.blocked()).isTrue();
        assertThat(decision.reason()).isEqualTo(FirewallDecision.Reason.POLICY);
        assertThat(decision.policyName()).isEqualTo("Default");
        assertThat(decision.violations()).hasSize(1);
        assertThat(decision.violations().get(0).ruleType()).isEqualTo(FirewallRuleType.CVSS_THRESHOLD);
        assertThat(decision.violations().get(0).reason())
                .as("a build log has to be able to say what the numbers were")
                .isEqualTo("CVSS 10 is at or above the configured threshold of 9");
        assertThat(decision.advisoryIds()).containsExactly("GHSA-jfh8-c2jp-5v3q");
        assertThat(decision.hold())
                .as("a critical advisory is refused outright, never queued (design 5.1)")
                .isNull();
    }

    @Test
    @DisplayName("a finding below the threshold matches nothing")
    void belowThresholdIsClean() {
        givenRules(cvssRule(FirewallAction.BLOCK, 9.0));

        FirewallDecision decision = evaluator().evaluate(
                context(finding("CVE-2020-1", 7.5, MatchConfidence.EXACT)));

        assertThat(decision.blocked()).isFalse();
        assertThat(decision.violations()).isEmpty();
        assertThat(decision.reason()).isEqualTo(FirewallDecision.Reason.ALLOWED);
    }

    @Test
    @DisplayName("WARN never blocks — it matches, it is recorded, and the download goes out")
    void warnRecordsWithoutBlocking() {
        givenRules(cvssRule(FirewallAction.WARN, 9.0));

        FirewallDecision decision = evaluator().evaluate(
                context(finding("GHSA-jfh8-c2jp-5v3q", 10.0, MatchConfidence.EXACT)));

        assertThat(decision.blocked()).isFalse();
        assertThat(decision.violations()).hasSize(1);
        assertThat(decision.violations().get(0).action()).isEqualTo(FirewallAction.WARN);
        assertThat(decision.blockingViolations()).isEmpty();
        assertThat(exemptions.lookups)
                .as("a WARN rule withholds nothing, so spending an exemption lookup on it is waste")
                .isZero();
    }

    @Test
    @DisplayName("an OSV MAL- entry blocks on its own, with no CVSS score involved")
    void knownMaliciousBlocksWithoutAScore() {
        givenRules(maliciousRule(FirewallAction.BLOCK));

        FirewallDecision decision = evaluator().evaluate(
                context(finding("MAL-2024-1234", null, MatchConfidence.EXACT)));

        assertThat(decision.blocked()).isTrue();
        assertThat(decision.violations().get(0).ruleType()).isEqualTo(FirewallRuleType.KNOWN_MALICIOUS);
        assertThat(decision.violations().get(0).reason())
                .isEqualTo("advisory MAL-2024-1234 flags this component as malicious");
    }

    @Test
    @DisplayName("a CPE-derived match does not block: a product-name collision must not break a build")
    void heuristicMatchesDoNotBlockByDefault() {
        givenRules(cvssRule(FirewallAction.BLOCK, 9.0));

        FirewallDecision decision = evaluator().evaluate(
                context(finding("CVE-2021-44228", 10.0, MatchConfidence.HEURISTIC)));

        assertThat(decision.blocked())
                .as("NVD matched on the product name alone; that is not grounds for a silent block")
                .isFalse();
        assertThat(decision.violations()).isEmpty();
    }

    @Test
    @DisplayName("a WARN rule does record the CPE-derived match it would not block on")
    void heuristicMatchesStillWarn() {
        givenRules(cvssRule(FirewallAction.WARN, 9.0));

        FirewallDecision decision = evaluator().evaluate(
                context(finding("CVE-2021-44228", 10.0, MatchConfidence.HEURISTIC)));

        assertThat(decision.violations()).hasSize(1);
        assertThat(decision.blocked()).isFalse();
    }

    @Test
    @DisplayName("minConfidence can be lowered per rule when an operator wants CPE matches to count")
    void minConfidenceIsConfigurable() {
        FirewallPolicyRuleEntity rule = cvssRule(FirewallAction.BLOCK, 9.0);
        rule.setConfig(Map.of("minScore", 9.0, "minConfidence", "HEURISTIC"));
        givenRules(rule);

        FirewallDecision decision = evaluator().evaluate(
                context(finding("CVE-2021-44228", 10.0, MatchConfidence.HEURISTIC)));

        assertThat(decision.blocked()).isTrue();
    }

    @Test
    @DisplayName("two rules that both match produce two violations, each with its own reason")
    void bothRulesCanMatch() {
        givenRules(cvssRule(FirewallAction.BLOCK, 9.0), maliciousRule(FirewallAction.BLOCK));

        FirewallDecision decision = evaluator().evaluate(context(
                finding("GHSA-jfh8-c2jp-5v3q", 10.0, MatchConfidence.EXACT),
                finding("MAL-2024-1234", null, MatchConfidence.EXACT)));

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

        FirewallDecision decision = evaluator().evaluate(context());

        assertThat(decision.blocked()).isFalse();
        assertThat(decision.violations()).isEmpty();
    }

    // ---------------------------------------------------------- the registry

    @Test
    @DisplayName("a policy row whose rule type has no bean matches nothing, ever")
    void unimplementedRuleTypesAreSkipped() {
        FirewallPolicyRuleEntity license = new FirewallPolicyRuleEntity();
        license.setPolicyId(POLICY_ID);
        license.setRuleType(FirewallRuleType.LICENSE);
        license.setAction(FirewallAction.BLOCK);
        license.setConfig(Map.of("deny", List.of("GPL-3.0")));
        givenRules(license);

        FirewallDecision decision = evaluator().evaluate(
                context(finding("GHSA-jfh8-c2jp-5v3q", 10.0, MatchConfidence.EXACT)));

        assertThat(decision.blocked())
                .as("a rule nobody implemented must not deny a download, however it is configured")
                .isFalse();
        assertThat(decision.violations()).isEmpty();
    }

    @Test
    @DisplayName("a rule bean that throws is undecidable, not a match and not a clean pass")
    void aThrowingRuleIsContained() {
        givenRules(rule(FirewallRuleType.LICENSE, FirewallAction.BLOCK));

        FirewallDecision failOpen = evaluator(new ThrowingRule(FirewallRuleType.LICENSE))
                .evaluate(context(FirewallFailMode.FAIL_OPEN, false));

        assertThat(failOpen.blocked())
                .as("a defect in one rule must not deny downloads in a fail-open repository")
                .isFalse();
        assertThat(failOpen.violations())
                .as("the broken rule is still on the record — served, but a rule that has "
                        + "thrown on every download for a week should be findable")
                .hasSize(1);
        assertThat(failOpen.violations().get(0).matched())
                .as("and never as a match: nothing was found against this component")
                .isFalse();
        assertThat(failOpen.blockingViolations())
                .as("a fail-open repository served it, so nothing here withheld anything")
                .allMatch(FirewallRuleViolation::undecided);

        FirewallDecision failClosed = evaluator(new ThrowingRule(FirewallRuleType.LICENSE))
                .evaluate(context(FirewallFailMode.FAIL_CLOSED, false));

        assertThat(failClosed.reason())
                .as("fail-closed holds what it cannot decide rather than serving it")
                .isEqualTo(FirewallDecision.Reason.QUARANTINED);
        assertThat(failClosed.hold().reason())
                .isEqualTo(FirewallQuarantineReason.EVALUATION_INCOMPLETE);
    }

    @Test
    @DisplayName("a disabled rule is not even loaded")
    void onlyEnabledRulesAreLoaded() {
        when(rules.findByPolicyIdAndEnabledTrue(POLICY_ID)).thenReturn(List.of());

        FirewallDecision decision = evaluator().evaluate(
                context(finding("GHSA-jfh8-c2jp-5v3q", 10.0, MatchConfidence.EXACT)));

        assertThat(decision.blocked()).isFalse();
    }

    // ----------------------------------------------------------- the policy

    @Test
    @DisplayName("with no policy at all the built-in rules apply rather than silently allowing everything")
    void missingPolicyFallsBackToBuiltInRules() {
        when(policies.findByIsDefaultTrue()).thenReturn(Optional.empty());

        FirewallDecision decision = evaluator().evaluate(
                context(finding("MAL-2024-1234", null, MatchConfidence.EXACT)));

        assertThat(decision.blocked()).isTrue();
        assertThat(decision.policyName()).isEqualTo("built-in default");
        assertThat(decision.policyId()).isNull();
    }

    @Test
    @DisplayName("a policy id that no longer exists falls back to the default, not to 'stop enforcing'")
    void danglingPolicyIdFallsBackToTheDefault() {
        UUID deleted = UUID.fromString("55555555-5555-5555-5555-555555555555");
        when(policies.findById(deleted)).thenReturn(Optional.empty());
        givenRules(cvssRule(FirewallAction.BLOCK, 9.0));

        FirewallRuleContext context = context(
                new FirewallRepositorySettings(
                        FirewallMode.QUARANTINE, FirewallFailMode.FAIL_OPEN, deleted, true),
                false,
                List.of(finding("GHSA-jfh8-c2jp-5v3q", 10.0, MatchConfidence.EXACT)));

        FirewallDecision decision = evaluator().evaluate(context);

        assertThat(decision.blocked())
                .as("a dangling policy_id is a data problem, not an instruction to serve everything")
                .isTrue();
        assertThat(decision.policyId()).isEqualTo(POLICY_ID);
        assertThat(decision.policyName()).isEqualTo("Default");
    }

    @Test
    @DisplayName("a non-numeric threshold falls back to the default instead of denying everything")
    void malformedConfigFallsBack() {
        FirewallPolicyRuleEntity rule = cvssRule(FirewallAction.BLOCK, 9.0);
        rule.setConfig(Map.of("minScore", "not a number"));
        givenRules(rule);

        FirewallDecision belowDefault = evaluator().evaluate(
                context(finding("CVE-2020-1", 7.5, MatchConfidence.EXACT)));
        FirewallDecision aboveDefault = evaluator().evaluate(
                context(finding("CVE-2021-44228", 9.8, MatchConfidence.EXACT)));

        assertThat(belowDefault.blocked()).isFalse();
        assertThat(aboveDefault.blocked()).isTrue();
    }

    // -------------------------------------------------------------- exemptions

    @Test
    @DisplayName("an approved exemption turns a BLOCK into a served download, and says which one did it")
    void anExemptionSuppressesTheBlock() {
        givenRules(maliciousRule(FirewallAction.BLOCK));
        exemptions.covering(FirewallRuleType.KNOWN_MALICIOUS);

        FirewallDecision decision = evaluator().evaluate(
                context(finding("MAL-2024-1234", null, MatchConfidence.EXACT)));

        assertThat(decision.blocked()).isFalse();
        assertThat(decision.reason())
                .as("distinct from ALLOWED so the audit trail can answer 'what got through, and whose?'")
                .isEqualTo(FirewallDecision.Reason.EXEMPTED);
        assertThat(decision.violations())
                .as("an exemption accepts a finding; it is not a reason to stop noticing it")
                .hasSize(1);
        assertThat(decision.violations().get(0).exemptionId()).isEqualTo(EXEMPTION_ID);
        assertThat(decision.violations().get(0).action())
                .as("the violation still records what the rule asked for")
                .isEqualTo(FirewallAction.BLOCK);
        assertThat(decision.blockingViolations())
                .as("a 403 body listing it would name a rule the reader has an exception from")
                .isEmpty();
        assertThat(decision.exemptedViolations()).hasSize(1);
        assertThat(decision.exemptionIds()).containsExactly(EXEMPTION_ID);
    }

    @Test
    @DisplayName("an exemption for a different rule suppresses nothing")
    void anExemptionIsScopedToItsRule() {
        givenRules(cvssRule(FirewallAction.BLOCK, 9.0));
        exemptions.covering(FirewallRuleType.KNOWN_MALICIOUS);

        FirewallDecision decision = evaluator().evaluate(
                context(finding("GHSA-jfh8-c2jp-5v3q", 10.0, MatchConfidence.EXACT)));

        assertThat(decision.blocked())
                .as("'exempt from MIN_AGE but not from KNOWN_MALICIOUS' is the point of scoping them")
                .isTrue();
        assertThat(decision.violations().get(0).exemptionId()).isNull();
        assertThat(decision.reason()).isEqualTo(FirewallDecision.Reason.POLICY);
    }

    @Test
    @DisplayName("only one of two blocking rules exempted still denies the download")
    void oneExemptionDoesNotCoverTheOtherRule() {
        givenRules(cvssRule(FirewallAction.BLOCK, 9.0), maliciousRule(FirewallAction.BLOCK));
        exemptions.covering(FirewallRuleType.CVSS_THRESHOLD);

        FirewallDecision decision = evaluator().evaluate(context(
                finding("GHSA-jfh8-c2jp-5v3q", 10.0, MatchConfidence.EXACT),
                finding("MAL-2024-1234", null, MatchConfidence.EXACT)));

        assertThat(decision.blocked()).isTrue();
        assertThat(decision.blockingViolations())
                .extracting(FirewallRuleViolation::ruleType)
                .containsExactly(FirewallRuleType.KNOWN_MALICIOUS);
        assertThat(decision.exemptedViolations())
                .extracting(FirewallRuleViolation::ruleType)
                .containsExactly(FirewallRuleType.CVSS_THRESHOLD);
    }

    @Test
    @DisplayName("an exemption store that cannot be read means 'no exemption', never 'checked and free'")
    void anUnreadableExemptionStoreDenies() {
        givenRules(maliciousRule(FirewallAction.BLOCK));
        exemptions.failing();

        FirewallDecision decision = evaluator().evaluate(
                context(finding("MAL-2024-1234", null, MatchConfidence.EXACT)));

        assertThat(decision.blocked())
                .as("denying a download somebody had permission for is recoverable; the reverse is not")
                .isTrue();
        assertThat(decision.violations().get(0).exemptionId())
                .as("a violation row must never claim an exemption that was never read")
                .isNull();
        assertThat(decision.reason()).isEqualTo(FirewallDecision.Reason.POLICY);
    }

    // ----------------------------------------------------------- hold ordering

    @Test
    @DisplayName("a component that trips a quarantining and a refusing rule is refused, with no queue entry")
    void anOutrightRefusalBeatsAHold() {
        givenRules(
                rule(FirewallRuleType.MIN_AGE, FirewallAction.BLOCK),
                maliciousRule(FirewallAction.BLOCK));

        FirewallDecision decision = evaluator(holdingRule(FirewallRuleType.MIN_AGE)).evaluate(
                context(finding("MAL-2024-1234", null, MatchConfidence.EXACT)));

        assertThat(decision.reason())
                .as("a release button next to a malicious package is what design 5.1 rules out")
                .isEqualTo(FirewallDecision.Reason.POLICY);
        assertThat(decision.held()).isFalse();
        assertThat(decision.hold()).isNull();
        assertThat(decision.blockingViolations()).hasSize(2);
    }

    @Test
    @DisplayName("a quarantining rule on its own holds the component under that rule's reason")
    void aQuarantiningRuleHolds() {
        givenRules(rule(FirewallRuleType.MIN_AGE, FirewallAction.BLOCK));

        FirewallDecision decision =
                evaluator(holdingRule(FirewallRuleType.MIN_AGE)).evaluate(context());

        assertThat(decision.blocked()).isTrue();
        assertThat(decision.reason()).isEqualTo(FirewallDecision.Reason.QUARANTINED);
        assertThat(decision.held()).isTrue();
        assertThat(decision.hold().reason()).isEqualTo(FirewallQuarantineReason.MIN_AGE_NOT_MET);
        assertThat(decision.hold().state()).isEqualTo(FirewallQuarantineState.QUARANTINED);
        assertThat(decision.hold().quarantineId())
                .as("the engine decides to hold; the entry is written by the enforcement path")
                .isNull();
    }

    @Test
    @DisplayName("a quarantining rule that only warns holds nothing")
    void aWarningQuarantiningRuleDoesNotHold() {
        givenRules(rule(FirewallRuleType.MIN_AGE, FirewallAction.WARN));

        FirewallDecision decision =
                evaluator(holdingRule(FirewallRuleType.MIN_AGE)).evaluate(context());

        assertThat(decision.blocked()).isFalse();
        assertThat(decision.hold()).isNull();
        assertThat(decision.violations()).hasSize(1);
    }

    @Test
    @DisplayName("an exemption covering the quarantining rule prevents the hold too")
    void anExemptionPreventsAHold() {
        givenRules(rule(FirewallRuleType.MIN_AGE, FirewallAction.BLOCK));
        exemptions.covering(FirewallRuleType.MIN_AGE);

        FirewallDecision decision =
                evaluator(holdingRule(FirewallRuleType.MIN_AGE)).evaluate(context());

        assertThat(decision.blocked()).isFalse();
        assertThat(decision.reason()).isEqualTo(FirewallDecision.Reason.EXEMPTED);
        assertThat(decision.hold()).isNull();
    }

    // --------------------------------------------------------- indeterminate

    @Test
    @DisplayName("INDETERMINATE under FAIL_CLOSED holds the component as EVALUATION_INCOMPLETE")
    void indeterminateHoldsWhenFailClosed() {
        givenRules(rule(FirewallRuleType.MIN_AGE, FirewallAction.BLOCK));

        FirewallDecision decision = evaluator(undecidableRule(FirewallRuleType.MIN_AGE))
                .evaluate(context(FirewallFailMode.FAIL_CLOSED, false));

        assertThat(decision.blocked()).isTrue();
        assertThat(decision.reason()).isEqualTo(FirewallDecision.Reason.QUARANTINED);
        assertThat(decision.hold().reason())
                .as("the data is expected to arrive; the sweep releases it when it does")
                .isEqualTo(FirewallQuarantineReason.EVALUATION_INCOMPLETE);
        assertThat(decision.violations())
                .as("the refusal has to be explainable: nothing matched, and the rule that "
                        + "could not tell is the entire reason the download was withheld")
                .hasSize(1);
        FirewallRuleViolation undecided = decision.violations().get(0);
        assertThat(undecided.ruleType()).isEqualTo(FirewallRuleType.MIN_AGE);
        assertThat(undecided.undecided())
                .as("and it says so as a fact, not as a phrase inside the reason text — "
                        + "'which rules have been undecidable this week' is a query")
                .isTrue();
        assertThat(undecided.matched()).isFalse();
        assertThat(undecided.advisoryIds())
                .as("nothing matched, so no advisory may be attached to it")
                .isEmpty();
    }

    @Test
    @DisplayName("INDETERMINATE under FAIL_OPEN serves, and costs no exemption lookup")
    void indeterminateServesWhenFailOpen() {
        givenRules(rule(FirewallRuleType.MIN_AGE, FirewallAction.BLOCK));

        FirewallDecision decision = evaluator(undecidableRule(FirewallRuleType.MIN_AGE))
                .evaluate(context(FirewallFailMode.FAIL_OPEN, false));

        assertThat(decision.blocked()).isFalse();
        assertThat(decision.reason()).isEqualTo(FirewallDecision.Reason.ALLOWED);
        assertThat(exemptions.lookups)
                .as("fail-open serves either way, so the index read would buy nothing")
                .isZero();
    }

    @Test
    @DisplayName("an exemption covering the undecidable rule prevents the fail-closed hold")
    void anExemptionPreventsTheFailClosedHold() {
        givenRules(rule(FirewallRuleType.MIN_AGE, FirewallAction.BLOCK));
        exemptions.covering(FirewallRuleType.MIN_AGE);

        FirewallDecision decision = evaluator(undecidableRule(FirewallRuleType.MIN_AGE))
                .evaluate(context(FirewallFailMode.FAIL_CLOSED, false));

        assertThat(decision.blocked())
                .as("holding a component somebody is exempt from is a hold nobody can act on")
                .isFalse();
        assertThat(decision.hold()).isNull();
    }

    // ----------------------------------------------------------- pre-existing

    @Test
    @DisplayName("a component that was already in the repository is recorded but not blocked")
    void preExistingComponentIsNeverBlocked() {
        givenRules(cvssRule(FirewallAction.BLOCK, 9.0));

        FirewallDecision decision = evaluator().evaluate(context(
                FirewallFailMode.FAIL_OPEN,
                true,
                finding("GHSA-jfh8-c2jp-5v3q", 10.0, MatchConfidence.EXACT)));

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
    @DisplayName("a pre-existing component is not held either — PRE_EXISTING outranks the queue")
    void preExistingBeatsAHold() {
        givenRules(rule(FirewallRuleType.MIN_AGE, FirewallAction.BLOCK));

        FirewallDecision decision = evaluator(holdingRule(FirewallRuleType.MIN_AGE))
                .evaluate(context(FirewallFailMode.FAIL_CLOSED, true));

        assertThat(decision.blocked()).isFalse();
        assertThat(decision.reason()).isEqualTo(FirewallDecision.Reason.PRE_EXISTING);
        assertThat(decision.hold())
                .as("a queue entry for a component that is being served would be a queue nobody trusts")
                .isNull();
    }

    @Test
    @DisplayName("a pre-existing component is not held by fail-closed indeterminacy either")
    void preExistingBeatsTheFailClosedHold() {
        givenRules(rule(FirewallRuleType.MIN_AGE, FirewallAction.BLOCK));

        FirewallDecision decision = evaluator(undecidableRule(FirewallRuleType.MIN_AGE))
                .evaluate(context(FirewallFailMode.FAIL_CLOSED, true));

        assertThat(decision.blocked()).isFalse();
        assertThat(decision.reason()).isEqualTo(FirewallDecision.Reason.PRE_EXISTING);
    }

    // --------------------------------------------------------- startup audit

    @Test
    @DisplayName("anyPolicyEnables sees an enabled rule of that type, and only an enabled one")
    void anyPolicyEnablesReadsTheConfiguredRules() {
        FirewallPolicyRuleEntity enabled = rule(FirewallRuleType.MIN_AGE, FirewallAction.BLOCK);
        FirewallPolicyRuleEntity disabled = rule(FirewallRuleType.LICENSE, FirewallAction.BLOCK);
        disabled.setEnabled(false);
        when(rules.findAll()).thenReturn(List.of(enabled, disabled));

        assertThat(evaluator().anyPolicyEnables(FirewallRuleType.MIN_AGE)).isTrue();
        assertThat(evaluator().anyPolicyEnables(FirewallRuleType.LICENSE)).isFalse();
        assertThat(evaluator().anyPolicyEnables(FirewallRuleType.TYPOSQUAT)).isFalse();
    }

    @Test
    @DisplayName("anyPolicyEnables answers 'no' rather than throwing at a caller that only wants to warn")
    void anyPolicyEnablesSurvivesAnUnreadableTable() {
        when(rules.findAll()).thenThrow(new IllegalStateException("policy table unreachable"));

        assertThat(evaluator().anyPolicyEnables(FirewallRuleType.MIN_AGE)).isFalse();
    }

    // ------------------------------------------------------------------

    private FirewallPolicyEvaluator evaluator(FirewallRule... extra) {
        List<FirewallRule> beans = new ArrayList<>(List.of(
                new CvssThresholdRule(), new KnownMaliciousRule()));
        beans.addAll(List.of(extra));
        return new FirewallPolicyEvaluator(
                policies, rules, new FirewallRuleRegistry(beans), provider(exemptions));
    }

    /**
     * An {@link ObjectProvider} that hands out the given service, or nothing at
     * all when it is null — the shape of a context assembled without the
     * exemption package.
     */
    @SuppressWarnings("unchecked")
    private static ObjectProvider<ExemptionService> provider(ExemptionService service) {
        ObjectProvider<ExemptionService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(service);
        return provider;
    }

    private void givenRules(FirewallPolicyRuleEntity... entities) {
        when(rules.findByPolicyIdAndEnabledTrue(POLICY_ID)).thenReturn(List.of(entities));
    }

    private static FirewallRuleContext context(AdvisoryFinding... findings) {
        return context(FirewallFailMode.FAIL_OPEN, false, findings);
    }

    private static FirewallRuleContext context(FirewallFailMode failMode, boolean preExisting,
            AdvisoryFinding... findings) {
        return context(
                new FirewallRepositorySettings(FirewallMode.QUARANTINE, failMode, null, true),
                preExisting,
                List.of(findings));
    }

    private static FirewallRuleContext context(
            FirewallRepositorySettings settings, boolean preExisting, List<AdvisoryFinding> findings) {
        return new FirewallRuleContext(
                REPOSITORY_ID,
                "maven-central",
                RepositoryType.PROXY,
                "org/apache/logging/log4j/log4j-core/2.14.1/log4j-core-2.14.1.jar",
                identity(),
                findings,
                ComponentFacts.unknown(identity().key()),
                settings,
                false,
                preExisting,
                NOW);
    }

    private static ComponentIdentity identity() {
        try {
            return new ComponentIdentity.Purl(new PackageURL(
                    "maven", "org.apache.logging.log4j", "log4j-core", "2.14.1", null, null));
        } catch (MalformedPackageURLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static FirewallPolicyRuleEntity cvssRule(FirewallAction action, double minScore) {
        FirewallPolicyRuleEntity rule = rule(FirewallRuleType.CVSS_THRESHOLD, action);
        rule.setConfig(Map.of("minScore", minScore));
        return rule;
    }

    private static FirewallPolicyRuleEntity maliciousRule(FirewallAction action) {
        return rule(FirewallRuleType.KNOWN_MALICIOUS, action);
    }

    private static FirewallPolicyRuleEntity rule(FirewallRuleType type, FirewallAction action) {
        FirewallPolicyRuleEntity rule = new FirewallPolicyRuleEntity();
        rule.setId(UUID.randomUUID());
        rule.setPolicyId(POLICY_ID);
        rule.setRuleType(type);
        rule.setAction(action);
        rule.setConfig(Map.of());
        rule.setEnabled(true);
        return rule;
    }

    private static AdvisoryFinding finding(String id, Double score, MatchConfidence confidence) {
        return new AdvisoryFinding(
                id, "summary", score == null ? null : "CRITICAL", score, null, null, null,
                List.of(new AdvisoryMatch(id, "OSV", confidence, ">=1.0, <2.0")));
    }

    // ------------------------------------------------------------ test doubles

    /** A rule that always matches and asks for the component to be held. */
    private static FirewallRule holdingRule(FirewallRuleType type) {
        return new FixedRule(
                type,
                FirewallRuleOutcome.matched(new FirewallRuleViolation(
                        type, FirewallAction.BLOCK, "the stub rule matched", List.of())),
                true);
    }

    /** A rule that needs a fact nobody has resolved yet. */
    private static FirewallRule undecidableRule(FirewallRuleType type) {
        return new FixedRule(
                type,
                FirewallRuleOutcome.indeterminate("the publication date has not been resolved yet"),
                true);
    }

    /**
     * A rule with a fixed answer.
     *
     * <p>Hand-written rather than mocked because what is under test is the
     * engine's reaction to an answer, and a stub says which answer far more
     * legibly than a chain of {@code when(...)}. The action on the violation is
     * BLOCK so that {@link FirewallRule#quarantineOnMatch()} is actually
     * consulted — it is only asked about a rule that would deny.
     */
    private record FixedRule(FirewallRuleType type, FirewallRuleOutcome outcome, boolean holds)
            implements FirewallRule {

        @Override
        public FirewallRuleType ruleType() {
            return type;
        }

        @Override
        public FirewallRuleOutcome evaluate(FirewallRuleContext context, FirewallRuleSettings settings) {
            // The policy's action wins over the stub's: a WARN row must produce a
            // WARN violation, which is what proves the engine reads the row.
            if (!outcome.matched()) {
                return outcome;
            }
            return FirewallRuleOutcome.matched(new FirewallRuleViolation(
                    type, settings.action(), outcome.violation().reason(), List.of()));
        }

        @Override
        public boolean quarantineOnMatch() {
            return holds;
        }

        @Override
        public FirewallQuarantineReason quarantineReason() {
            return FirewallQuarantineReason.MIN_AGE_NOT_MET;
        }
    }

    /** A rule with a defect — the case the registry has to contain. */
    private record ThrowingRule(FirewallRuleType type) implements FirewallRule {

        @Override
        public FirewallRuleType ruleType() {
            return type;
        }

        @Override
        public FirewallRuleOutcome evaluate(FirewallRuleContext context, FirewallRuleSettings settings) {
            throw new IllegalStateException("the license corpus is not loaded");
        }
    }

    /**
     * The exemption store, as much of it as the engine touches.
     *
     * <p>A stub rather than a mock so that "how many times was the index read?"
     * is an assertable number: the engine promises one lookup per blocking rule
     * that matched and none at all otherwise, and that promise is what keeps an
     * enforced download's cost bounded.
     */
    private static final class StubExemptionService implements ExemptionService {

        private FirewallRuleType covered;
        private boolean broken;
        private int lookups;

        void covering(FirewallRuleType ruleType) {
            this.covered = ruleType;
        }

        void failing() {
            this.broken = true;
        }

        @Override
        public Optional<FirewallExemption> findApplicable(
                UUID repositoryId, ComponentIdentity identity, FirewallRuleType ruleType, Instant at) {
            lookups++;
            if (broken) {
                throw new IllegalStateException("exemption index unreachable");
            }
            return covered == ruleType ? Optional.of(exemption(ruleType)) : Optional.empty();
        }

        private static FirewallExemption exemption(FirewallRuleType ruleType) {
            return new FirewallExemption(
                    EXEMPTION_ID,
                    "pkg:maven/org.apache.logging.log4j/log4j-core@2.14.1",
                    FirewallComponentKeyKind.PURL,
                    FirewallExemptionScope.VERSION,
                    REPOSITORY_ID,
                    ruleType,
                    List.of(),
                    FirewallExemptionState.APPROVED,
                    null,
                    null,
                    "needed for the 2026-08 release",
                    "release-bot",
                    NOW,
                    "security",
                    NOW,
                    "approved for one release");
        }

        // Nothing below is on the engine's path; a call would be a design change.

        @Override
        public List<FirewallExemption> findApplicable(
                UUID repositoryId, ComponentIdentity identity, Instant at) {
            throw new UnsupportedOperationException();
        }

        @Override
        public FirewallExemption request(ExemptionRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public FirewallExemption approve(UUID id, String approver, String note, Instant expiresAt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public FirewallExemption reject(UUID id, String approver, String note) {
            throw new UnsupportedOperationException();
        }

        @Override
        public FirewallExemption revoke(UUID id, String approver, String note) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<FirewallExemption> find(UUID id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Page<FirewallExemption> list(ExemptionQuery query, Pageable pageable) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ExemptionSummary summary() {
            throw new UnsupportedOperationException();
        }

        @Override
        public int expireLapsed(Instant now) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<FirewallExemption> notifyUpcomingExpiry(Instant now, Duration lead) {
            throw new UnsupportedOperationException();
        }
    }
}
