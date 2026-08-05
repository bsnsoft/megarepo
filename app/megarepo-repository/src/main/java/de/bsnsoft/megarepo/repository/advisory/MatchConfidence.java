package de.bsnsoft.megarepo.repository.advisory;

/**
 * How much the firewall trusts that an advisory really describes the component
 * that was looked up.
 *
 * <p>The customer's requirement is to <em>"label findings with source and
 * confidence"</em>, and the reason is the CPE problem: NVD names software by
 * {@code vendor:product} while every other feed — and MegaRepo itself — names it
 * by purl. There is no lossless mapping between the two (see
 * {@link CpePurlTranslator}), so a match derived from a CPE is a weaker claim
 * than a match on purl coordinates. Collapsing both into one "this is
 * vulnerable" verdict is what produced the false positives the customer
 * reported.
 *
 * <p>The level is derived from the stored row, never from the source name: a row
 * whose {@code purl_type} is {@link CpePurlTranslator#PURL_TYPE} is
 * {@link #HEURISTIC}, anything else is {@link #EXACT}. That keeps the rule in
 * one place and stays correct if a source ever publishes both shapes.
 *
 * <p>Declaration order is significant — constants are declared strongest first,
 * so {@link Enum#compareTo} sorts the more trustworthy finding to the front.
 */
public enum MatchConfidence {

    /**
     * The advisory named the package by purl and every purl component matched:
     * type, namespace and name. OSV and GitHub Advisories publish this shape.
     *
     * <p>A false positive at this level means the advisory itself is wrong, not
     * that MegaRepo guessed.
     */
    EXACT,

    /**
     * The advisory named the software by CPE and the match was made on the CPE
     * product name alone.
     *
     * <p>Neither the ecosystem nor the publisher was verified: a CPE carries no
     * purl type, and its vendor is an organisation name ({@code apache}) which
     * has no relation to a purl namespace
     * ({@code org.apache.logging.log4j}). Two unrelated packages that happen to
     * share a name therefore match the same CPE product.
     *
     * <p>Findings at this level are real CVEs and must be shown, but they are
     * candidates for human review rather than grounds for a silent block.
     */
    HEURISTIC;

    /** {@code true} for the level a policy may act on without human review. */
    public boolean isExact() {
        return this == EXACT;
    }
}
