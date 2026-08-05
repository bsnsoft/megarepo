package de.bsnsoft.megarepo.repository.firewall.report;

/**
 * How much data each side of the comparison actually had.
 *
 * <p>Reported first in the rendered report, because every number after it is
 * conditional on these. A comparison run before the OSV/GHSA ingest has
 * finished would show purl matching finding almost nothing and could be read as
 * evidence against it; the reader has to be able to see that from the report
 * itself rather than infer it.
 *
 * @param cveEntries rows in {@code cve_entries} — the legacy NVD mirror
 * @param cveAffectedProducts rows in {@code cve_affected_products} — the CPE
 *     ranges the legacy path matches against
 * @param advisories rows in {@code advisory} across all sources
 * @param advisoryAffectedTotal rows in {@code advisory_affected}
 * @param advisoryAffectedPurlNative the subset published with a real purl (OSV,
 *     GHSA) — the only rows purl matching can reach at {@code EXACT} confidence
 * @param advisoryAffectedCpeDerived the subset stored under the reserved
 *     {@code cpe} purl type, i.e. NVD data carried over
 */
public record AdvisoryStoreState(
        long cveEntries,
        long cveAffectedProducts,
        long advisories,
        long advisoryAffectedTotal,
        long advisoryAffectedPurlNative,
        long advisoryAffectedCpeDerived) {

    /**
     * {@code true} when one of the two sides has no data at all, which makes the
     * comparison meaningless rather than favourable.
     */
    public boolean isComparable() {
        return cveAffectedProducts > 0 && advisoryAffectedTotal > 0;
    }
}
