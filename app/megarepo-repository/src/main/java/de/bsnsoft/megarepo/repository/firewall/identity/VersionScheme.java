package de.bsnsoft.megarepo.repository.firewall.identity;

import java.util.Comparator;

/**
 * Ecosystem-specific version ordering.
 *
 * <p>Each package ecosystem defines its own version grammar and its own
 * precedence rules. A single generic comparator gets a large share of real
 * versions wrong — Maven's {@code sp} qualifier sorts <em>above</em> the plain
 * release, PEP&nbsp;440's {@code .post1} sorts above {@code 1.0} while
 * {@code rc1} sorts below it, semver compares dot-separated pre-release
 * identifiers numerically where they are numeric, and NuGet ignores build
 * metadata entirely. Those rules contradict each other, so they cannot be
 * folded into one algorithm; they have to be selected per ecosystem.
 *
 * <p>Obtain an implementation through {@link VersionSchemes#forPurlType(String)}.
 *
 * <h2>Contract</h2>
 * Implementations are stateless, immutable and safe for concurrent use.
 * {@link #compare(String, String)} defines a total order and <strong>never
 * throws</strong> — it is called on the download request path with version
 * strings that originate from untrusted upstream metadata. Input that does not
 * parse under the scheme's grammar degrades to a documented fallback instead of
 * raising. {@code null} sorts below every non-null value.
 */
public interface VersionScheme {

    /** Stable scheme identifier, e.g. {@code maven}, {@code pep440}, {@code semver}. */
    String id();

    /**
     * Orders two version strings by this ecosystem's precedence rules.
     *
     * @return negative, zero or positive, like {@link Comparable#compareTo}
     */
    int compare(String a, String b);

    /**
     * Tests whether {@code version} falls inside {@code range}.
     *
     * <p>The range carries no ordering of its own — the same
     * {@code introduced}/{@code fixed} pair means different things in different
     * ecosystems — so containment is answered by the scheme, not by the range.
     * Bounds follow the OSV convention: {@code introduced} is inclusive,
     * {@code fixed} is exclusive, {@code last_affected} is inclusive. See
     * {@link VersionRange}.
     *
     * @return {@code false} when either argument is {@code null}
     */
    default boolean contains(VersionRange range, String version) {
        if (range == null || version == null) {
            return false;
        }
        if (range.hasLowerBound() && compare(version, range.introduced()) < 0) {
            return false;
        }
        if (range.fixed() != null && compare(version, range.fixed()) >= 0) {
            return false;
        }
        return range.lastAffected() == null || compare(version, range.lastAffected()) <= 0;
    }

    /** This scheme as a {@link Comparator}, for sorting version lists. */
    default Comparator<String> comparator() {
        return this::compare;
    }
}
