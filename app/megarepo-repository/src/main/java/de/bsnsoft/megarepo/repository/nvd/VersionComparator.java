package de.bsnsoft.megarepo.repository.nvd;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compares artifact versions using a relaxed semver-ish algorithm that
 * handles the common Maven/npm/PyPI shapes. Splits on dots, hyphens,
 * and underscores; compares numeric segments numerically and text
 * segments lexically. Any version with a text qualifier (rc, alpha,
 * beta, snapshot, m1, ...) sorts below the same prefix without it
 * (1.0.0-rc1 &lt; 1.0.0).
 *
 * Not a replacement for Maven's ComparableVersion, but good enough
 * for CVE range checks which are almost always on major/minor/patch.
 */
public final class VersionComparator {

    private static final Pattern SEGMENT = Pattern.compile("(\\d+)|([A-Za-z]+)");

    private VersionComparator() {}

    /** Returns negative, 0, or positive like Comparable.compareTo. */
    public static int compare(String a, String b) {
        if (a == null && b == null) return 0;
        if (a == null) return -1;
        if (b == null) return 1;
        if (a.equals(b)) return 0;

        String[] aParts = splitMain(a);
        String[] bParts = splitMain(b);

        int cmp = compareMain(aParts[0], bParts[0]);
        if (cmp != 0) return cmp;

        // Qualifier handling: empty qualifier beats any non-empty (1.0 > 1.0-rc1)
        String aq = aParts[1];
        String bq = bParts[1];
        if (aq.isEmpty() && !bq.isEmpty()) return 1;
        if (!aq.isEmpty() && bq.isEmpty()) return -1;
        return aq.compareTo(bq);
    }

    private static String[] splitMain(String v) {
        int dash = v.indexOf('-');
        if (dash < 0) return new String[] { v, "" };
        return new String[] { v.substring(0, dash), v.substring(dash + 1) };
    }

    private static int compareMain(String a, String b) {
        String[] aSeg = a.split("\\.");
        String[] bSeg = b.split("\\.");
        int n = Math.max(aSeg.length, bSeg.length);
        for (int i = 0; i < n; i++) {
            String as = i < aSeg.length ? aSeg[i] : "0";
            String bs = i < bSeg.length ? bSeg[i] : "0";
            int c = compareSegment(as, bs);
            if (c != 0) return c;
        }
        return 0;
    }

    private static int compareSegment(String a, String b) {
        Matcher am = SEGMENT.matcher(a);
        Matcher bm = SEGMENT.matcher(b);
        while (am.find() && bm.find()) {
            // numeric vs numeric → compare as int; numeric beats text (1 > rc)
            String aNum = am.group(1), bNum = bm.group(1);
            if (aNum != null && bNum != null) {
                int c = Long.compare(Long.parseLong(aNum), Long.parseLong(bNum));
                if (c != 0) return c;
            } else if (aNum != null) {
                return 1;
            } else if (bNum != null) {
                return -1;
            } else {
                int c = am.group(2).compareToIgnoreCase(bm.group(2));
                if (c != 0) return c;
            }
        }
        if (am.find()) return 1;
        if (bm.find()) return -1;
        return 0;
    }
}
