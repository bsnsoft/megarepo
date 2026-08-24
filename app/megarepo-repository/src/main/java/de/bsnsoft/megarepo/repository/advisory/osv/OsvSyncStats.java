package de.bsnsoft.megarepo.repository.advisory.osv;

/**
 * What a single {@code sync()} call read, kept and threw away.
 *
 * <p>An advisory feed is external input: a record with a field OSV added last week, a
 * range whose events do not pair up, an ecosystem MegaRepo does not host. None of those
 * may end the sync — the alternative is a firewall whose advisory table silently stops
 * updating the day upstream adds a field. Everything skipped is therefore counted here
 * and logged once per ecosystem, so "we mirror 40% of OSV" stays a visible number rather
 * than an assumption.
 *
 * <p>Not thread-safe; one instance belongs to one sync call.
 */
public final class OsvSyncStats {

    /** JSON entries taken out of the archive. */
    public int entriesRead;

    /** Advisories handed to the caller. */
    public int emitted;

    /** Entries whose JSON did not parse. */
    public int malformedJson;

    /** Entries larger than the per-entry byte cap — a single record is never megabytes. */
    public int oversizedEntries;

    /** Records with no id, or an id too long for {@code advisory.id}. */
    public int unusableRecord;

    /** Records whose every affected entry was dropped, leaving nothing to store. */
    public int noAffectedRanges;

    /** Affected entries naming an ecosystem MegaRepo does not mirror (Go, Debian, …). */
    public int skippedForeignEcosystem;

    /** Affected entries whose package name does not split into purl components. */
    public int skippedUnusablePackageName;

    /** {@code GIT} ranges — commit hashes, not versions any {@code VersionScheme} orders. */
    public int skippedGitRanges;

    /** Affected entries that carried ranges but none MegaRepo could use. */
    public int skippedUnusableRanges;

    /** Version bounds too long for {@code advisory_affected} — dropped, never truncated. */
    public int skippedOverlongBounds;

    /** Enumerated {@code versions[]} beyond the per-entry cap. */
    public int truncatedVersionEnumerations;

    /** Records unchanged since the previous pass's watermark. */
    public int unchanged;

    /** Everything read but not handed on, for a single "how much of OSV do we keep" number. */
    public int discarded() {
        return malformedJson + oversizedEntries + unusableRecord + noAffectedRanges + unchanged;
    }

    @Override
    public String toString() {
        return ("read=%d emitted=%d unchanged=%d discarded=%d "
                        + "(json=%d oversized=%d unusable=%d noRanges=%d) "
                        + "affectedSkipped(foreignEco=%d badName=%d git=%d unusableRange=%d longBound=%d) "
                        + "versionsTruncated=%d")
                .formatted(
                        entriesRead,
                        emitted,
                        unchanged,
                        discarded(),
                        malformedJson,
                        oversizedEntries,
                        unusableRecord,
                        noAffectedRanges,
                        skippedForeignEcosystem,
                        skippedUnusablePackageName,
                        skippedGitRanges,
                        skippedUnusableRanges,
                        skippedOverlongBounds,
                        truncatedVersionEnumerations);
    }
}
