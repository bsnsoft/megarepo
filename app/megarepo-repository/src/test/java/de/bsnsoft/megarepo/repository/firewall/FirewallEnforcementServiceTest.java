package de.bsnsoft.megarepo.repository.firewall;

import com.github.packageurl.PackageURL;
import de.bsnsoft.megarepo.core.firewall.FirewallAction;
import de.bsnsoft.megarepo.core.firewall.FirewallFailMode;
import de.bsnsoft.megarepo.core.firewall.FirewallMode;
import de.bsnsoft.megarepo.core.firewall.FirewallRuleType;
import de.bsnsoft.megarepo.repository.advisory.AdvisoryFinding;
import de.bsnsoft.megarepo.repository.advisory.AdvisoryMatch;
import de.bsnsoft.megarepo.repository.advisory.MatchConfidence;
import de.bsnsoft.megarepo.repository.firewall.identity.ComponentIdentity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The switch, and what happens on either side of it.
 *
 * <p>The single most important assertion in this class is the first one: with
 * the master switch off, a repository explicitly configured for QUARANTINE with
 * a critical advisory on file is still served. That is the promise an
 * installation upgrading into this build depends on.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FirewallEnforcementServiceTest {

    private static final UUID REPO_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID POLICY_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final String REPO = "maven-central";
    private static final String PATH =
            "org/apache/logging/log4j/log4j-core/2.14.1/log4j-core-2.14.1.jar";
    private static final FirewallRequestContext CONTEXT =
            new FirewallRequestContext("ci-build", "10.0.0.7", PATH, "GET");

    @Mock private FirewallEvaluationService evaluation;
    @Mock private FirewallPolicyEvaluator policy;
    @Mock private FirewallEnforcementSettingsService settings;
    @Mock private FirewallViolationRecorder recorder;

    @Test
    @DisplayName("master switch OFF: a QUARANTINE repository with a critical advisory is still served")
    void masterSwitchOffServesEvenInQuarantine() {
        when(settings.enforcementEnabled()).thenReturn(false);
        when(evaluation.resolveSettings(REPO_ID)).thenReturn(quarantine(FirewallFailMode.FAIL_CLOSED));

        FirewallEvaluation verdict = service().evaluate(REPO_ID, REPO, PATH, CONTEXT);

        assertThat(verdict.blocked()).isFalse();
        assertThat(verdict.outcome()).isEqualTo(FirewallEvaluation.Outcome.NOT_ENFORCING);
        assertThat(verdict.enforcementEvaluated())
                .as("the observation path still has to run for this download")
                .isFalse();
        verifyNoInteractions(policy, recorder);
        verify(evaluation, never()).inspect(any(), anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("master switch ON but the repository is in AUDIT: nothing is decided")
    void auditModeIsNotEnforced() {
        when(settings.enforcementEnabled()).thenReturn(true);
        when(evaluation.resolveSettings(REPO_ID)).thenReturn(new FirewallRepositorySettings(
                FirewallMode.AUDIT, FirewallFailMode.FAIL_CLOSED, POLICY_ID, true));

        FirewallEvaluation verdict = service().evaluate(REPO_ID, REPO, PATH, CONTEXT);

        assertThat(verdict.blocked()).isFalse();
        assertThat(verdict.outcome()).isEqualTo(FirewallEvaluation.Outcome.NOT_ENFORCING);
        verifyNoInteractions(policy, recorder);
    }

    @Test
    @DisplayName("master switch ON + QUARANTINE + a BLOCK rule: the download is denied and recorded")
    void switchOnAndQuarantineBlocks() {
        givenEnforcing();
        givenMatchedComponent(false);
        when(policy.evaluate(any(), any(), anyBoolean())).thenReturn(FirewallDecision.blocked(
                POLICY_ID, "Default", List.of(blockingViolation())));

        FirewallEvaluation verdict = service().evaluate(REPO_ID, REPO, PATH, CONTEXT);

        assertThat(verdict.blocked()).isTrue();
        assertThat(verdict.enforcementEvaluated()).isTrue();
        assertThat(verdict.decision().reason()).isEqualTo(FirewallDecision.Reason.POLICY);
        verify(recorder).recordDecision(any(FirewallEvaluation.class), eq(CONTEXT));
    }

    @Test
    @DisplayName("a WARN rule matches, is recorded, and the download goes out")
    void warnDoesNotBlock() {
        givenEnforcing();
        givenMatchedComponent(false);
        when(policy.evaluate(any(), any(), anyBoolean())).thenReturn(FirewallDecision.allowed(
                POLICY_ID, "Default",
                List.of(new FirewallRuleViolation(
                        FirewallRuleType.CVSS_THRESHOLD, FirewallAction.WARN,
                        "CVSS 10 is at or above the configured threshold of 9",
                        List.of("GHSA-jfh8-c2jp-5v3q")))));

        FirewallEvaluation verdict = service().evaluate(REPO_ID, REPO, PATH, CONTEXT);

        assertThat(verdict.blocked()).isFalse();
        verify(recorder).recordDecision(any(FirewallEvaluation.class), eq(CONTEXT));
    }

    @Test
    @DisplayName("a component stored before enforcement was switched on is passed to the policy as pre-existing")
    void preExistingComponentIsFlaggedAndServed() {
        givenEnforcing();
        givenMatchedComponent(true);
        when(policy.evaluate(any(), any(), anyBoolean())).thenReturn(FirewallDecision.preExisting(
                POLICY_ID, "Default", List.of(blockingViolation())));

        FirewallEvaluation verdict = service().evaluate(REPO_ID, REPO, PATH, CONTEXT);

        assertThat(verdict.blocked())
                .as("already-present components are audited, never blocked retroactively")
                .isFalse();
        assertThat(verdict.preExisting()).isTrue();
        ArgumentCaptor<Boolean> preExisting = ArgumentCaptor.forClass(Boolean.class);
        verify(policy).evaluate(any(), any(), preExisting.capture());
        assertThat(preExisting.getValue()).isTrue();
        verify(recorder).recordDecision(any(FirewallEvaluation.class), eq(CONTEXT));
    }

    @Test
    @DisplayName("the watermark handed to the inspection is the moment enforcement was switched on")
    void watermarkIsTheEnforcementStart() {
        Instant since = Instant.parse("2026-08-01T09:00:00Z");
        givenEnforcing();
        when(settings.enforcingSince()).thenReturn(since);
        givenMatchedComponent(false);
        when(policy.evaluate(any(), any(), anyBoolean())).thenReturn(FirewallDecision.allowed());

        service().evaluate(REPO_ID, REPO, PATH, CONTEXT);

        verify(evaluation).inspect(eq(REPO_ID), eq(REPO), eq(PATH), any(), eq(since));
    }

    @Test
    @DisplayName("a clean component is served and nothing is written")
    void cleanComponentIsServedSilently() {
        givenEnforcing();
        when(evaluation.inspect(any(), anyString(), anyString(), any(), any()))
                .thenReturn(new FirewallEvaluation(
                        REPO_ID, REPO, PATH, quarantine(FirewallFailMode.FAIL_OPEN),
                        identity(), List.of(), FirewallEvaluation.Outcome.CLEAN));

        FirewallEvaluation verdict = service().evaluate(REPO_ID, REPO, PATH, CONTEXT);

        assertThat(verdict.blocked()).isFalse();
        assertThat(verdict.outcome()).isEqualTo(FirewallEvaluation.Outcome.CLEAN);
        verify(recorder, never()).recordDecision(any(), any());
        verifyNoInteractions(policy);
    }

    @Test
    @DisplayName("FAIL_OPEN: an evaluation that does not finish in time serves the download")
    void failOpenServesWhenTheVerdictIsLate() {
        givenEnforcing(FirewallFailMode.FAIL_OPEN);

        FirewallEvaluation verdict = service(new StalledExecutorService()).evaluate(REPO_ID, REPO, PATH, CONTEXT);

        assertThat(verdict.blocked())
                .as("an unavailable firewall breaking every build is worse than one artifact slipping through")
                .isFalse();
        assertThat(verdict.outcome()).isEqualTo(FirewallEvaluation.Outcome.UNAVAILABLE);
        assertThat(verdict.decision().failModeApplied()).isTrue();
    }

    @Test
    @DisplayName("FAIL_CLOSED: an evaluation that does not finish in time denies the download")
    void failClosedDeniesWhenTheVerdictIsLate() {
        givenEnforcing(FirewallFailMode.FAIL_CLOSED);

        FirewallEnforcementService service = service(new StalledExecutorService());
        FirewallEvaluation verdict = service.evaluate(REPO_ID, REPO, PATH, CONTEXT);

        assertThat(verdict.blocked()).isTrue();
        assertThat(verdict.decision().reason())
                .isEqualTo(FirewallDecision.Reason.EVALUATION_UNAVAILABLE);
        assertThat(service.unavailableCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a saturated evaluation pool is an unavailable verdict, not a queued request")
    void rejectedEvaluationUsesTheFailMode() {
        givenEnforcing(FirewallFailMode.FAIL_CLOSED);

        FirewallEvaluation verdict =
                service(new RejectingExecutorService()).evaluate(REPO_ID, REPO, PATH, CONTEXT);

        assertThat(verdict.outcome()).isEqualTo(FirewallEvaluation.Outcome.UNAVAILABLE);
        assertThat(verdict.blocked()).isTrue();
    }

    @Test
    @DisplayName("an evaluation that throws is an unavailable verdict — FAIL_OPEN still serves")
    void evaluationThrowsAndFailOpenServes() {
        givenEnforcing(FirewallFailMode.FAIL_OPEN);
        when(evaluation.inspect(any(), anyString(), anyString(), any(), any()))
                .thenThrow(new IllegalStateException("advisory store unavailable"));

        FirewallEvaluation verdict = service().evaluate(REPO_ID, REPO, PATH, CONTEXT);

        assertThat(verdict.outcome()).isEqualTo(FirewallEvaluation.Outcome.UNAVAILABLE);
        assertThat(verdict.blocked()).isFalse();
    }

    @Test
    @DisplayName("a firewall broken outside its own fail mode serves the download regardless")
    void unexpectedFailureNeverCostsTheArtifact() {
        when(settings.enforcementEnabled()).thenThrow(new IllegalStateException("switch is unreadable"));

        FirewallEvaluation verdict = service().evaluate(REPO_ID, REPO, PATH, CONTEXT);

        assertThat(verdict.blocked()).isFalse();
        assertThat(verdict.outcome()).isEqualTo(FirewallEvaluation.Outcome.FAILED);
        assertThat(verdict.enforcementEvaluated())
                .as("nothing was decided, so the observation path still owns this download")
                .isFalse();
    }

    @Test
    @DisplayName("a recorder that throws does not change a verdict that was already given")
    void recorderFailureDoesNotChangeTheVerdict() {
        givenEnforcing();
        givenMatchedComponent(false);
        when(policy.evaluate(any(), any(), anyBoolean())).thenReturn(FirewallDecision.blocked(
                POLICY_ID, "Default", List.of(blockingViolation())));
        when(recorder.recordDecision(any(), any()))
                .thenThrow(new IllegalStateException("constraint violation"));

        FirewallEvaluation verdict = service().evaluate(REPO_ID, REPO, PATH, CONTEXT);

        assertThat(verdict.blocked()).isTrue();
    }

    private void givenEnforcing() {
        givenEnforcing(FirewallFailMode.FAIL_OPEN);
    }

    private void givenEnforcing(FirewallFailMode failMode) {
        when(settings.enforcementEnabled()).thenReturn(true);
        when(settings.enforcingSince()).thenReturn(Instant.parse("2026-08-01T09:00:00Z"));
        when(evaluation.resolveSettings(REPO_ID)).thenReturn(quarantine(failMode));
    }

    private void givenMatchedComponent(boolean preExisting) {
        when(evaluation.inspect(any(), anyString(), anyString(), any(), any()))
                .thenReturn(new FirewallEvaluation(
                        REPO_ID, REPO, PATH, quarantine(FirewallFailMode.FAIL_OPEN),
                        identity(), List.of(finding()), FirewallEvaluation.Outcome.MATCHED,
                        preExisting, FirewallDecision.notEvaluated()));
    }

    private FirewallEnforcementService service() {
        return service(new DirectExecutorService());
    }

    private FirewallEnforcementService service(ExecutorService executor) {
        return new FirewallEnforcementService(
                evaluation, policy, settings, recorder,
                new FirewallEnforcementProperties(
                        true, Duration.ofMillis(50), Duration.ofSeconds(10), 4, 200),
                executor,
                false);
    }

    private static FirewallRepositorySettings quarantine(FirewallFailMode failMode) {
        return new FirewallRepositorySettings(FirewallMode.QUARANTINE, failMode, POLICY_ID, true);
    }

    private static FirewallRuleViolation blockingViolation() {
        return new FirewallRuleViolation(
                FirewallRuleType.CVSS_THRESHOLD, FirewallAction.BLOCK,
                "CVSS 10 is at or above the configured threshold of 9",
                List.of("GHSA-jfh8-c2jp-5v3q"));
    }

    private static ComponentIdentity identity() {
        try {
            return new ComponentIdentity.Purl(new PackageURL(
                    "maven", "org.apache.logging.log4j", "log4j-core", "2.14.1", null, null));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static AdvisoryFinding finding() {
        return new AdvisoryFinding(
                "GHSA-jfh8-c2jp-5v3q", "RCE", "CRITICAL", 10.0, null, null, null,
                List.of(new AdvisoryMatch(
                        "GHSA-jfh8-c2jp-5v3q", "GHSA", MatchConfidence.EXACT, ">=2.0, <2.15.0")));
    }

    /** Runs everything on the calling thread, so assertions need no waiting. */
    private static final class DirectExecutorService extends AbstractExecutorService {

        private volatile boolean shutdown;

        @Override
        public void execute(Runnable command) {
            command.run();
        }

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }
    }

    /** Accepts work and never runs it — the "the verdict is late" case. */
    private static final class StalledExecutorService extends AbstractExecutorService {

        @Override
        public void execute(Runnable command) {
            // Deliberately dropped: the caller's Future never completes.
        }

        @Override
        public void shutdown() {}

        @Override
        public List<Runnable> shutdownNow() {
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }
    }

    /** Refuses work — the "backlog full" case. */
    private static final class RejectingExecutorService extends AbstractExecutorService {

        @Override
        public void execute(Runnable command) {
            throw new RejectedExecutionException("backlog full");
        }

        @Override
        public void shutdown() {}

        @Override
        public List<Runnable> shutdownNow() {
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }
    }
}
