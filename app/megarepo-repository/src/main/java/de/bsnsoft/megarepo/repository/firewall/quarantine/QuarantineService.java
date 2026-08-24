package de.bsnsoft.megarepo.repository.firewall.quarantine;

import de.bsnsoft.megarepo.core.firewall.FirewallQuarantineReason;
import de.bsnsoft.megarepo.repository.firewall.FirewallEvaluation;
import de.bsnsoft.megarepo.repository.firewall.FirewallRequestContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * The quarantine state machine.
 *
 * <h2>What is held, and what is simply refused</h2>
 *
 * Quarantine is rule-driven and never blanket — the customer said so, and the
 * distinction is the difference between a queue an operator works through and a
 * queue an operator stops reading. Three things put a component here, all of
 * them verdicts that are expected to change without anybody doing anything:
 *
 * <ul>
 *   <li>{@link FirewallQuarantineReason#MIN_AGE_NOT_MET} — it will be old enough
 *       at a time the policy already states;</li>
 *   <li>{@link FirewallQuarantineReason#UNKNOWN_COMPONENT} — the advisory data
 *       may yet arrive;</li>
 *   <li>{@link FirewallQuarantineReason#EVALUATION_INCOMPLETE} — the evaluation
 *       did not finish and the repository is fail-closed.</li>
 * </ul>
 *
 * <p>A critical advisory or a known-malicious package is refused outright and
 * produces no entry. Waiting does not make either acceptable, and offering a
 * "release" button next to a credential stealer is an invitation.
 *
 * <h2>Components that were already there</h2>
 *
 * Never quarantined. An artifact whose asset predates the moment enforcement was
 * switched on is audited and served, and {@link #quarantine} must return empty
 * for it rather than relying on the caller to check. That is the customer's
 * hardest constraint — switching enforcement on may not break a build that
 * worked yesterday — and it holds in one place or it holds nowhere.
 *
 * <h2>Switching it off</h2>
 *
 * The whole mechanism is disableable
 * ({@code megarepo.firewall.quarantine.enabled=false}). Disabled, {@link #quarantine}
 * records nothing and {@link #find} finds nothing, so an enforcing repository
 * refuses or serves on the policy alone with no queue in between. Existing rows
 * are left untouched — a disable is a change of behaviour, not a data migration.
 */
public interface QuarantineService {

    /**
     * The live entry for this component in this repository, if there is one.
     *
     * <p>Called on the request path before the policy is evaluated: a component
     * already decided about does not need deciding again, and this is what makes
     * a repeated download of a held artifact cost one indexed read instead of a
     * full evaluation.
     *
     * <p>Never throws. A quarantine store that is unreachable answers empty and
     * the evaluation proceeds as if the component had not been seen before.
     */
    Optional<FirewallQuarantineEntry> find(UUID repositoryId, String componentKey);

    /**
     * Records that a client asked for a component that is being held.
     *
     * <p>Separate from {@link #find} and deliberately fire-and-forget: the
     * counter is an operator signal, and a download that has already been refused
     * must not wait for a write, nor fail differently because one did.
     */
    void recordHit(UUID quarantineId, Instant seenAt);

    /**
     * Puts a component into quarantine, or refreshes the entry that is already
     * there.
     *
     * @param evaluation the decision that produced the hold, carrying the
     *     identity, the policy and the matched rules that go into the snapshot
     * @param reason which of the three triggers fired
     * @param context who asked for it
     * @return the entry, or empty when nothing was held — quarantine is disabled,
     *     the component predates enforcement, or it could not be identified well
     *     enough to key an entry on
     */
    Optional<FirewallQuarantineEntry> quarantine(
            FirewallEvaluation evaluation,
            FirewallQuarantineReason reason,
            FirewallRequestContext context);

    /**
     * Releases a held component so it may be served.
     *
     * @param quarantineId the entry
     * @param decision what happened and why — the machine-readable resolution
     *     plus the sentence an auditor reads
     * @return the updated entry
     * @throws IllegalStateException when the entry is not in a state this
     *     transition is valid from
     */
    FirewallQuarantineEntry release(UUID quarantineId, QuarantineDecision decision);

    /**
     * Moves a held component to {@code BLOCKED}: refused, and no longer
     * re-evaluated in the hope of a different answer.
     */
    FirewallQuarantineEntry block(UUID quarantineId, QuarantineDecision decision);

    /** The queue an operator reads. */
    Page<FirewallQuarantineEntry> queue(QuarantineQuery query, Pageable pageable);

    /** How many entries are in each state, for the admin overview. */
    QuarantineSummary summary();

    /**
     * Re-evaluates held entries whose next evaluation is due and releases the
     * ones that have become acceptable.
     *
     * <p>The automatic-release engine. Driven by the scheduled task (V19) and
     * called again right after an advisory sync, because that is the moment an
     * {@code UNKNOWN_COMPONENT} answer most often changes. Both entry points run
     * the same code — a release that only happens on one of the two paths is a
     * release nobody can predict.
     *
     * @param now the clock, passed so the sweep is testable without waiting
     * @param limit how many entries to look at in this pass
     * @return how many entries changed state
     */
    int reevaluateDue(Instant now, int limit);

    /**
     * Schedules every held entry decided by this policy for immediate
     * re-evaluation.
     *
     * <p>Called when a policy is edited or unassigned. Without it, loosening a
     * policy leaves components held for a rule that no longer exists until the
     * next sweep, and the operator who just fixed the policy watches the build
     * keep failing.
     *
     * @return how many entries were rescheduled
     */
    int invalidatePolicy(UUID policyId);

    /** Counts per state, for the admin overview and the UI badge. */
    record QuarantineSummary(long quarantined, long released, long blocked) {}
}
