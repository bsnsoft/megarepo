package de.bsnsoft.megarepo.repository.advisory;

/**
 * One advisory row that matched a looked-up component, together with the
 * evidence for the match.
 *
 * <p>A finding keeps every match that contributed to it rather than a single
 * verdict, because the customer asked for findings <em>labelled with source and
 * confidence</em>. After merging, one vulnerability reported by NVD, OSV and
 * GHSA is a single {@link AdvisoryFinding} carrying three matches — the
 * duplicate disappears from the report while the provenance does not.
 *
 * @param advisoryId the upstream id that matched, e.g. {@code CVE-2021-44228}
 * @param source the {@code AdvisorySource#sourceId()} that published it
 * @param confidence how the match was made; see {@link MatchConfidence}
 * @param matchedRange the affected range that covered the component's version,
 *     as published — the auditable reason this component was flagged
 */
public record AdvisoryMatch(
        String advisoryId, String source, MatchConfidence confidence, String matchedRange) {}
