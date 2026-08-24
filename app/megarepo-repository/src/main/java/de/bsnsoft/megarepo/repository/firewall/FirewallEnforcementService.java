package de.bsnsoft.megarepo.repository.firewall;

import de.bsnsoft.megarepo.core.firewall.FirewallMode;
import de.bsnsoft.megarepo.core.firewall.FirewallQuarantineReason;
import de.bsnsoft.megarepo.core.repository.RepositoryType;
import de.bsnsoft.megarepo.repository.firewall.facts.ComponentFacts;
import de.bsnsoft.megarepo.repository.firewall.facts.ComponentFactsService;
import de.bsnsoft.megarepo.repository.firewall.identity.ComponentIdentity;
import de.bsnsoft.megarepo.repository.firewall.quarantine.FirewallQuarantineEntry;
import de.bsnsoft.megarepo.repository.firewall.quarantine.QuarantineService;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRuleContext;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
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
 * an artifact because of what the policy says about it.
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
 * <h2>The order the decision is assembled in</h2>
 *
 * <ol>
 *   <li><b>An existing quarantine entry short-circuits everything.</b> One
 *       indexed read answers a repeated request for a held component: still held
 *       or blocked ⇒ refuse and count the hit; released ⇒ serve, <em>without</em>
 *       running the rules again. Re-deriving the verdict would let an unchanged
 *       policy overturn an operator who deliberately released something, and it
 *       would make every download of a held artifact pay for a full evaluation.</li>
 *   <li>Otherwise the component is identified, its advisories and its local facts
 *       are read once, and {@link FirewallPolicyEvaluator} runs every configured
 *       rule against them — exemptions included.</li>
 *   <li>A decision to hold is written to the queue here, because this is the side
 *       that has the request context and the off-thread executor. The evaluator
 *       stays read-only.</li>
 * </ol>
 *
 * <h2>What it costs a download that is enforced</h2>
 *
 * Local indexed reads only: the repository's firewall config, the asset, its
 * component, the advisories naming it, the component's facts row, the quarantine
 * entry, the policy and its rules, plus one exemption lookup per blocking rule
 * that actually matched. No network — advisory feeds and package metadata are
 * mirrored by background tasks and this path only reads the mirrors, which is the
 * customer's explicit rule. The work runs on a small dedicated pool and the
 * request waits at most
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
 * <p>That is distinct from a <em>rule</em> that cannot decide. A rule reporting
 * {@code INDETERMINATE} — {@code MIN_AGE} with no publication date yet — is not a
 * fault: the data is expected to arrive. Fail-closed holds such a component under
 * {@link FirewallQuarantineReason#EVALUATION_INCOMPLETE} so the sweep can release
 * it by itself; fail-open serves it. Both are decided by
 * {@link FirewallPolicyEvaluator}, which is where the fail mode is in scope.
 *
 * <p>The one thing that never happens is a download failing for a reason other
 * than a deliberate verdict. Every entry point catches {@link RuntimeException}
 * and answers "serve": if the firewall is broken in a way its own fail mode did
 * not anticipate, it gets out of the way.
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
    private final QuarantineService quarantine;
    private final ObjectProvider<ComponentFactsService> facts;
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
            QuarantineService quarantine,
            ObjectProvider<ComponentFactsService> facts,
            FirewallEnforcementProperties properties) {
        this(evaluation, policy, settings, recorder, quarantine, facts, properties,
                defaultExecutor(properties), true);
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
            QuarantineService quarantine,
            ObjectProvider<ComponentFactsService> facts,
            FirewallEnforcementProperties properties,
            ExecutorService executor,
            boolean ownsExecutor) {
        this.evaluation = evaluation;
        this.policy = policy;
        this.settings = settings;
        this.recorder = recorder;
        this.quarantine = quarantine;
        this.facts = facts;
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
        return evaluate(repositoryId, repositoryName, null, path, context);
    }

    /**
     * The same, told what kind of repository resolved the artifact.
     *
     * <p>{@code TYPOSQUAT} and {@code NAMESPACE_CONFUSION} both turn on it — the
     * whole finding of the latter is "this internal-looking coordinate arrived
     * from the internet" — and the router already holds the
     * {@link de.bsnsoft.megarepo.core.repository.RepositoryConfig}, so passing it
     * costs nothing where looking it up again would cost a query on every
     * enforced download. A null type simply leaves those rules unable to match,
     * which is the safe direction for a heuristic.
     */
    public FirewallEvaluation evaluate(
            UUID repositoryId,
            String repositoryName,
            RepositoryType repositoryType,
            String path,
            FirewallRequestContext context) {

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

            FirewallEvaluation decided = decideWithin(
                    repositoryId, repositoryName, repositoryType, path, repositorySettings, context);
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
            UUID repositoryId,
            String repositoryName,
            RepositoryType repositoryType,
            String path,
            FirewallRepositorySettings repositorySettings,
            FirewallRequestContext context) {

        Instant watermark = preExistingWatermark();
        Future<FirewallEvaluation> pending;
        try {
            pending = executor.submit(() -> decide(
                    repositoryId, repositoryName, repositoryType, path, repositorySettings,
                    watermark, context));
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

    /** Identify, short-circuit on the queue, apply the policy. Runs off the request thread. */
    private FirewallEvaluation decide(
            UUID repositoryId,
            String repositoryName,
            RepositoryType repositoryType,
            String path,
            FirewallRepositorySettings repositorySettings,
            Instant watermark,
            FirewallRequestContext context) {

        FirewallEvaluation inspection =
                evaluation.inspect(repositoryId, repositoryName, path, repositorySettings, watermark);

        ComponentIdentity identity = inspection.identity();
        if (identity == null) {
            // No asset row, or an asset attached to no component: checksums,
            // metadata, index pages. There is no component for a policy to have
            // an opinion about — not even UNKNOWN_COMPONENT, whose subject is a
            // component whose coordinates could not be resolved, not the absence
            // of one.
            return inspection.withDecision(FirewallDecision.allowed());
        }

        Optional<FirewallDecision> shortCircuit = decidedEarlier(inspection);
        if (shortCircuit.isPresent()) {
            return inspection.withDecision(shortCircuit.get());
        }

        FirewallRuleContext ruleContext = new FirewallRuleContext(
                repositoryId,
                repositoryName,
                repositoryType,
                path,
                identity,
                inspection.findings(),
                lookupFacts(identity),
                repositorySettings,
                false,
                inspection.preExisting(),
                Instant.now());

        FirewallDecision decision = policy.evaluate(ruleContext);
        if (decision.held()) {
            decision = hold(inspection.withDecision(decision), decision, context);
        }
        return inspection.withDecision(decision);
    }

    /**
     * The quarantine short-circuit.
     *
     * <p>Returns a decision when there is a live entry for this component, and
     * empty when there is not — which includes a quarantine store that could not
     * be read, because {@link QuarantineService#find} answers empty rather than
     * throwing and a database hiccup must not do what no policy asked for.
     *
     * <p>A {@code RELEASED} entry is served <em>without</em> the rules running.
     * That is deliberate: the release is somebody's decision or the sweep's, and
     * re-deriving it on every download would either overturn it or make it
     * meaningless. A {@code BLOCKED} entry is refused for the same reason in the
     * other direction.
     */
    private Optional<FirewallDecision> decidedEarlier(FirewallEvaluation inspection) {
        Optional<FirewallQuarantineEntry> existing =
                quarantine.find(inspection.repositoryId(), inspection.componentKey());
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        FirewallQuarantineEntry entry = existing.get();
        FirewallDecision.Hold hold = new FirewallDecision.Hold(
                entry.id(), entry.state(), entry.reason(), entry.nextEvaluationAt(), entry.hitCount());

        if (!entry.denies()) {
            log.debug("Quarantine entry {} for {} is {} — serving on that decision",
                    entry.id(), entry.componentKey(), entry.state());
            return Optional.of(FirewallDecision.releasedFromQuarantine(hold));
        }
        quarantine.recordHit(entry.id(), Instant.now());
        return Optional.of(FirewallDecision.quarantined(
                entry.policyId(), null, List.of(), hold));
    }

    /**
     * Writes the queue entry behind a decision to hold, and folds the stored entry
     * back into the decision so the 403 can say when it will be looked at again.
     *
     * <p>An empty answer from {@link QuarantineService#quarantine} does not soften
     * the verdict. It means "no entry was written" — quarantine is switched off,
     * or the component could not be keyed — and the refusal is the policy's, not
     * the queue's. An enforcing repository with quarantine disabled therefore
     * still refuses; it simply has no queue in between, which is exactly what
     * disabling it is for.
     */
    private FirewallDecision hold(
            FirewallEvaluation evaluated, FirewallDecision decision, FirewallRequestContext context) {

        FirewallQuarantineReason reason = decision.hold() == null
                ? FirewallQuarantineReason.EVALUATION_INCOMPLETE
                : decision.hold().reason();
        try {
            return quarantine.quarantine(evaluated, reason, context)
                    .map(entry -> decision.withHold(new FirewallDecision.Hold(
                            entry.id(), entry.state(), entry.reason(),
                            entry.nextEvaluationAt(), entry.hitCount())))
                    .orElse(decision);
        } catch (RuntimeException e) {
            log.warn("Could not record the quarantine entry for {}/{} — the download was still refused",
                    evaluated.repositoryName(), evaluated.path(), e);
            return decision;
        }
    }

    /**
     * The component's locally cached facts.
     *
     * <p>One primary-key read against {@code firewall_component_facts}, which
     * never fetches: a miss answers {@code UNKNOWN} and the rules that need the
     * fact report {@code INDETERMINATE}. That is the whole reason the table
     * exists — reading package metadata on a request thread is what the customer
     * forbade.
     *
     * <p>Resolution is <em>not</em> requested from here. The rules that need a
     * fact enqueue it themselves when they find it missing, which keeps the
     * request that pays for the enqueue the same one that noticed the gap, and
     * avoids queueing a lookup for every download in an instance whose policy
     * asks for no facts at all.
     */
    private ComponentFacts lookupFacts(ComponentIdentity identity) {
        ComponentFactsService service = facts.getIfAvailable();
        if (service == null) {
            return ComponentFacts.unknown(identity.key());
        }
        try {
            ComponentFacts looked = service.lookup(identity);
            return looked == null ? ComponentFacts.unknown(identity.key()) : looked;
        } catch (RuntimeException e) {
            log.debug("Component facts lookup failed for {}", identity.key(), e);
            return ComponentFacts.unknown(identity.key());
        }
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
     *
     * <p>Neither does a decision taken by the quarantine short-circuit: the queue
     * entry <em>is</em> the record of it, it already counts the hits, and writing
     * a violation row per download of a held component would flood the log with
     * the one thing that is already visible elsewhere.
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
