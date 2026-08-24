package de.bsnsoft.megarepo.repository.advisory;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * One vulnerability affecting one looked-up component.
 *
 * <p>This is what {@link AdvisoryLookupService} returns and what the policy
 * engine will evaluate. It is a <em>merged</em> view: the same vulnerability
 * published as a CVE by NVD, as a GHSA by GitHub and as an OSV entry appears
 * once, with one {@link AdvisoryMatch} per contributing source in
 * {@link #matches()}.
 *
 * <p>The scalar fields are taken from the <em>primary</em> advisory of the
 * merge group — the match with the strongest {@link MatchConfidence}, then the
 * highest CVSS score, then the lowest id. Ties are broken deterministically on
 * purpose: a finding whose severity depends on which source happened to sync
 * first is not something a policy can be written against.
 *
 * @param advisoryId id of the primary advisory; {@link #advisoryIds()} has all of them
 * @param summary short description of the primary advisory, may be null
 * @param severity textual severity as published, may be null
 * @param cvssScore CVSS base score, null when no contributing source published one
 * @param cvssVector CVSS vector string, may be null
 * @param published first publication time of the primary advisory, may be null
 * @param modified last upstream modification of the primary advisory, may be null
 * @param matches every advisory row that matched, never empty
 */
public record AdvisoryFinding(
        String advisoryId,
        String summary,
        String severity,
        Double cvssScore,
        String cvssVector,
        Instant published,
        Instant modified,
        List<AdvisoryMatch> matches) {

    public AdvisoryFinding {
        matches = matches == null ? List.of() : List.copyOf(matches);
    }

    /**
     * The strongest confidence any contributing source supports.
     *
     * <p>Strongest rather than weakest: once <em>one</em> feed named the package
     * by purl, the component is identified — an additional CPE-derived match for
     * the same vulnerability does not make that weaker.
     */
    public MatchConfidence confidence() {
        MatchConfidence best = MatchConfidence.HEURISTIC;
        for (AdvisoryMatch match : matches) {
            if (match.confidence().compareTo(best) < 0) {
                best = match.confidence();
            }
        }
        return best;
    }

    /** Every source that reported this vulnerability, in match order. */
    public Set<String> sources() {
        Set<String> sources = new LinkedHashSet<>();
        for (AdvisoryMatch match : matches) {
            if (match.source() != null) {
                sources.add(match.source());
            }
        }
        return sources;
    }

    /** Every upstream id this finding was merged from, in match order. */
    public Set<String> advisoryIds() {
        Set<String> ids = new LinkedHashSet<>();
        for (AdvisoryMatch match : matches) {
            ids.add(match.advisoryId());
        }
        return ids;
    }

    /** {@code true} when more than one advisory id was collapsed into this finding. */
    public boolean isMerged() {
        return advisoryIds().size() > 1;
    }
}
