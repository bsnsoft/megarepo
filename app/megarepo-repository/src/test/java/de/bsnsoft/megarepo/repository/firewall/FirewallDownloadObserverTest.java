package de.bsnsoft.megarepo.repository.firewall;

import de.bsnsoft.megarepo.core.firewall.FirewallMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The request path's only contact with the firewall. Everything asserted here is
 * a promise the router relies on: the call returns, it returns nothing, and it
 * cannot throw.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FirewallDownloadObserverTest {

    private static final UUID REPO_ID = UUID.randomUUID();
    private static final String PATH = "org/acme/lib/1.0/lib-1.0.jar";
    private static final FirewallRequestContext CONTEXT =
            new FirewallRequestContext("ci", "10.0.0.1", PATH, "GET");

    /** Runs the submitted work immediately, so assertions do not race a pool. */
    private static final Executor SAME_THREAD = Runnable::run;

    @Mock private FirewallEvaluationService evaluation;

    @Test
    @DisplayName("an observed download reaches the evaluation")
    void observationIsForwarded() {
        FirewallDownloadObserver observer = observer(properties(true, Duration.ZERO), SAME_THREAD);

        observer.observeDownload(REPO_ID, "maven-central", PATH, CONTEXT);

        verify(evaluation).evaluateDownload(eq(REPO_ID), eq("maven-central"), eq(PATH), eq(CONTEXT));
    }

    @Test
    @DisplayName("an evaluation that throws does not escape to the caller")
    void evaluationFailureNeverEscapes() {
        when(evaluation.evaluateDownload(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("everything is on fire"));
        FirewallDownloadObserver observer = observer(properties(true, Duration.ZERO), SAME_THREAD);

        assertThatCode(() -> observer.observeDownload(REPO_ID, "maven-central", PATH, CONTEXT))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an executor that refuses work is counted, not propagated")
    void rejectedSubmissionIsCounted() {
        Executor rejecting = task -> {
            throw new RejectedExecutionException("backlog full");
        };
        FirewallDownloadObserver observer = observer(properties(true, Duration.ZERO), rejecting);

        assertThatCode(() -> observer.observeDownload(REPO_ID, "maven-central", PATH, CONTEXT))
                .doesNotThrowAnyException();
        assertThat(observer.droppedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("the master switch stops the hook before it does anything")
    void disabledDoesNothing() {
        FirewallDownloadObserver observer = observer(properties(false, Duration.ZERO), SAME_THREAD);

        observer.observeDownload(REPO_ID, "maven-central", PATH, CONTEXT);

        verifyNoInteractions(evaluation);
    }

    @Test
    @DisplayName("a hot artifact is evaluated once per interval, not once per request")
    void repeatedDownloadsAreThrottled() {
        FirewallDownloadObserver observer =
                observer(properties(true, Duration.ofMinutes(10)), SAME_THREAD);

        for (int i = 0; i < 50; i++) {
            observer.observeDownload(REPO_ID, "maven-central", PATH, CONTEXT);
        }

        verify(evaluation, times(1)).evaluateDownload(any(), any(), any(), any());
    }

    @Test
    @DisplayName("a missing repository id or path is ignored rather than passed on")
    void incompleteInputIsIgnored() {
        FirewallDownloadObserver observer = observer(properties(true, Duration.ZERO), SAME_THREAD);

        observer.observeDownload(null, "maven-central", PATH, CONTEXT);
        observer.observeDownload(REPO_ID, "maven-central", null, CONTEXT);

        verify(evaluation, never()).evaluateDownload(any(), any(), any(), any());
    }

    @Test
    @DisplayName("the observation does not run on the calling thread")
    void workIsHandedOff() {
        List<Runnable> submitted = new java.util.ArrayList<>();
        FirewallDownloadObserver observer =
                observer(properties(true, Duration.ZERO), submitted::add);

        observer.observeDownload(REPO_ID, "maven-central", PATH, CONTEXT);

        verifyNoInteractions(evaluation);
        assertThat(submitted).as("the caller only queued the work").hasSize(1);
        submitted.get(0).run();
        verify(evaluation).evaluateDownload(any(), any(), any(), any());
    }

    private FirewallDownloadObserver observer(FirewallAuditProperties properties, Executor executor) {
        return new FirewallDownloadObserver(evaluation, properties, executor);
    }

    private static FirewallAuditProperties properties(boolean enabled, Duration reevaluationInterval) {
        return new FirewallAuditProperties(
                enabled, FirewallMode.AUDIT, 2, 500,
                Duration.ofHours(24), reevaluationInterval, 10_000);
    }
}
