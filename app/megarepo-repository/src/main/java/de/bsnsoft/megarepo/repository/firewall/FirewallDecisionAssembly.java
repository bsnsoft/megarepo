package de.bsnsoft.megarepo.repository.firewall;

import de.bsnsoft.megarepo.core.firewall.FirewallQuarantineReason;
import de.bsnsoft.megarepo.core.repository.RepositoryType;
import de.bsnsoft.megarepo.repository.firewall.facts.ComponentFacts;
import de.bsnsoft.megarepo.repository.firewall.facts.ComponentFactsService;
import de.bsnsoft.megarepo.repository.firewall.identity.ComponentIdentity;
import de.bsnsoft.megarepo.repository.firewall.quarantine.FirewallQuarantineEntry;
import de.bsnsoft.megarepo.repository.firewall.quarantine.QuarantineService;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRuleContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The verdict, assembled once for both directions an artifact can travel.
 *
 * <h2>Why this class exists</h2>
 *
 * It exists because it did not, and the bug that produced it (osTicket #155155)
 * is the shape that always follows: the download path consulted the exemptions
 * and the upload path did not, so an operator could approve an exemption, watch
 * the component download, and watch the very same component be refused on
 * publish into the very same repository. That is not a missing feature in the
 * upload gate — copying the exemption lookup into it would have fixed today's
 * symptom and left the cause in place. The cause was two hand-assembled
 * decisions that had to be kept in step by whoever remembered.
 *
 * <p>So the assembly lives here, in one place, and both request paths call it.
 * Everything a verdict is made of — the quarantine short-circuit, the component's
 * locally cached facts, the rule context, {@link FirewallPolicyEvaluator} (which
 * owns the policy, the exemptions, the fail mode and the grandfathering rule),
 * and the queue entry a hold produces — happens here and nowhere else. A rule
 * that changes changes for both directions on the same line.
 *
 * <h2>What is left to the caller, and why</h2>
 *
 * The two paths differ in four things, and all four are outside this class:
 *
 * <ol>
 *   <li><b>Where the inspection comes from.</b> A download reads the stored asset
 *       and its component ({@link FirewallEvaluationService#inspect}); a publish
 *       is handed the {@link de.bsnsoft.megarepo.database.entity.ComponentEntity}
 *       the format handler has just written, because only the format module knows
 *       how to read coordinates out of an upload.</li>
 *   <li><b>The direction.</b> {@code upload} is passed straight into
 *       {@link FirewallRuleContext#upload()}: refusing to publish something is
 *       not the same act as refusing to serve it, and a rule may reasonably read
 *       differently in the two directions.</li>
 *   <li><b>Timing.</b> A download runs this on the enforcement pool under a
 *       timeout; a publish runs it inline. That is deliberate — a publish has
 *       already paid for a body transfer and a blob write, and letting a
 *       saturated pool turn into a refused release on a fail-closed repository
 *       would deny an artifact for a reason that has nothing to do with it.</li>
 *   <li><b>What a refusal does.</b> A download is simply not written; a publish
 *       has to be retracted through the format handler's own delete before the
 *       403 goes out. {@link de.bsnsoft.megarepo.repository.RepositoryRouter}
 *       owns that, and remains the only owner of the router wiring.</li>
 * </ol>
 *
 * <p>Grandfathering is <em>not</em> on that list. It reads oddly for a publish —
 * "this component was already here" and "this component is being written" sound
 * like opposites — but the path being re-published is the same path a download
 * would have been grandfathered on, and the customer's rule is about the
 * operator's switch, not about the verb: arming the firewall may not turn a
 * release job that worked yesterday into a failing one. Both directions therefore
 * answer it the same way, from the same watermark.
 *
 * <h2>It writes one thing</h2>
 *
 * The quarantine entry behind a decision to hold, because the evaluator is
 * read-only by construction and somebody has to have the request context. Nothing
 * else here writes; the violation log is the caller's, since a download records
 * it off-thread after the bytes are gone and a publish records it inline while
 * the client is still waiting.
 */
@Service
public class FirewallDecisionAssembly {

    private static final Logger log = LoggerFactory.getLogger(FirewallDecisionAssembly.class);

    private final FirewallPolicyEvaluator policy;
    private final QuarantineService quarantine;
    private final ObjectProvider<ComponentFactsService> facts;

    public FirewallDecisionAssembly(
            FirewallPolicyEvaluator policy,
            QuarantineService quarantine,
            ObjectProvider<ComponentFactsService> facts) {
        this.policy = policy;
        this.quarantine = quarantine;
        this.facts = facts;
    }

    /**
     * Decides about one component, in whichever direction it is travelling.
     *
     * <p>Does not catch: both callers already treat "the firewall threw" as their
     * own fail-safe answer — serve the download, keep the publish — and swallowing
     * here would take the choice away from the only two places that know which
     * one applies.
     *
     * @param inspection what is known about the component before any judgement:
     *     repository, path, settings, identity, advisories, and whether it was
     *     already stored before enforcement was switched on. Its
     *     {@link FirewallEvaluation#decision()} is ignored and replaced
     * @param repositoryType the repository that holds (or will hold) the
     *     component. {@code TYPOSQUAT} and {@code NAMESPACE_CONFUSION} turn on it
     * @param upload true for a publish, false for a download
     * @param request who is asking, for the quarantine entry's snapshot
     * @return the same inspection with a verdict attached; never null
     */
    public FirewallEvaluation decide(
            FirewallEvaluation inspection,
            RepositoryType repositoryType,
            boolean upload,
            FirewallRequestContext request) {

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
                inspection.repositoryId(),
                inspection.repositoryName(),
                repositoryType,
                inspection.path(),
                identity,
                inspection.findings(),
                lookupFacts(identity),
                inspection.settings(),
                upload,
                inspection.preExisting(),
                Instant.now());

        FirewallDecision decision = policy.evaluate(ruleContext);
        if (decision.held()) {
            decision = hold(inspection.withDecision(decision), decision, request);
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
     * re-deriving it on every request would either overturn it or make it
     * meaningless. A {@code BLOCKED} entry is refused for the same reason in the
     * other direction.
     *
     * <p>It applies to a publish for exactly the same reason it applies to a
     * download. An operator who released a held component and then watched the
     * publisher's retry be refused anyway would be reading the same contradiction
     * this class was written to remove — and the queue's own store already agrees:
     * {@link QuarantineService#quarantine} refuses to re-hold a decided entry, so
     * without this the upload path would refuse a component nothing would have
     * held.
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
            log.debug("Quarantine entry {} for {} is {} — answering on that decision",
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
     *
     * <p>Holding a <em>publish</em> holds the component, not stored bytes: nothing
     * was published, and the router has retracted what it wrote. When the entry is
     * released the publisher retries and succeeds, which is the same shape as a
     * held download becoming servable.
     */
    private FirewallDecision hold(
            FirewallEvaluation evaluated, FirewallDecision decision, FirewallRequestContext request) {

        FirewallQuarantineReason reason = decision.hold() == null
                ? FirewallQuarantineReason.EVALUATION_INCOMPLETE
                : decision.hold().reason();
        try {
            return quarantine.quarantine(evaluated, reason, request)
                    .map(entry -> decision.withHold(new FirewallDecision.Hold(
                            entry.id(), entry.state(), entry.reason(),
                            entry.nextEvaluationAt(), entry.hitCount())))
                    .orElse(decision);
        } catch (RuntimeException e) {
            log.warn("Could not record the quarantine entry for {}/{} — the verdict stands",
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
     * avoids queueing a lookup for every request in an instance whose policy asks
     * for no facts at all.
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
}
