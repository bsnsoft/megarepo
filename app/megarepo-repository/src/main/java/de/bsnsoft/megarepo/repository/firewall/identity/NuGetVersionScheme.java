package de.bsnsoft.megarepo.repository.firewall.identity;

import java.math.BigInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * NuGet version ordering — SemVer 2.0.0 with the three deviations NuGet makes.
 *
 * <ul>
 *   <li><strong>Four numeric parts.</strong> {@code Major.Minor.Patch.Revision}
 *       is legal and ordered on all four. A missing part is {@code 0}, so
 *       {@code 1.0.0.0} equals {@code 1.0.0} — that is exactly NuGet's
 *       normalisation rule, which drops a zero revision.</li>
 *   <li><strong>Case-insensitive pre-release labels.</strong> NuGet compares
 *       alphanumeric release labels ordinal-ignore-case, so {@code 1.0.0-Beta}
 *       and {@code 1.0.0-beta} are the same version. Semver would order them
 *       apart.</li>
 *   <li><strong>Build metadata is irrelevant.</strong> Everything after
 *       {@code +} is dropped before comparing, so {@code 1.0.0+sha.abc} equals
 *       {@code 1.0.0}. NuGet strips metadata during normalisation and the
 *       gallery rejects two packages that differ only in metadata.</li>
 * </ul>
 *
 * <p>This mirrors the ordering that {@code NuGet.Versioning.VersionComparer.Default}
 * implements. It is kept separate from {@code NugetNames.versionOrder()} in
 * {@code megarepo-format-nuget}: that comparator drives V3 protocol output and
 * orders pre-release labels by plain string comparison, which is good enough
 * for listing pages but mis-orders {@code rc.2} against {@code rc.10}. The
 * format module also sits <em>above</em> {@code megarepo-repository} in the
 * module graph, so it cannot be reused from here without a dependency cycle.
 *
 * <p>Unparseable input sorts below every parseable version; two unparseable
 * strings fall back to {@link GenericVersionScheme}.
 */
final class NuGetVersionScheme implements VersionScheme {

    private static final Pattern NUGET = Pattern.compile(
            "^v?\\s*(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?(?:\\.(\\d+))?"
                    + "(?:-([0-9A-Za-z.-]+))?"
                    + "(?:\\+([0-9A-Za-z.-]+))?$");

    @Override
    public String id() {
        return "nuget";
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
        Matcher m = NUGET.matcher(version.trim());
        if (!m.matches()) {
            return null;
        }
        return new Parsed(
                VersionSchemeSupport.number(m.group(1)),
                VersionSchemeSupport.number(m.group(2)),
                VersionSchemeSupport.number(m.group(3)),
                VersionSchemeSupport.number(m.group(4)),
                m.group(5));
    }

    /** Build metadata is intentionally not captured — NuGet ignores it entirely. */
    private record Parsed(
            BigInteger major,
            BigInteger minor,
            BigInteger patch,
            BigInteger revision,
            String preRelease)
            implements Comparable<Parsed> {

        @Override
        public int compareTo(Parsed other) {
            int cmp = major.compareTo(other.major);
            if (cmp == 0) {
                cmp = minor.compareTo(other.minor);
            }
            if (cmp == 0) {
                cmp = patch.compareTo(other.patch);
            }
            if (cmp == 0) {
                cmp = revision.compareTo(other.revision);
            }
            if (cmp != 0) {
                return cmp;
            }
            if (preRelease == null && other.preRelease == null) {
                return 0;
            }
            if (preRelease == null) {
                return 1;
            }
            if (other.preRelease == null) {
                return -1;
            }
            return VersionSchemeSupport.comparePreRelease(preRelease, other.preRelease, true);
        }
    }
}
