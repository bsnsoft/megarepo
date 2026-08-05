package de.bsnsoft.megarepo.repository.firewall;

import de.bsnsoft.megarepo.repository.advisory.AdvisoryFinding;
import de.bsnsoft.megarepo.repository.firewall.identity.ComponentIdentity;

import java.util.List;
import java.util.UUID;

/**
 * What one evaluation saw, and — on the enforcement path — what it decided.
 *
 * <p>{@link #blocked()} is the single place that answers "do the bytes go out?".
 * In Phase 1 it was a constant {@code false}, because the observation path
 * cannot withhold anything: it runs after the response has been written, and
 * every evaluation it produces carries {@link FirewallDecision#allowed()}. That
 * is still true of {@link FirewallEvaluationService}. The enforcement path
 * ({@link FirewallEnforcementService}) runs before the response instead and
 * attaches a real decision, so the same method now has a real answer for the
 * downloads that are actually gated.
 *
 * @param repositoryId repository the component was served from
 * @param repositoryName its name, kept because the audit trail outlives the row
 * @param path artifact path that was requested
 * @param settings the repository's resolved firewall configuration
 * @param identity what the component was identified as, or null when the
 *     evaluation stopped before identity could be built
 * @param findings advisories affecting the component; empty when clean or when
 *     no lookup was possible
 * @param outcome why the evaluation ended where it did
 * @param preExisting whether the component was already stored in this repository
 *     before enforcement was switched on. Such a component is recorded like any
 *     other but is never denied — see
 *     {@link FirewallDecision.Reason#PRE_EXISTING}.
 * @param decision the verdict; {@link FirewallDecision#notEvaluated()} when no
 *     enforcement ran, never null
 */
public record FirewallEvaluation(
        UUID repositoryId,
        String repositoryName,
        String path,
        FirewallRepositorySettings settings,
        ComponentIdentity identity,
        List<AdvisoryFinding> findings,
        Outcome outcome,
        boolean preExisting,
        FirewallDecision decision) {

    public FirewallEvaluation {
        findings = findings == null ? List.of() : List.copyOf(findings);
        decision = decision == null ? FirewallDecision.notEvaluated() : decision;
    }

    /**
     * An observation: no decision, nothing pre-existing to consider. This is the
     * shape the AUDIT path produces, and the shape that structurally cannot
     * block.
     */
    public FirewallEvaluation(
            UUID repositoryId,
            String repositoryName,
            String path,
            FirewallRepositorySettings settings,
            ComponentIdentity identity,
            List<AdvisoryFinding> findings,
            Outcome outcome) {
        this(repositoryId, repositoryName, path, settings, identity, findings, outcome,
                false, FirewallDecision.notEvaluated());
    }

    /** Whether the download must be denied. */
    public boolean blocked() {
        return decision.blocked();
    }

    /** Whether an enforcement decision was taken for this download. */
    public boolean enforcementEvaluated() {
        return decision.evaluated();
    }

    /** Whether at least one advisory named this component. */
    public boolean hasFindings() {
        return !findings.isEmpty();
    }

    /** The component's purl or hash key, or null when it was never identified. */
    public String componentKey() {
        return identity == null ? null : identity.key();
    }

    /** The same evaluation with a different outcome. */
    public FirewallEvaluation withOutcome(Outcome newOutcome) {
        return new FirewallEvaluation(
                repositoryId, repositoryName, path, settings, identity, findings, newOutcome,
                preExisting, decision);
    }

    /** The same evaluation with a decision attached. */
    public FirewallEvaluation withDecision(FirewallDecision newDecision) {
        return new FirewallEvaluation(
                repositoryId, repositoryName, path, settings, identity, findings, outcome,
                preExisting, newDecision);
    }

    /** How far the evaluation got. */
    public enum Outcome {

        /** {@code megarepo.firewall.audit.enabled=false} — the hook did nothing. */
        DISABLED,

        /** The repository's mode is OFF. Nothing was queried, nothing recorded. */
        MODE_OFF,

        /**
         * Enforcement did not apply to this download: the master switch is off,
         * or the repository is not in QUARANTINE mode. The observation path
         * handles it instead.
         */
        NOT_ENFORCING,

        /**
         * The served path has no asset row, or the asset belongs to no component
         * — checksums, metadata, index pages. Not a finding and not an error.
         */
        NO_COMPONENT,

        /**
         * The component resolved to a hash or to nothing, so no advisory feed can
         * be queried for it. Nothing is recorded: "no data about this component"
         * is the {@code UNKNOWN_COMPONENT} policy rule's input, and that rule is
         * not implemented.
         */
        UNRESOLVABLE_IDENTITY,

        /** Identified, looked up, no advisory matched. */
        CLEAN,

        /**
         * Advisories matched. Nothing has been decided or written yet — this is
         * what the read-only inspection returns and what the enforcement path
         * hands to the policy engine.
         */
        MATCHED,

        /** Advisories matched and a violation row was written. */
        RECORDED,

        /**
         * Advisories matched but an equivalent row is already on file within the
         * suppression window. The download was served either way.
         */
        SUPPRESSED,

        /**
         * No verdict could be reached in time — the evaluation was rejected,
         * timed out, or threw. The repository's {@code fail_mode} decided what
         * happens to the download; {@link #blocked()} reflects the result.
         */
        UNAVAILABLE,

        /**
         * Evaluation threw on the observation path. Logged, swallowed, and
         * reported here — the download itself was completed before this ever
         * ran.
         */
        FAILED
    }
}
