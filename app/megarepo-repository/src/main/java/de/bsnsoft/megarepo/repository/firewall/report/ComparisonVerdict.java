package de.bsnsoft.megarepo.repository.firewall.report;

/**
 * The one-word summary of a component's comparison.
 *
 * <p>Deliberately coarse and mutually exclusive, so that the component counts in
 * {@link ComparisonSummary} add up to the number of components scanned. The
 * per-kind detail lives in {@link ComponentComparison#deltas()}; a component may
 * exhibit several {@link DeltaKind}s at once and is still exactly one verdict
 * here.
 */
public enum ComparisonVerdict {

    /**
     * The component has no purl. Raw files and Docker tags carry no package
     * coordinates, so no advisory feed can be asked about them and the purl side
     * has nothing to say.
     *
     * <p>Reported rather than skipped: a legacy CPE match on such a component
     * rests on the file name alone, and the size of that group is part of the
     * honest answer.
     */
    UNIDENTIFIED,

    /** Neither method reports a vulnerability. */
    BOTH_CLEAN,

    /** Both methods report the same set of vulnerabilities. */
    AGREEMENT,

    /** The methods disagree about at least one vulnerability. */
    DIVERGENT
}
