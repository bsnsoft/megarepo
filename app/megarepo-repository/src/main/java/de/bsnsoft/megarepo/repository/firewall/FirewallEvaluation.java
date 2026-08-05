package de.bsnsoft.megarepo.repository.firewall;

import de.bsnsoft.megarepo.repository.advisory.AdvisoryFinding;
import de.bsnsoft.megarepo.repository.firewall.identity.ComponentIdentity;

import java.util.List;
import java.util.UUID;

/**
 * What one evaluation saw. A record of an observation, never an instruction.
 *
 * <p>There is deliberately no field, method or state on this type that a caller
 * could act on to withhold content — {@link #blocked()} is a constant
 * {@code false} and exists so that the Phase 1 promise is written down in code
 * rather than only in a comment. The request path does not even receive one of
 * these (see {@link FirewallDownloadObserver}); it is returned to tests and to
 * whatever Phase 2 puts in front of the upstream fetch.
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
 */
public record FirewallEvaluation(
        UUID repositoryId,
        String repositoryName,
        String path,
        FirewallRepositorySettings settings,
        ComponentIdentity identity,
        List<AdvisoryFinding> findings,
        Outcome outcome) {

    public FirewallEvaluation {
        findings = findings == null ? List.of() : List.copyOf(findings);
    }

    /**
     * Always {@code false} in Phase 1, for every mode including
     * {@link de.bsnsoft.megarepo.core.firewall.FirewallMode#QUARANTINE}.
     *
     * <p>AUDIT is defined as "record violations, serve anyway", and Phase 1 ships
     * AUDIT only. When enforcement lands, this becomes a real question and this
     * method is the single place that has to start answering it.
     */
    public boolean blocked() {
        return false;
    }

    /** Whether at least one advisory named this component. */
    public boolean hasFindings() {
        return !findings.isEmpty();
    }

    /** How far the evaluation got. */
    public enum Outcome {

        /** {@code megarepo.firewall.audit.enabled=false} — the hook did nothing. */
        DISABLED,

        /** The repository's mode is OFF. Nothing was queried, nothing recorded. */
        MODE_OFF,

        /**
         * The served path has no asset row, or the asset belongs to no component
         * — checksums, metadata, index pages. Not a finding and not an error.
         */
        NO_COMPONENT,

        /**
         * The component resolved to a hash or to nothing, so no advisory feed can
         * be queried for it. Phase 1 records nothing: "no data about this
         * component" is the {@code UNKNOWN_COMPONENT} policy rule's input, and
         * that rule is Phase 2.
         */
        UNRESOLVABLE_IDENTITY,

        /** Identified, looked up, no advisory matched. */
        CLEAN,

        /** Advisories matched and a violation row was written. */
        RECORDED,

        /**
         * Advisories matched but an equivalent row is already on file within the
         * suppression window. The download was served either way.
         */
        SUPPRESSED,

        /**
         * Evaluation threw. Logged, swallowed, and reported here — the download
         * itself was completed before this ever ran.
         */
        FAILED
    }
}
