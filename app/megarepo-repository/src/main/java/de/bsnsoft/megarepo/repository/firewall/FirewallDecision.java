package de.bsnsoft.megarepo.repository.firewall;

import de.bsnsoft.megarepo.core.firewall.FirewallQuarantineReason;
import de.bsnsoft.megarepo.core.firewall.FirewallQuarantineState;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;

/**
 * What the firewall decided about one download, and why.
 *
 * <p>This is the type that answers the question Phase 1 could not: whether the
 * bytes go out. It is produced only on the enforcement path; the observation
 * path attaches {@link #allowed()} and keeps its structural guarantee that it
 * cannot withhold anything.
 *
 * <h2>What Phase 2 adds</h2>
 *
 * Two outcomes that did not exist while a denied download was simply refused:
 *
 * <ul>
 *   <li>{@link Reason#QUARANTINED} — the component is <em>held</em>. Refused
 *       today, expected to become servable on its own, and visible in a queue
 *       with a stated reason and a stated next look. {@link #hold()} carries all
 *       three, because a 403 that says "quarantined" and nothing else tells a
 *       developer to open a ticket rather than to wait eleven minutes.</li>
 *   <li>{@link Reason#EXEMPTED} — a blocking rule matched and an approved
 *       exemption covers it. The violations are still here, each carrying the
 *       {@link FirewallRuleViolation#exemptionId()} that let it through.</li>
 * </ul>
 *
 * @param blocked whether the download must be denied
 * @param reason why the decision came out the way it did — the 403 body and the
 *     audit row both key on this rather than re-deriving it
 * @param policyId the policy that was evaluated, or null when none was
 * @param policyName its name, for the message a developer reads in a build log
 * @param violations every rule that matched, blocking or not, exempted or not
 * @param hold the quarantine entry behind a {@link Reason#QUARANTINED} or
 *     {@link Reason#QUARANTINE_RELEASED} decision, or null for every other one
 */
public record FirewallDecision(
        boolean blocked,
        Reason reason,
        UUID policyId,
        String policyName,
        List<FirewallRuleViolation> violations,
        Hold hold) {

    public FirewallDecision {
        violations = violations == null ? List.of() : List.copyOf(violations);
    }

    /**
     * The quarantine entry a decision refers to.
     *
     * @param quarantineId the entry, or null when the engine decided to hold but
     *     the entry has not been written yet — {@link #withHold} fills it in
     * @param state where the entry stands
     * @param reason why it is held
     * @param nextEvaluationAt when the sweep will look again. The single most
     *     useful thing a held download can be told: for a {@code MIN_AGE} entry
     *     it is the exact moment the component becomes old enough
     * @param hitCount how often the held component has been asked for
     */
    public record Hold(
            UUID quarantineId,
            FirewallQuarantineState state,
            FirewallQuarantineReason reason,
            Instant nextEvaluationAt,
            long hitCount) {

        public Hold {
            state = state == null ? FirewallQuarantineState.QUARANTINED : state;
        }

        /** A hold the engine has decided on but not yet written. */
        public static Hold pending(FirewallQuarantineReason reason) {
            return new Hold(null, FirewallQuarantineState.QUARANTINED, reason, null, 0);
        }
    }

    /** Why the firewall decided what it decided. */
    public enum Reason {

        /**
         * No decision was taken. Enforcement is off globally, or the repository
         * is not in QUARANTINE mode. The observation path handles the download.
         */
        NOT_EVALUATED,

        /** Evaluated, and no rule asked to block. */
        ALLOWED,

        /** At least one rule with action BLOCK matched. */
        POLICY,

        /**
         * A blocking rule matched and an approved exemption covers every one of
         * them, so the download was served. Which exemption did it is on the
         * violation.
         */
        EXEMPTED,

        /**
         * The component is held in quarantine — either because this evaluation
         * decided to hold it, or because an entry was already on file. Refused,
         * but with a stated reason that is expected to resolve itself.
         */
        QUARANTINED,

        /**
         * The component was held and has since been released, so it is served
         * without the rules being run again. The release is the decision; re-deriving
         * it on every download would let a policy that has not changed overturn an
         * operator who has.
         */
        QUARANTINE_RELEASED,

        /**
         * A blocking rule matched, but the component was already stored in the
         * repository before enforcement was switched on, so it was served.
         *
         * <p>The customer's rule: already-present components are audited, never
         * blocked retroactively — otherwise switching enforcement on breaks
         * every build that depends on something already cached.
         */
        PRE_EXISTING,

        /**
         * The firewall could not reach a verdict in time. Whether that denies
         * the download is the repository's {@code fail_mode}, not this class's
         * choice; {@link #blocked()} already reflects it.
         */
        EVALUATION_UNAVAILABLE
    }

    /** No decision taken — the observation path owns this download. */
    public static FirewallDecision notEvaluated() {
        return new FirewallDecision(false, Reason.NOT_EVALUATED, null, null, List.of(), null);
    }

    /** Evaluated and allowed, with whatever non-blocking rules matched. */
    public static FirewallDecision allowed(
            UUID policyId, String policyName, List<FirewallRuleViolation> violations) {
        return new FirewallDecision(false, Reason.ALLOWED, policyId, policyName, violations, null);
    }

    /** Evaluated and allowed, no policy involved. */
    public static FirewallDecision allowed() {
        return new FirewallDecision(false, Reason.ALLOWED, null, null, List.of(), null);
    }

    /**
     * Every blocking rule that matched is covered by an approved exemption.
     *
     * <p>A distinct reason rather than plain {@code ALLOWED} because the audit
     * trail has to be able to answer "what got through on an exemption, and
     * whose?" without joining the violation log against the exemption table by
     * timestamp.
     */
    public static FirewallDecision exempted(
            UUID policyId, String policyName, List<FirewallRuleViolation> violations) {
        return new FirewallDecision(false, Reason.EXEMPTED, policyId, policyName, violations, null);
    }

    /** A blocking rule matched and the download is denied. */
    public static FirewallDecision blocked(
            UUID policyId, String policyName, List<FirewallRuleViolation> violations) {
        return new FirewallDecision(true, Reason.POLICY, policyId, policyName, violations, null);
    }

    /**
     * The component is held: refused now, expected to resolve on its own.
     *
     * @param hold what is known about the entry. {@link Hold#pending} before it
     *     has been written; {@link #withHold} attaches the stored one afterwards
     */
    public static FirewallDecision quarantined(
            UUID policyId, String policyName, List<FirewallRuleViolation> violations, Hold hold) {
        return new FirewallDecision(true, Reason.QUARANTINED, policyId, policyName, violations, hold);
    }

    /** The component was held and has been released; it is served on that decision. */
    public static FirewallDecision releasedFromQuarantine(Hold hold) {
        return new FirewallDecision(
                false, Reason.QUARANTINE_RELEASED, null, null, List.of(), hold);
    }

    /** A blocking rule matched, but the component predates enforcement. */
    public static FirewallDecision preExisting(
            UUID policyId, String policyName, List<FirewallRuleViolation> violations) {
        return new FirewallDecision(
                false, Reason.PRE_EXISTING, policyId, policyName, violations, null);
    }

    /**
     * No verdict could be reached.
     *
     * @param failClosed the repository's {@code fail_mode}, which is what
     *     decides — fail-closed denies, fail-open serves
     */
    public static FirewallDecision unavailable(boolean failClosed) {
        return new FirewallDecision(
                failClosed, Reason.EVALUATION_UNAVAILABLE, null, null, List.of(), null);
    }

    /** The same decision with the stored quarantine entry attached. */
    public FirewallDecision withHold(Hold stored) {
        return new FirewallDecision(blocked, reason, policyId, policyName, violations, stored);
    }

    /** Whether a decision was taken at all. */
    public boolean evaluated() {
        return reason != Reason.NOT_EVALUATED;
    }

    /** Whether the repository's fail mode had to decide this one. */
    public boolean failModeApplied() {
        return reason == Reason.EVALUATION_UNAVAILABLE;
    }

    /** Whether the component is being held rather than plainly refused. */
    public boolean held() {
        return reason == Reason.QUARANTINED;
    }

    /**
     * The rules that actually withheld the artifact.
     *
     * <p>An exempted violation is not among them: it matched, it is recorded, and
     * it denied nothing. A 403 body listing it would name a rule the reader has
     * already been granted an exception from.
     */
    public List<FirewallRuleViolation> blockingViolations() {
        List<FirewallRuleViolation> blocking = new ArrayList<>();
        for (FirewallRuleViolation violation : violations) {
            if (violation.denies()) {
                blocking.add(violation);
            }
        }
        return blocking;
    }

    /** The violations an exemption let through, in the order they were evaluated. */
    public List<FirewallRuleViolation> exemptedViolations() {
        List<FirewallRuleViolation> exempted = new ArrayList<>();
        for (FirewallRuleViolation violation : violations) {
            if (violation.exempted()) {
                exempted.add(violation);
            }
        }
        return exempted;
    }

    /** The exemptions that suppressed a violation in this decision, de-duplicated. */
    public List<UUID> exemptionIds() {
        LinkedHashSet<UUID> ids = new LinkedHashSet<>();
        for (FirewallRuleViolation violation : violations) {
            if (violation.exemptionId() != null) {
                ids.add(violation.exemptionId());
            }
        }
        return List.copyOf(ids);
    }

    /** Every advisory id behind the matched rules, sorted and de-duplicated. */
    public List<String> advisoryIds() {
        TreeSet<String> ids = new TreeSet<>();
        for (FirewallRuleViolation violation : violations) {
            ids.addAll(violation.advisoryIds());
        }
        return List.copyOf(ids);
    }
}
