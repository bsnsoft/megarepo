package de.bsnsoft.megarepo.repository.firewall.identity;

import java.math.BigInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Semantic Versioning 2.0.0 precedence, as npm uses it.
 *
 * <p>Rules implemented (semver.org §10–§11):
 * <ul>
 *   <li>{@code major}, {@code minor}, {@code patch} compare numerically.</li>
 *   <li>A version with a pre-release ranks <em>below</em> the same version
 *       without one: {@code 1.0.0-alpha} &lt; {@code 1.0.0}.</li>
 *   <li>Pre-release identifiers are dot-separated and compared left to right;
 *       numeric identifiers numerically ({@code beta.2} &lt; {@code beta.11}),
 *       numeric below alphanumeric, alphanumeric by ASCII order, and a longer
 *       identifier list wins when the shared prefix is equal.</li>
 *   <li>Build metadata is <em>ignored</em>: {@code 1.0.0+build.1} equals
 *       {@code 1.0.0+build.2}.</li>
 * </ul>
 *
 * <p>Parsing is deliberately more permissive than the spec on two points that
 * occur constantly in real registry metadata: a leading {@code v} or {@code =}
 * is stripped, and a missing minor or patch segment defaults to {@code 0}, so
 * {@code 1.2} is read as {@code 1.2.0}. Everything past that follows the spec.
 *
 * <p>Input that does not parse at all sorts below every parseable version;
 * two unparseable strings fall back to {@link GenericVersionScheme} so the
 * order stays total and stable.
 */
final class SemverVersionScheme implements VersionScheme {

    private static final Pattern SEMVER = Pattern.compile(
            "^[v=]?\\s*(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?"
                    + "(?:-([0-9A-Za-z.-]+))?"
                    + "(?:\\+([0-9A-Za-z.-]+))?$");

    @Override
    public String id() {
        return "semver";
    }

    @Override
    public int compare(String a, String b) {
        if (a == null || b == null) {
            return VersionSchemeSupport.compareNulls(a, b);
        }
        Parsed pa = parse(a);
        Parsed pb = parse(b);
        if (pa == null || pb == null) {
            if (pa == null && pb == null) {
                return VersionSchemes.GENERIC.compare(a, b);
            }
            return pa == null ? -1 : 1;
        }
        return pa.compareTo(pb);
    }

    private static Parsed parse(String version) {
        Matcher m = SEMVER.matcher(version.trim());
        if (!m.matches()) {
            return null;
        }
        return new Parsed(
                VersionSchemeSupport.number(m.group(1)),
                VersionSchemeSupport.number(m.group(2)),
                VersionSchemeSupport.number(m.group(3)),
                m.group(4));
    }

    /** Build metadata is intentionally not captured — it carries no precedence. */
    private record Parsed(BigInteger major, BigInteger minor, BigInteger patch, String preRelease)
            implements Comparable<Parsed> {

        @Override
        public int compareTo(Parsed other) {
            int cmp = major.compareTo(other.major);
            if (cmp != 0) {
                return cmp;
            }
            cmp = minor.compareTo(other.minor);
            if (cmp != 0) {
                return cmp;
            }
            cmp = patch.compareTo(other.patch);
            if (cmp != 0) {
                return cmp;
            }
            if (preRelease == null && other.preRelease == null) {
                return 0;
            }
            if (preRelease == null) {
                return 1; // a release outranks any pre-release of the same version
            }
            if (other.preRelease == null) {
                return -1;
            }
            return VersionSchemeSupport.comparePreRelease(preRelease, other.preRelease, false);
        }
    }
}
