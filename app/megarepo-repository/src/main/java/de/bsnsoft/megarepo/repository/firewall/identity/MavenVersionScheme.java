package de.bsnsoft.megarepo.repository.firewall.identity;

import org.apache.maven.artifact.versioning.ComparableVersion;

/**
 * Maven ordering, delegated to Maven's own {@link ComparableVersion}.
 *
 * <p>Reimplementing this would mean reimplementing the qualifier table
 * ({@code alpha < beta < milestone < rc < snapshot < "" < sp}), the qualifier
 * aliases ({@code a}={@code alpha}, {@code b}={@code beta}, {@code m}={@code milestone},
 * {@code ga}/{@code final}/{@code release}={@code ""}) and the trailing-zero
 * normalisation ({@code 1.0} = {@code 1.0.0}). Maven ships that logic in a
 * dependency-free class, so it is used directly — this is the reference
 * implementation the Maven resolver itself uses.
 *
 * <p>{@code ComparableVersion} parses anything without throwing: unrecognised
 * input becomes a plain qualifier token, so the {@code compare} contract holds
 * for arbitrary strings.
 */
final class MavenVersionScheme implements VersionScheme {

    @Override
    public String id() {
        return "maven";
    }

    @Override
    public int compare(String a, String b) {
        if (a == null || b == null) {
            return VersionSchemeSupport.compareNulls(a, b);
        }
        return new ComparableVersion(a.trim()).compareTo(new ComparableVersion(b.trim()));
    }
}
