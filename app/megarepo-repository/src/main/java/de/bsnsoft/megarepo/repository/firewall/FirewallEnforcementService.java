package de.bsnsoft.megarepo.repository.firewall;

import de.bsnsoft.megarepo.core.firewall.FirewallMode;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The enforcement path: the one place in MegaRepo that can refuse to hand over
 * an artifact because of what an advisory says about it.
 *
 * <h2>When it does anything at all</h2>
 *
 * Both switches have to be on:
 *
 * <ol>
 *   <li>the global {@link FirewallEnforcementSettingsService} switch, which
 *       ships off, and</li>
 *   <li>the repository's mode, which has to be {@code QUARANTINE}.</li>
 * </ol>
 *
 * Anything else returns {@link FirewallEvaluation.Outcome#NOT_ENFORCING}
 * immediately — no query, no thread hand-off, no measurable cost — and the
 * caller falls through to the observation path exactly as before. That is what
 * makes this change invisible to an installation that upgrades into it.
 *
 * <h2>What it costs a download that is enforced</h2>
 *
 * Four indexed reads against the local database: the repository's firewall
 * config, the asset, its component, and the advisories naming it, plus the
 * policy and its rules. No network — advisory feeds are mirrored by a background
 * task and this path only reads the mirror, which is the customer's explicit
 * rule. The work runs on a small dedicated pool and the request waits at most
 * {@link FirewallEnforcementProperties#evaluationTimeout()} for it.
 *
 * <h2>When it cannot answer</h2>
 *
 * A timeout, a rejected task (pool saturated) or an exception all produce
 * {@link FirewallEvaluation.Outcome#UNAVAILABLE}, and then the repository's
 * {@code fail_mode} decides: {@code FAIL_OPEN} serves, {@code FAIL_CLOSED}
 * denies. The default for a repository with no explicit row is
 * {@code FAIL_OPEN}, because an unavailable firewall breaking every build is a
 * worse failure than a vulnerable artifact slipping through one.
 *
 * <p>The one thing that never happens is a download failing for a reason other
 * than a deliberate verdict. Every entry point catches {@link RuntimeException}
 * and answers "serve": if the firewall is broken in a way its own fail mode did
 * not anticipate, it gets out of the way.
 *
 * <h2>What it does not do</h2>
 *
 * It does not quarantine. A denied artifact is not served, but for a proxy
 * repository it has already been fetched and cached by the format handler by the
 * time this runs — there is no state machine holding it in a separate place, and
 * no release/approve workflow. Subsequent requests for it are denied again by
 * the same evaluation rather than by a stored quarantine state.
 */
@Service
public class FirewallEnforcementService {

    private static final Logger log = LoggerFactory.getLogger(FirewallEnforcementService.class);

    /** Unavailable verdicts are logged on the first one, then every this many. */
    private static final long UNAVAILABLE_LOG_INTERVAL = 100;

    private final FirewallEvaluationService evaluation;
    private final FirewallPolicyEvaluator policy;
    private final FirewallEnforcementSettingsService settings;
    private final FirewallViolationRecorder recorder;
    private final FirewallEnforcementProperties properties;
    private final ExecutorService executor;
    private final boolean ownsExecutor;
    private final AtomicLong unavailable = new AtomicLong();

    @Autowired
    public FirewallEnforcementService(
            FirewallEvaluationService evaluation,
            FirewallPolicyEvaluator policy,
            FirewallEnforcementSettingsService settings,
            FirewallViolationRecorder recorder,
            FirewallEnforcementProperties properties) {
        this(evaluation, policy, settings, recorder, properties, defaultExecutor(properties), true);
    }

    /**
     * Visible for tests, which supply a direct executor so an assertion does not
     * have to wait for a pool — or a deliberately slow one to exercise the
     * timeout.
     */
    FirewallEnforcementService(
            FirewallEvaluationService evaluation,
            FirewallPolicyEvaluator policy,
            FirewallEnforcementSettingsService settings,
            FirewallViolationRecorder recorder,
            FirewallEnforcementProperties properties,
            ExecutorService executor,
            boolean ownsExecutor) {
        this.evaluation = evaluation;
        this.policy = policy;
        this.settings = settings;
        this.recorder = recorder;
        this.properties = properties;
        this.executor = executor;
        this.ownsExecutor = ownsExecutor;
    }

    /**
     * Decides whether one artifact may be served, and records the decision.
     *
     * <p>Called before the response is written, which is the only point at which
     * a refusal is still possible. Never throws.
     *
     * @param repositoryId repository the artifact would be served from
     * @param repositoryName its name, for the audit trail and the 403 body
     * @param path artifact path within the repository
     * @param context who is asking
     * @return the evaluation; {@link FirewallEvaluation#blocked()} is the answer,
     *     and {@link FirewallEvaluation#enforcementEvaluated()} tells the caller
     *     whether the observation path still has to run for this download
     */
    public FirewallEvaluation evaluate(
            UUID repositoryId, String repositoryName, String path, FirewallRequestContext context) {

        FirewallRepositorySettings repositorySettings =
                FirewallRepositorySettings.fallback(FirewallMode.OFF);
        try {
            if (repositoryId == null || path == null || !settings.enforcementEnabled()) {
                return notEnforcing(repositoryId, repositoryName, path, repositorySettings);
            }

            repositorySettings = evaluation.resolveSettings(repositoryId);
            if (!repositorySettings.enforces()) {
                // AUDIT and OFF are the observation path's business. Returning
                // here rather than evaluating and discarding keeps enforcement
                // off the critical path of every non-enforcing repository.
                return notEnforcing(repositoryId, repositoryName, path, repositorySettings);
            }

            FirewallEvaluation decided = decideWithin(repositoryId, repositoryName, path, repositorySettings);
            recordQuietly(decided, context);
            return decided;

        } catch (RuntimeException e) {
            // Not a fail-mode case: the fail mode covers "no verdict in time",
            // this covers "the firewall is broken in a way nobody planned for".
            // A defect here must not cost a client its artifact.
            log.warn("Repository firewall enforcement failed for {}/{} — the download was served",
                    repositoryName, path, e);
            return notEnforcing(repositoryId, repositoryName, path, repositorySettings)
                    .withOutcome(FirewallEvaluation.Outcome.FAILED);
        }
    }

    /**
     * Runs the evaluation on the pool and waits for it, no longer than the
     * configured budget.
     *
     * <p>Off-thread rather than inline so that "the firewall took too long" is
     * something the request path can actually observe. A query that blocks on a
     * lock does not become interruptible by being wrapped in a timer, but it
     * does become something this method can walk away from.
     */
    private FirewallEvaluation decideWithin(
            UUID repositoryId, String repositoryName, String path, FirewallRepositorySettings repositorySettings) {

        Instant watermark = preExistingWatermark();
        Future<FirewallEvaluation> pending;
        try {
            pending = executor.submit(
                    () -> decide(repositoryId, repositoryName, path, repositorySettings, watermark));
        } catch (RejectedExecutionException e) {
            return unavailable(repositoryId, repositoryName, path, repositorySettings,
                    "the evaluation backlog is full");
        }

        try {
            return pending.get(properties.evaluationTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pending.cancel(true);
            return unavailable(repositoryId, repositoryName, path, repositorySettings,
                    "the evaluation did not finish within " + properties.evaluationTimeout());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pending.cancel(true);
            return unavailable(repositoryId, repositoryName, path, repositorySettings,
                    "the request was interrupted");
        } catch (java.util.concurrent.ExecutionException e) {
            log.warn("Repository firewall evaluation of {}/{} threw", repositoryName, path, e.getCause());
            return unavailable(repositoryId, repositoryName, path, repositorySettings,
                    "the evaluation failed");
        }
    }

    /** The read-only half: identify, look up, apply the policy. Runs off the request thread. */
    private FirewallEvaluation decide(
            UUID repositoryId,
            String repositoryName,
            String path,
            FirewallRepositorySettings repositorySettings,
            Instant watermark) {

        FirewallEvaluation inspection =
                evaluation.inspect(repositoryId, repositoryName, path, repositorySettings, watermark);

        if (inspection.outcome() != FirewallEvaluation.Outcome.MATCHED) {
            // No component, no usable identity, or no advisory names it. None of
            // those is a policy question with the rules this build implements —
            // UNKNOWN_COMPONENT would be, and it is not implemented, so an
            // unidentifiable artifact is served rather than denied on a rule
            // nobody wrote.
            return inspection.withDecision(FirewallDecision.allowed());
        }

        FirewallDecision decision =
                policy.evaluate(repositorySettings, inspection.findings(), inspection.preExisting());
        return inspection.withDecision(decision);
    }

    /**
     * The instant before which a stored artifact counts as "already in the
     * repository".
     *
     * <p>Normally the moment enforcement was first switched on. Before that has
     * been stamped — a node that has not managed to read its settings row yet —
     * "now" is used, which grandfathers everything and therefore blocks nothing.
     * The customer's rule is that already-present components are audited and
     * never blocked; erring towards "already present" is the only direction that
     * cannot break a build.
     */
    private Instant preExistingWatermark() {
        Instant since = settings.enforcingSince();
        return since == null ? Instant.now() : since;
    }

    private FirewallEvaluation unavailable(
            UUID repositoryId,
            String repositoryName,
            String path,
            FirewallRepositorySettings repositorySettings,
            String why) {

        boolean failClosed = repositorySettings.failsClosed();
        long count = unavailable.incrementAndGet();
        if (count == 1 || count % UNAVAILABLE_LOG_INTERVAL == 0) {
            log.warn("Repository firewall could not reach a verdict for {}/{} ({}); "
                            + "fail mode {} — the download was {}. {} unavailable verdicts so far.",
                    repositoryName, path, why, repositorySettings.failMode(),
                    failClosed ? "DENIED" : "served", count);
        }
        return new FirewallEvaluation(
                repositoryId, repositoryName, path, repositorySettings, null, List.of(),
                FirewallEvaluation.Outcome.UNAVAILABLE, false,
                FirewallDecision.unavailable(failClosed));
    }

    private static FirewallEvaluation notEnforcing(
            UUID repositoryId,
            String repositoryName,
            String path,
            FirewallRepositorySettings repositorySettings) {
        return new FirewallEvaluation(
                repositoryId, repositoryName, path, repositorySettings, null, List.of(),
                FirewallEvaluation.Outcome.NOT_ENFORCING, false, FirewallDecision.notEvaluated());
    }

    /**
     * Writes the decision to {@code firewall_violation} without the download
     * waiting for it.
     *
     * <p>Off the request thread on purpose: the verdict is already made, and the
     * client should not pay for the log write — nor should a failing write be
     * able to change an answer that has already been given.
     *
     * <p>An {@code UNAVAILABLE} verdict writes nothing: there is no component to
     * key a row on ({@code firewall_violation.purl} is NOT NULL and the
     * evaluation never got as far as an identity), and inventing a placeholder
     * would put rows into the component-keyed log that name no component. It is
     * logged instead, in {@link #unavailable}.
     */
    private void recordQuietly(FirewallEvaluation decided, FirewallRequestContext context) {
        if (!decided.hasFindings() && decided.decision().violations().isEmpty()) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    recorder.recordDecision(decided, context);
                } catch (RuntimeException e) {
                    log.warn("Could not record the firewall decision for {}/{}",
                            decided.repositoryName(), decided.path(), e);
                }
            });
        } catch (RuntimeException e) {
            log.debug("Firewall decision for {}/{} was not scheduled for recording",
                    decided.repositoryName(), decided.path(), e);
        }
    }

    /** How many downloads got an "unavailable" verdict. Diagnostics and tests. */
    public long unavailableCount() {
        return unavailable.get();
    }

    @PreDestroy
    void shutdown() {
        if (!ownsExecutor) {
            return;
        }
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                log.debug("Firewall enforcement pool did not terminate within 5s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static ExecutorService defaultExecutor(FirewallEnforcementProperties properties) {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                properties.threads(),
                properties.threads(),
                60,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(properties.queueCapacity()),
                daemonThreadFactory());
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    private static ThreadFactory daemonThreadFactory() {
        AtomicLong counter = new AtomicLong();
        return runnable -> {
            Thread thread = new Thread(runnable, "firewall-enforce-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
