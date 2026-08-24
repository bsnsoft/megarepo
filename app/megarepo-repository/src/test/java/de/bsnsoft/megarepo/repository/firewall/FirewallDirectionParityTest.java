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
import de.bsnsoft.megarepo.database.repository.AssetJpaRepository;
import de.bsnsoft.megarepo.database.repository.FirewallPolicyJpaRepository;
import de.bsnsoft.megarepo.database.repository.FirewallPolicyRuleJpaRepository;
import de.bsnsoft.megarepo.repository.advisory.AdvisoryLookupService;
import de.bsnsoft.megarepo.repository.firewall.exemption.ExemptionService;
import de.bsnsoft.megarepo.repository.firewall.exemption.FirewallExemption;
import de.bsnsoft.megarepo.repository.firewall.facts.ComponentFactsService;
import de.bsnsoft.megarepo.repository.firewall.identity.ComponentIdentity;
import de.bsnsoft.megarepo.repository.firewall.identity.PurlBuilder;
import de.bsnsoft.megarepo.repository.firewall.quarantine.FirewallQuarantineEntry;
import de.bsnsoft.megarepo.repository.firewall.quarantine.QuarantineService;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRule;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRuleContext;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRuleOutcome;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRuleRegistry;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRuleSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;

import java.lang.reflect.Constructor;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The two directions reach the same verdict, because they run the same code
 * (osTicket #155155).
 *
 * <h2>What this class is for</h2>
 *
 * It is the test that would have caught the bug. The download path consulted the
 * exemptions; the publish path assembled its own decision and did not, so an
 * approved exemption served a component and refused the identical publish. Every
 * unit test that existed at the time passed: each path was tested against its own
 * behaviour, and neither was tested against the other's.
 *
 * <p>So this class asks one question — <em>given the same component, the same
 * policy, the same repository and the same operator decisions, do the two
 * directions agree?</em> — across the cases where they could plausibly disagree:
 * a plain block, a hold, a fail-mode call, an exemption on a matched rule, an
 * exemption on a rule that could not decide, a grandfathered component, and a
 * component the queue has already decided about.
 *
 * <p>It deliberately compares the two <em>request paths</em>
 * ({@link FirewallEnforcementService} and {@link FirewallUploadEvaluator}) rather
 * than calling {@link FirewallDecisionAssembly} twice. Calling the shared piece
 * twice proves only that it is deterministic; the thing worth proving is that
 * both entry points still reach it.
 *
 * <h2>What may differ, and is therefore not asserted</h2>
 *
 * The rule context's {@code upload} flag, which is the whole point of having two
 * directions, and the {@link FirewallEvaluation.Outcome}, which describes how the
 * inspection went rather than what was decided. Everything a client or an
 * operator sees — refused or not, why, under which policy, naming which rules,
 * spending which exemption, held under which reason — is compared.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FirewallDirectionParityTest {

    private static final UUID REPOSITORY = UUID.randomUUID();
    private static final UUID POLICY_ID = UUID.randomUUID();
    private static final String NAME = "maven-releases";
    private static final String PATH = "com/acme/util/1.0.0/util-1.0.0.jar";
    private static final String KEY = "pkg:maven/com.acme/util@1.0.0";
    private static final Instant SWITCHED_ON = Instant.parse("2026-01-01T00:00:00Z");

    private static final FirewallRequestContext GET_CONTEXT =
            new FirewallRequestContext("ci-build", "10.0.0.7", PATH, "GET");
    private static final FirewallRequestContext PUT_CONTEXT =
            new FirewallRequestContext("release-bot", "10.0.0.7", PATH, "PUT");

    @Mock private FirewallEvaluationService evaluationService;
    @Mock private FirewallEnforcementSettingsService enforcementSettings;
    @Mock private FirewallViolationRecorder recorder;
    @Mock private FirewallPolicyJpaRepository policies;
    @Mock private FirewallPolicyRuleJpaRepository policyRules;
    @Mock private AdvisoryLookupService advisories;
    @Mock private AssetJpaRepository assets;
    @Mock private PurlBuilder purlBuilder;
    @Mock private QuarantineService quarantine;
    @Mock private ComponentFactsService facts;
    @Mock private ExemptionService exemptions;

    private StubRule rule;
    private FirewallRepositorySettings settings =
            new FirewallRepositorySettings(FirewallMode.QUARANTINE, FirewallFailMode.FAIL_OPEN, POLICY_ID, true);

    @BeforeEach
    void setUp() {
        FirewallPolicyEntity policy = new FirewallPolicyEntity();
        policy.setId(POLICY_ID);
        policy.setName("Default");
        when(policies.findById(POLICY_ID)).thenReturn(Optional.of(policy));
        when(policies.findByIsDefaultTrue()).thenReturn(Optional.of(policy));

        when(enforcementSettings.enforcementEnabled()).thenReturn(true);
        when(enforcementSettings.enforcingSince()).thenReturn(SWITCHED_ON);
        when(advisories.findAdvisories(any(ComponentIdentity.class))).thenReturn(List.of());
        when(assets.findByRepositoryIdAndPath(any(), any())).thenReturn(Optional.empty());
        when(facts.lookup(any())).thenReturn(null);
        when(quarantine.find(any(), any())).thenReturn(Optional.empty());
        when(quarantine.quarantine(any(), any(), any())).thenReturn(Optional.empty());
    }

    // ── The matrix ──────────────────────────────────────────────────────

    @Test
    @DisplayName("a blocking rule refuses both directions the same way")
    void aBlockingRuleAgrees() {
        givenRule(StubRule.matching(FirewallRuleType.KNOWN_MALICIOUS, FirewallAction.BLOCK, false));

        assertDirectionsAgree()
                .satisfies(verdict -> {
                    assertThat(verdict.blocked()).isTrue();
                    assertThat(verdict.decision().reason()).isEqualTo(FirewallDecision.Reason.POLICY);
                });
    }

    @Test
    @DisplayName("a WARN rule stops neither direction")
    void aWarningAgrees() {
        givenRule(StubRule.matching(FirewallRuleType.KNOWN_MALICIOUS, FirewallAction.WARN, false));

        assertDirectionsAgree()
                .satisfies(verdict -> {
                    assertThat(verdict.blocked()).isFalse();
                    assertThat(verdict.decision().reason()).isEqualTo(FirewallDecision.Reason.ALLOWED);
                    assertThat(verdict.decision().violations()).hasSize(1);
                });
    }

    @Test
    @DisplayName("a quarantining rule holds both directions under the same reason")
    void aHoldAgrees() {
        givenRule(StubRule.matching(FirewallRuleType.MIN_AGE, FirewallAction.BLOCK, true));

        assertDirectionsAgree()
                .satisfies(verdict -> {
                    assertThat(verdict.blocked()).isTrue();
                    assertThat(verdict.decision().reason())
                            .as("held, not plainly blocked — the difference between 'wait' and 'act', "
                                    + "and the publish path used to report the second for both")
                            .isEqualTo(FirewallDecision.Reason.QUARANTINED);
                    assertThat(verdict.decision().hold().reason())
                            .isEqualTo(FirewallQuarantineReason.MIN_AGE_NOT_MET);
                });
    }

    @Test
    @DisplayName("a malicious package is refused outright in both directions, queue or no queue")
    void anOutrightRefusalBeatsAHoldInBothDirections() {
        // KNOWN_MALICIOUS does not quarantine on match; MIN_AGE does. Design §5.1:
        // the component is refused, not queued — offering an operator a release
        // button for a package the policy calls malicious is the one outcome ruled
        // out. The publish path used to write the queue entry anyway, because it
        // took the first *quarantining* blocking rule regardless of the others.
        givenRules(
                StubRule.matching(FirewallRuleType.KNOWN_MALICIOUS, FirewallAction.BLOCK, false),
                StubRule.matching(FirewallRuleType.MIN_AGE, FirewallAction.BLOCK, true));

        assertDirectionsAgree()
                .satisfies(verdict -> {
                    assertThat(verdict.blocked()).isTrue();
                    assertThat(verdict.decision().reason()).isEqualTo(FirewallDecision.Reason.POLICY);
                    assertThat(verdict.decision().hold())
                            .as("and no queue entry is offered for it")
                            .isNull();
                });
    }

    @Test
    @DisplayName("an approved exemption serves and publishes alike — the reported bug")
    void anExemptionAgrees() {
        givenRule(StubRule.matching(FirewallRuleType.KNOWN_MALICIOUS, FirewallAction.BLOCK, false));
        UUID exemption = givenApprovedExemption(FirewallRuleType.KNOWN_MALICIOUS);

        assertDirectionsAgree()
                .satisfies(verdict -> {
                    assertThat(verdict.blocked())
                            .as("this is the pair of assertions the ticket is about: the operator "
                                    + "approved the exemption once, and it has to mean the same "
                                    + "thing whichever way the artifact is travelling")
                            .isFalse();
                    assertThat(verdict.decision().reason()).isEqualTo(FirewallDecision.Reason.EXEMPTED);
                    assertThat(verdict.decision().exemptionIds()).containsExactly(exemption);
                });
    }

    @Test
    @DisplayName("an expired exemption refuses both directions again")
    void anExpiredExemptionAgrees() {
        givenRule(StubRule.matching(FirewallRuleType.KNOWN_MALICIOUS, FirewallAction.BLOCK, false));
        // The store applies the expiry itself, so "expired" here is simply "no
        // applicable exemption" — the same answer both paths have to act on.
        when(exemptions.findApplicable(any(), any(), any(FirewallRuleType.class), any()))
                .thenReturn(Optional.empty());

        assertDirectionsAgree().satisfies(verdict -> assertThat(verdict.blocked()).isTrue());
    }

    @Test
    @DisplayName("fail-closed holds both directions when a rule cannot decide")
    void anUndecidableRuleUnderFailClosedAgrees() {
        settings = new FirewallRepositorySettings(
                FirewallMode.QUARANTINE, FirewallFailMode.FAIL_CLOSED, POLICY_ID, true);
        givenRule(StubRule.indeterminate(FirewallRuleType.MIN_AGE));

        assertDirectionsAgree()
                .satisfies(verdict -> {
                    assertThat(verdict.blocked()).isTrue();
                    assertThat(verdict.decision().reason())
                            .as("EVALUATION_UNAVAILABLE is what a firewall that ran out of time "
                                    + "reports; a rule that is waiting for a fact is held, and the "
                                    + "403 a developer reads says so in both directions")
                            .isEqualTo(FirewallDecision.Reason.QUARANTINED);
                    assertThat(verdict.decision().violations())
                            .as("and the rule that could not decide is named, or the refusal is "
                                    + "unexplainable")
                            .anyMatch(FirewallRuleViolation::undecided);
                });
    }

    @Test
    @DisplayName("fail-open serves and publishes when a rule cannot decide")
    void anUndecidableRuleUnderFailOpenAgrees() {
        givenRule(StubRule.indeterminate(FirewallRuleType.MIN_AGE));

        assertDirectionsAgree().satisfies(verdict -> assertThat(verdict.blocked()).isFalse());
    }

    @Test
    @DisplayName("an exemption covers a rule that cannot decide, in both directions")
    void anExemptionOverAnUndecidableRuleAgrees() {
        settings = new FirewallRepositorySettings(
                FirewallMode.QUARANTINE, FirewallFailMode.FAIL_CLOSED, POLICY_ID, true);
        givenRule(StubRule.indeterminate(FirewallRuleType.MIN_AGE));
        givenApprovedExemption(FirewallRuleType.MIN_AGE);

        assertDirectionsAgree()
                .satisfies(verdict -> assertThat(verdict.blocked())
                        .as("holding a component because MIN_AGE cannot yet tell, when the operator "
                                + "has already decided it may pass MIN_AGE, denies exactly what "
                                + "they approved")
                        .isFalse());
    }

    @Test
    @DisplayName("a released quarantine entry decides both directions without the rules running")
    void aReleasedEntryAgrees() {
        givenRule(StubRule.matching(FirewallRuleType.MIN_AGE, FirewallAction.BLOCK, true));
        when(quarantine.find(REPOSITORY, KEY)).thenReturn(Optional.of(
                entry(FirewallQuarantineState.RELEASED, FirewallQuarantineReason.MIN_AGE_NOT_MET)));

        assertDirectionsAgree()
                .satisfies(verdict -> {
                    assertThat(verdict.blocked())
                            .as("the release is somebody's decision, and the publisher's retry is "
                                    + "the request that was waiting for it")
                            .isFalse();
                    assertThat(verdict.decision().reason())
                            .isEqualTo(FirewallDecision.Reason.QUARANTINE_RELEASED);
                });
    }

    @Test
    @DisplayName("a still-held quarantine entry refuses both directions without the rules running")
    void aHeldEntryAgrees() {
        givenRule(StubRule.notMatching(FirewallRuleType.MIN_AGE));
        when(quarantine.find(REPOSITORY, KEY)).thenReturn(Optional.of(
                entry(FirewallQuarantineState.QUARANTINED, FirewallQuarantineReason.MIN_AGE_NOT_MET)));

        assertDirectionsAgree()
                .satisfies(verdict -> {
                    assertThat(verdict.blocked()).isTrue();
                    assertThat(verdict.decision().reason()).isEqualTo(FirewallDecision.Reason.QUARANTINED);
                });
    }

    @Test
    @DisplayName("a component that predates the switch is denied in neither direction")
    void aPreExistingComponentAgrees() {
        givenRule(StubRule.matching(FirewallRuleType.KNOWN_MALICIOUS, FirewallAction.BLOCK, false));
        givenPreExisting();

        assertDirectionsAgree()
                .satisfies(verdict -> {
                    assertThat(verdict.blocked())
                            .as("the customer's hardest constraint: arming the firewall may not "
                                    + "break what was already working, and re-deploying an "
                                    + "existing path is exactly that")
                            .isFalse();
                    assertThat(verdict.decision().reason())
                            .isEqualTo(FirewallDecision.Reason.PRE_EXISTING);
                    assertThat(verdict.decision().violations())
                            .as("recorded, not denied — the audit trail still has to answer 'what "
                                    + "would this policy have done?', and the publish path used to "
                                    + "return before the rules ran at all")
                            .hasSize(1);
                });
    }

    @Test
    @DisplayName("with no policy row at all both directions fall back to the same built-in rules")
    void theBuiltInFallbackAgrees() {
        settings = new FirewallRepositorySettings(
                FirewallMode.QUARANTINE, FirewallFailMode.FAIL_OPEN, null, true);
        when(policies.findByIsDefaultTrue()).thenReturn(Optional.empty());
        givenRule(StubRule.matching(FirewallRuleType.KNOWN_MALICIOUS, FirewallAction.BLOCK, false));

        assertDirectionsAgree()
                .satisfies(verdict -> {
                    assertThat(verdict.blocked())
                            .as("an armed repository whose policy somebody deleted must not start "
                                    + "accepting every publish; and a publish allowed by a fallback "
                                    + "the download would have refused is a hole with a plausible "
                                    + "explanation")
                            .isTrue();
                    assertThat(verdict.decision().policyName())
                            .isEqualTo(FirewallPolicyEvaluator.BUILT_IN_POLICY_NAME);
                });
    }

    // ── The structural half ─────────────────────────────────────────────

    @Test
    @DisplayName("the publish path owns no decision machinery of its own")
    void thePublishPathHasNoSecondAssembly() {
        Constructor<?> constructor = FirewallUploadEvaluator.class.getConstructors()[0];
        List<Class<?>> parameters = Arrays.asList(constructor.getParameterTypes());

        assertThat(parameters)
                .as("the publish path reaches its verdict through the shared assembly")
                .contains(FirewallDecisionAssembly.class);
        assertThat(parameters)
                .as("and holds none of the pieces a decision is made of. Re-acquiring any of these "
                        + "is how the second assembly grew the first time: each one arrives for a "
                        + "reason that sounds local, and the exemption step is the one that gets "
                        + "forgotten")
                .doesNotContain(
                        FirewallRuleRegistry.class,
                        FirewallPolicyJpaRepository.class,
                        FirewallPolicyRuleJpaRepository.class,
                        FirewallPolicyEvaluator.class,
                        ExemptionService.class,
                        QuarantineService.class);
    }

    // ── Running both directions ─────────────────────────────────────────

    /**
     * Evaluates the same component both ways and asserts the two verdicts are the
     * same decision, then hands that decision to the caller for the assertions
     * that say <em>which</em> decision it should have been.
     *
     * <p>Both halves matter. "They agree" alone would be satisfied by two paths
     * that are both wrong in the same way; "the download blocks" alone is what the
     * old tests asserted, one path at a time.
     */
    private org.assertj.core.api.ObjectAssert<FirewallEvaluation> assertDirectionsAgree() {
        FirewallEvaluation downloaded = downloadPath().evaluate(
                REPOSITORY, NAME, RepositoryType.HOSTED, PATH, GET_CONTEXT);
        FirewallEvaluation published = uploadPath().evaluate(
                new FirewallUploadEvaluator.UploadCandidate(
                        REPOSITORY, NAME, RepositoryType.HOSTED, PATH, identity()),
                PUT_CONTEXT);

        assertThat(published.blocked())
                .as("refused on publish but served on download (or the other way round) for the "
                        + "same component, policy and repository")
                .isEqualTo(downloaded.blocked());
        assertThat(published.decision().reason())
                .as("the same verdict has to be reported for the same reason: the 403 body, the "
                        + "headers and the audit row all key on it")
                .isEqualTo(downloaded.decision().reason());
        assertThat(published.decision().policyName()).isEqualTo(downloaded.decision().policyName());
        assertThat(published.preExisting()).isEqualTo(downloaded.preExisting());
        assertThat(ruleTypes(published)).isEqualTo(ruleTypes(downloaded));
        assertThat(published.decision().exemptionIds())
                .isEqualTo(downloaded.decision().exemptionIds());
        assertThat(holdReason(published)).isEqualTo(holdReason(downloaded));

        return assertThat(published);
    }

    private static List<FirewallRuleType> ruleTypes(FirewallEvaluation evaluation) {
        List<FirewallRuleType> types = new ArrayList<>();
        for (FirewallRuleViolation violation : evaluation.decision().violations()) {
            types.add(violation.ruleType());
        }
        return types;
    }

    private static FirewallQuarantineReason holdReason(FirewallEvaluation evaluation) {
        FirewallDecision.Hold hold = evaluation.decision().hold();
        return hold == null ? null : hold.reason();
    }

    // ── The two paths, over one assembly ────────────────────────────────

    private FirewallDecisionAssembly assembly() {
        FirewallPolicyEvaluator policy = new FirewallPolicyEvaluator(
                policies, policyRules, new FirewallRuleRegistry(List.copyOf(rules)), provider(exemptions));
        return new FirewallDecisionAssembly(policy, quarantine, provider(facts));
    }

    private FirewallEnforcementService downloadPath() {
        when(evaluationService.resolveSettings(REPOSITORY)).thenReturn(settings);
        when(evaluationService.inspect(eq(REPOSITORY), eq(NAME), eq(PATH), any(), any()))
                .thenReturn(inspection());
        return new FirewallEnforcementService(
                evaluationService, assembly(), enforcementSettings, recorder,
                new FirewallEnforcementProperties(
                        true, Duration.ofSeconds(10), Duration.ofSeconds(10), 4, 200),
                directExecutor(),
                false);
    }

    private FirewallUploadEvaluator uploadPath() {
        when(evaluationService.resolveSettings(REPOSITORY)).thenReturn(settings);
        return new FirewallUploadEvaluator(
                evaluationService, enforcementSettings, assembly(), advisories, assets, purlBuilder);
    }

    /**
     * What both paths see before anything is judged.
     *
     * <p>The download path is handed this by {@code FirewallEvaluationService};
     * the publish path builds the same shape from the component the format handler
     * wrote. Stubbing the download side with exactly what the publish side derives
     * is what makes the comparison about the decision rather than about the two
     * lookups.
     */
    private FirewallEvaluation inspection() {
        return new FirewallEvaluation(
                REPOSITORY, NAME, PATH, settings, identity(), List.of(),
                FirewallEvaluation.Outcome.CLEAN, preExisting, FirewallDecision.notEvaluated());
    }

    // ── Fixture ─────────────────────────────────────────────────────────

    private final List<FirewallRule> rules = new ArrayList<>();
    private boolean preExisting;

    private void givenRule(StubRule only) {
        givenRules(only);
    }

    private void givenRules(StubRule... stubs) {
        rules.clear();
        List<FirewallPolicyRuleEntity> configured = new ArrayList<>();
        for (StubRule stub : stubs) {
            rules.add(stub);
            configured.add(policyRule(stub.ruleType(), stub.action));
        }
        rule = stubs.length == 0 ? null : stubs[0];
        when(policyRules.findByPolicyIdAndEnabledTrue(POLICY_ID)).thenReturn(configured);
    }

    /**
     * Makes the component count as already stored before enforcement was switched
     * on — through each path's own mechanism, which is precisely the pair being
     * compared.
     */
    private void givenPreExisting() {
        preExisting = true;
        de.bsnsoft.megarepo.database.entity.AssetEntity asset =
                new de.bsnsoft.megarepo.database.entity.AssetEntity();
        asset.setCreatedAt(SWITCHED_ON.minus(Duration.ofDays(30)));
        when(assets.findByRepositoryIdAndPath(REPOSITORY, PATH)).thenReturn(Optional.of(asset));
    }

    private UUID givenApprovedExemption(FirewallRuleType ruleType) {
        UUID id = UUID.randomUUID();
        FirewallExemption exemption = new FirewallExemption(
                id, KEY, FirewallComponentKeyKind.PURL, FirewallExemptionScope.VERSION,
                REPOSITORY, ruleType, List.of(), FirewallExemptionState.APPROVED,
                null, null, "the fix is not released yet", "release-bot",
                SWITCHED_ON, "security-lead", SWITCHED_ON, "signed off for one sprint");
        when(exemptions.findApplicable(eq(REPOSITORY), any(), eq(ruleType), any()))
                .thenReturn(Optional.of(exemption));
        return id;
    }

    private static FirewallPolicyRuleEntity policyRule(FirewallRuleType type, FirewallAction action) {
        FirewallPolicyRuleEntity entity = new FirewallPolicyRuleEntity();
        entity.setId(UUID.randomUUID());
        entity.setRuleType(type);
        entity.setAction(action);
        entity.setConfig(Map.of());
        entity.setEnabled(true);
        return entity;
    }

    private static FirewallQuarantineEntry entry(
            FirewallQuarantineState state, FirewallQuarantineReason reason) {
        return new FirewallQuarantineEntry(
                UUID.randomUUID(), REPOSITORY, NAME, KEY, PATH, state, reason, null, null,
                Map.of(), SWITCHED_ON, SWITCHED_ON, 3, SWITCHED_ON,
                SWITCHED_ON.plus(Duration.ofMinutes(11)), null, null, null, null);
    }

    private static ComponentIdentity identity() {
        try {
            return new ComponentIdentity.Purl(new PackageURL(KEY));
        } catch (MalformedPackageURLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static <T> ObjectProvider<T> provider(T value) {
        @SuppressWarnings("unchecked")
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    /** Runs the evaluation on the calling thread, so an assertion needs no wait. */
    private static ExecutorService directExecutor() {
        return new AbstractExecutorService() {
            @Override public void shutdown() {}
            @Override public List<Runnable> shutdownNow() {
                return List.of();
            }
            @Override public boolean isShutdown() {
                return false;
            }
            @Override public boolean isTerminated() {
                return false;
            }
            @Override public boolean awaitTermination(long timeout, TimeUnit unit) {
                return true;
            }
            @Override public void execute(Runnable command) {
                command.run();
            }
        };
    }

    /** A rule with a fixed answer. */
    private static final class StubRule implements FirewallRule {

        private final FirewallRuleType ruleType;
        private final FirewallAction action;
        private final FirewallRuleOutcome outcome;
        private final boolean holds;

        private StubRule(
                FirewallRuleType ruleType,
                FirewallAction action,
                FirewallRuleOutcome outcome,
                boolean holds) {
            this.ruleType = ruleType;
            this.action = action;
            this.outcome = outcome;
            this.holds = holds;
        }

        static StubRule notMatching(FirewallRuleType type) {
            return new StubRule(type, FirewallAction.BLOCK, FirewallRuleOutcome.notMatched(), false);
        }

        static StubRule matching(FirewallRuleType type, FirewallAction action, boolean holds) {
            return new StubRule(type, action, FirewallRuleOutcome.matched(new FirewallRuleViolation(
                    type, action, "stub rule matched", List.of())), holds);
        }

        static StubRule indeterminate(FirewallRuleType type) {
            return new StubRule(type, FirewallAction.BLOCK,
                    FirewallRuleOutcome.indeterminate("the publication date has not resolved yet"),
                    true);
        }

        @Override
        public FirewallRuleType ruleType() {
            return ruleType;
        }

        @Override
        public FirewallRuleOutcome evaluate(FirewallRuleContext context, FirewallRuleSettings settings) {
            return outcome;
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
}
