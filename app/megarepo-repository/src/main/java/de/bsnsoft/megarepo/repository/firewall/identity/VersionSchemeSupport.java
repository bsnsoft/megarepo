package de.bsnsoft.megarepo.repository.firewall.identity;

import java.math.BigInteger;

/**
 * Shared primitives for the hand-written version schemes.
 *
 * <p>Package-private on purpose: these are implementation details of
 * {@link SemverVersionScheme}, {@link NuGetVersionScheme},
 * {@link Pep440VersionScheme} and {@link GenericVersionScheme}, not API.
 */
final class VersionSchemeSupport {

    private VersionSchemeSupport() {}

    /**
     * Null ordering shared by every scheme: {@code null} sorts below every
     * non-null value, two {@code null}s are equal. Only call this when at least
     * one argument is {@code null}.
     */
    static int compareNulls(String a, String b) {
        if (a == null && b == null) {
            return 0;
        }
        return a == null ? -1 : 1;
    }

    /**
     * Parses a numeric segment as {@link BigInteger}. Package registries do
     * contain absurd segment values (dates, epoch seconds, four-digit builds
     * concatenated), and a {@code long} overflow would silently invert the
     * order, so no fixed width is assumed.
     */
    static BigInteger number(String digits) {
        return digits == null || digits.isEmpty() ? BigInteger.ZERO : new BigInteger(digits);
    }

    /** {@code true} when the whole string consists of ASCII digits. */
    static boolean isNumeric(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * Compares dot-separated pre-release identifiers by semver §11.4:
     * numeric identifiers are compared numerically, numeric identifiers rank
     * below alphanumeric ones, alphanumeric identifiers are compared by ASCII
     * order, and when all preceding identifiers are equal the longer list wins
     * ({@code 1.0.0-alpha} &lt; {@code 1.0.0-alpha.1}).
     *
     * @param caseInsensitive NuGet folds case on alphanumeric labels
     *                        ({@code 1.0.0-Beta} equals {@code 1.0.0-beta});
     *                        semver does not.
     */
    static int comparePreRelease(String a, String b, boolean caseInsensitive) {
        String[] as = a.split("\\.", -1);
        String[] bs = b.split("\\.", -1);
        int shared = Math.min(as.length, bs.length);
        for (int i = 0; i < shared; i++) {
            int cmp = compareIdentifier(as[i], bs[i], caseInsensitive);
            if (cmp != 0) {
                return cmp;
            }
        }
        return Integer.compare(as.length, bs.length);
    }

    private static int compareIdentifier(String a, String b, boolean caseInsensitive) {
        boolean aNum = isNumeric(a);
        boolean bNum = isNumeric(b);
        if (aNum && bNum) {
            return number(a).compareTo(number(b));
        }
        if (aNum) {
            return -1; // numeric identifiers always have lower precedence
        }
        if (bNum) {
            return 1;
        }
        return caseInsensitive ? String.CASE_INSENSITIVE_ORDER.compare(a, b) : a.compareTo(b);
    }
}
