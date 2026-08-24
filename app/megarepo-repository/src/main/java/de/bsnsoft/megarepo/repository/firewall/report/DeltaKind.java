package de.bsnsoft.megarepo.repository.firewall.report;

/**
 * How the two matching methods disagreed about one vulnerability.
 *
 * <p>The unit of comparison is a <em>vulnerability</em>, not an advisory row:
 * one purl-side finding may carry several upstream ids ({@code GHSA-…} plus the
 * {@code CVE-…} it aliases), and a legacy CVE id counts as its own vulnerability
 * only when no purl-side finding claims it. That makes the five kinds a
 * partition — every vulnerability seen for a component falls into exactly one.
 *
 * <p>The two {@code VERSION_ONLY_*} kinds exist because a raw
 * "only-one-side-flagged-it" split would hide the second defect the design
 * names: both methods can identify the same package and still disagree, because
 * the legacy {@code VersionComparator} orders versions generically while
 * {@code VersionScheme} orders them by the ecosystem's own grammar. Folding
 * those cases into {@link #CPE_ONLY} would overstate the false-positive
 * improvement of purl identity and understate the effect of the version
 * ordering fix.
 */
public enum DeltaKind {

    /** Both methods report the vulnerability. No change from purl identity. */
    AGREED,

    /**
     * Only the legacy CPE match reports it, and the purl side never matched the
     * package at all — the CPE product name matched something the component
     * is not.
     *
     * <p>This is the suspected false positive. "Suspected" and not "confirmed":
     * the purl side can only refute a CPE match when the advisory store actually
     * carries the package under a purl. {@link VulnerabilityDelta#assessment()}
     * states which of the two situations applies for each case.
     */
    CPE_ONLY,

    /**
     * Only the purl match reports it — the legacy CPE candidate generation never
     * produced a product name that reaches this advisory.
     *
     * <p>A vulnerability the current firewall misses entirely.
     */
    PURL_ONLY,

    /**
     * Both methods identify the same package; only the legacy path considers the
     * component's version affected.
     *
     * <p>A patched release the legacy {@code VersionComparator} sorts into the
     * vulnerable range — Maven's {@code -sp1}, PEP 440's {@code .post1}.
     */
    VERSION_ONLY_CPE,

    /**
     * Both methods identify the same package; only the purl path considers the
     * component's version affected.
     *
     * <p>The mirror image: a genuinely vulnerable release the legacy comparator
     * sorts out of the range.
     */
    VERSION_ONLY_PURL;

    /** {@code true} for the kinds where the two methods reached the same verdict. */
    public boolean isAgreement() {
        return this == AGREED;
    }

    /** {@code true} for the two kinds that differ only in the version verdict. */
    public boolean isVersionDisagreement() {
        return this == VERSION_ONLY_CPE || this == VERSION_ONLY_PURL;
    }
}
