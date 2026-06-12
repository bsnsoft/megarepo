package de.bsnsoft.megarepo.format.nuget.naming;

import java.util.Comparator;
import java.util.Locale;

/**
 * NuGet identifier and version normalization.
 *
 * <p>The NuGet V3 protocol is strictly lowercase on all flat-container and
 * registration paths: the dotnet client lowercases both package id and
 * version before building URLs. Versions additionally follow the
 * <a href="https://learn.microsoft.com/en-us/nuget/concepts/package-versioning#normalized-version-numbers">
 * normalized version</a> rules: build metadata ({@code +...}) is removed,
 * leading zeroes in numeric segments are stripped, and a zero fourth
 * (revision) segment is omitted.
 */
public final class NugetNames {

    private NugetNames() {}

    /** Lowercases a package id for path usage ({@code Newtonsoft.Json} → {@code newtonsoft.json}). */
    public static String lowerId(String id) {
        if (id == null) {
            return null;
        }
        return id.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Normalizes a NuGet version string: strips build metadata, removes
     * leading zeroes from numeric segments, pads to three segments
     * ({@code 1.0} → {@code 1.0.0}) and drops a zero revision segment
     * ({@code 1.0.0.0} → {@code 1.0.0}). The pre-release suffix is kept as-is.
     */
    public static String normalizeVersion(String version) {
        if (version == null || version.isBlank()) {
            return version;
        }
        String v = version.trim();

        // Strip build metadata (+...) — not part of the normalized form
        int plus = v.indexOf('+');
        if (plus >= 0) {
            v = v.substring(0, plus);
        }

        // Split off the pre-release suffix (first '-')
        String prerelease = null;
        int dash = v.indexOf('-');
        if (dash >= 0) {
            prerelease = v.substring(dash + 1);
            v = v.substring(0, dash);
        }

        String[] segments = v.split("\\.");
        long[] numeric = new long[Math.max(segments.length, 3)];
        boolean parseable = segments.length >= 1 && segments.length <= 4;
        if (parseable) {
            for (int i = 0; i < segments.length; i++) {
                try {
                    numeric[i] = Long.parseLong(segments[i]);
                } catch (NumberFormatException e) {
                    parseable = false;
                    break;
                }
            }
        }
        if (!parseable) {
            // Not a plain numeric dotted version — leave untouched (minus metadata)
            return prerelease != null ? v + "-" + prerelease : v;
        }

        StringBuilder sb = new StringBuilder();
        sb.append(numeric[0]).append('.')
                .append(segments.length > 1 ? numeric[1] : 0).append('.')
                .append(segments.length > 2 ? numeric[2] : 0);
        if (segments.length == 4 && numeric[3] != 0) {
            sb.append('.').append(numeric[3]);
        }
        if (prerelease != null && !prerelease.isBlank()) {
            sb.append('-').append(prerelease);
        }
        return sb.toString();
    }

    /** Normalized + lowercased version for path usage. */
    public static String lowerVersion(String version) {
        String normalized = normalizeVersion(version);
        return normalized == null ? null : normalized.toLowerCase(Locale.ROOT);
    }

    /**
     * Orders versions ascending the way NuGet does (SemVer-ish): numeric
     * segments numerically, pre-release below the corresponding release.
     */
    public static Comparator<String> versionOrder() {
        return NugetNames::compareVersions;
    }

    static int compareVersions(String a, String b) {
        if (a == null && b == null) return 0;
        if (a == null) return -1;
        if (b == null) return 1;

        String[] aParts = splitRelease(a);
        String[] bParts = splitRelease(b);

        int cmp = compareNumericDotted(aParts[0], bParts[0]);
        if (cmp != 0) {
            return cmp;
        }
        String aPre = aParts[1];
        String bPre = bParts[1];
        if (aPre.isEmpty() && bPre.isEmpty()) return 0;
        if (aPre.isEmpty()) return 1; // release > pre-release
        if (bPre.isEmpty()) return -1;
        return aPre.compareToIgnoreCase(bPre);
    }

    private static String[] splitRelease(String version) {
        String v = version;
        int plus = v.indexOf('+');
        if (plus >= 0) {
            v = v.substring(0, plus);
        }
        int dash = v.indexOf('-');
        if (dash >= 0) {
            return new String[] {v.substring(0, dash), v.substring(dash + 1)};
        }
        return new String[] {v, ""};
    }

    private static int compareNumericDotted(String a, String b) {
        String[] as = a.split("\\.");
        String[] bs = b.split("\\.");
        int len = Math.max(as.length, bs.length);
        for (int i = 0; i < len; i++) {
            long an = segmentValue(as, i);
            long bn = segmentValue(bs, i);
            if (an != bn) {
                return Long.compare(an, bn);
            }
        }
        return 0;
    }

    private static long segmentValue(String[] segments, int index) {
        if (index >= segments.length) {
            return 0;
        }
        try {
            return Long.parseLong(segments[index]);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
