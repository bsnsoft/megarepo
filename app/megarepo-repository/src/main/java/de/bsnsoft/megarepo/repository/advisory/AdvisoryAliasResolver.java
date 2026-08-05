package de.bsnsoft.megarepo.repository.advisory;

import java.util.Collection;
import java.util.Map;

/**
 * Groups advisory ids that describe the same vulnerability.
 *
 * <p>The same flaw is published under several ids: NVD issues
 * {@code CVE-2021-44228}, GitHub issues {@code GHSA-jfh8-c2jp-5v3q}, OSV mirrors
 * both. Without a way to relate them, a single Log4Shell in a jar produces three
 * findings and the report becomes noise. Resolving the relation is what turns
 * {@link AdvisoryMergeService} from a no-op into deduplication.
 *
 * <h2>What can be resolved today, and what cannot</h2>
 *
 * <p>OSV and GHSA both publish an {@code aliases} array, but
 * {@link NormalizedAdvisory} — the frozen Phase 1 ingest contract — has no field
 * to carry it, so no source can hand aliases to the ingest. Persisting them
 * would therefore mean an {@code advisory_alias} table that nothing could ever
 * fill, and an empty table reads as "these advisories have no aliases" rather
 * than "aliases are not implemented yet". No such table is created.
 *
 * <p>The relation that <em>is</em> sound today is identity of the upstream id
 * itself, which {@link IdentityAliasResolver} implements. It genuinely fires:
 * {@code advisory.id} is the primary key, so two sources publishing the same id
 * already collapse to one row, and a lookup that reaches the same advisory
 * through both an exact and a CPE-derived range must not report it twice.
 *
 * <p>Cross-id merging (GHSA ↔ CVE) needs {@code aliases} on
 * {@link NormalizedAdvisory} plus an {@code advisory_alias} table. This
 * interface is the seam for it: a database-backed implementation marked
 * {@code @Primary} replaces the default without any change to
 * {@link AdvisoryMergeService} or {@link AdvisoryLookupService}.
 */
public interface AdvisoryAliasResolver {

    /**
     * Maps every given advisory id to the canonical id of its vulnerability.
     *
     * <p>Two ids that map to the same value are the same vulnerability. The
     * value is a grouping key and must be stable for a given input set; it need
     * not itself be a known advisory id. Which advisory of a group supplies the
     * merged finding's metadata is decided by {@link AdvisoryMergeService}, not
     * by this key.
     *
     * <p>Batched by design: a database-backed implementation resolves a whole
     * lookup result in one query, and this sits on the download request path.
     *
     * @param advisoryIds ids to group, never null, may be empty
     * @return id → canonical id, containing an entry for every input id
     */
    Map<String, String> canonicalIds(Collection<String> advisoryIds);
}
