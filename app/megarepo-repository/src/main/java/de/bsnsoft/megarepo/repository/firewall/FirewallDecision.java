package de.bsnsoft.megarepo.repository.firewall;

import java.util.ArrayList;
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
 * @param blocked whether the download must be denied
 * @param reason why the decision came out the way it did — the 403 body and the
 *     audit row both key on this rather than re-deriving it
 * @param policyId the policy that was evaluated, or null when none was
 * @param policyName its name, for the message a developer reads in a build log
 * @param violations every rule that matched, blocking or not
 */
public record FirewallDecision(
        boolean blocked,
        Reason reason,
        UUID policyId,
        String policyName,
        List<FirewallRuleViolation> violations) {

    public FirewallDecision {
        violations = violations == null ? List.of() : List.copyOf(violations);
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
        return new FirewallDecision(false, Reason.NOT_EVALUATED, null, null, List.of());
    }

    /** Evaluated and allowed, with whatever non-blocking rules matched. */
    public static FirewallDecision allowed(
            UUID policyId, String policyName, List<FirewallRuleViolation> violations) {
        return new FirewallDecision(false, Reason.ALLOWED, policyId, policyName, violations);
    }

    /** Evaluated and allowed, no policy involved. */
    public static FirewallDecision allowed() {
        return new FirewallDecision(false, Reason.ALLOWED, null, null, List.of());
    }

    /** A blocking rule matched and the download is denied. */
    public static FirewallDecision blocked(
            UUID policyId, String policyName, List<FirewallRuleViolation> violations) {
        return new FirewallDecision(true, Reason.POLICY, policyId, policyName, violations);
    }

    /** A blocking rule matched, but the component predates enforcement. */
    public static FirewallDecision preExisting(
            UUID policyId, String policyName, List<FirewallRuleViolation> violations) {
        return new FirewallDecision(false, Reason.PRE_EXISTING, policyId, policyName, violations);
    }

    /**
     * No verdict could be reached.
     *
     * @param failClosed the repository's {@code fail_mode}, which is what
     *     decides — fail-closed denies, fail-open serves
     */
    public static FirewallDecision unavailable(boolean failClosed) {
        return new FirewallDecision(
                failClosed, Reason.EVALUATION_UNAVAILABLE, null, null, List.of());
    }

    /** Whether a decision was taken at all. */
    public boolean evaluated() {
        return reason != Reason.NOT_EVALUATED;
    }

    /** Whether the repository's fail mode had to decide this one. */
    public boolean failModeApplied() {
        return reason == Reason.EVALUATION_UNAVAILABLE;
    }

    /** Only the rules that asked to block. */
    public List<FirewallRuleViolation> blockingViolations() {
        List<FirewallRuleViolation> blocking = new ArrayList<>();
        for (FirewallRuleViolation violation : violations) {
            if (violation.blocks()) {
                blocking.add(violation);
            }
        }
        return blocking;
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
