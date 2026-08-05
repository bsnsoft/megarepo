package de.bsnsoft.megarepo.repository.firewall.identity;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PEP 440 ordering, as PyPI and pip use it.
 *
 * <p>The grammar is
 * {@code [N!]N(.N)*[{a|b|rc}N][.postN][.devN][+local]}, and the precedence it
 * defines is the one place a generic comparator cannot be patched into
 * correctness, because two suffixes pull in opposite directions:
 * {@code 1.0rc1} sorts <em>below</em> {@code 1.0} while {@code 1.0.post1} sorts
 * <em>above</em> it.
 *
 * <p>The canonical ordering:
 * <pre>
 * 1.0.dev1 &lt; 1.0a1 &lt; 1.0a2 &lt; 1.0b1 &lt; 1.0rc1 &lt; 1.0
 *          &lt; 1.0+local &lt; 1.0.post1.dev1 &lt; 1.0.post1 &lt; 1.1
 * </pre>
 *
 * <p>Implemented as the sort key PEP 440 specifies — {@code (epoch, release,
 * pre, post, dev, local)} — with the infinity sentinels the reference
 * implementation uses:
 * <ul>
 *   <li>no pre-release, but a dev release and no post release → {@code pre} is
 *       negative infinity, which is what puts {@code 1.0.dev1} below
 *       {@code 1.0a1};</li>
 *   <li>no pre-release otherwise → {@code pre} is positive infinity, putting
 *       the final release above all of its pre-releases;</li>
 *   <li>no post release → negative infinity; no dev release → positive
 *       infinity, so {@code 1.0.post1.dev1} &lt; {@code 1.0.post1}.</li>
 * </ul>
 *
 * <p>Normalisation follows the spec: an epoch defaults to {@code 0}, trailing
 * zeros in the release segment are irrelevant ({@code 1.0} = {@code 1.0.0}),
 * separators before a suffix are optional and interchangeable
 * ({@code 1.0a1} = {@code 1.0-a1} = {@code 1.0_alpha.1}), spellings are folded
 * ({@code alpha}→{@code a}, {@code beta}→{@code b},
 * {@code c}/{@code pre}/{@code preview}→{@code rc},
 * {@code rev}/{@code r}→{@code post}), and the implicit post form {@code 1.0-1}
 * means {@code 1.0.post1}.
 *
 * <p>Local version labels ({@code +ubuntu.1}) are compared last and only
 * against each other: a version without a local label ranks below the same
 * version with one, and within a label numeric segments outrank alphanumeric
 * ones.
 *
 * <p>Unparseable input sorts below every parseable version; two unparseable
 * strings fall back to {@link GenericVersionScheme}.
 */
final class Pep440VersionScheme implements VersionScheme {

    private static final Pattern PEP440 = Pattern.compile(
            "^\\s*v?"
                    + "(?:(?<epoch>[0-9]+)!)?"
                    + "(?<release>[0-9]+(?:\\.[0-9]+)*)"
                    + "(?:[-_.]?(?<preL>alpha|beta|preview|pre|rc|a|b|c)[-_.]?(?<preN>[0-9]+)?)?"
                    + "(?:(?:-(?<postN1>[0-9]+))"
                    + "|(?:[-_.]?(?<postL>post|rev|r)[-_.]?(?<postN2>[0-9]+)?))?"
                    + "(?:[-_.]?(?<devL>dev)[-_.]?(?<devN>[0-9]+)?)?"
                    + "(?:\\+(?<local>[a-z0-9]+(?:[-_.][a-z0-9]+)*))?"
                    + "\\s*$",
            Pattern.CASE_INSENSITIVE);

    /** Pre-release ranks. The sentinels bracket the real letters a &lt; b &lt; rc. */
    private static final int PRE_NEGATIVE_INFINITY = -1;
    private static final int PRE_A = 0;
    private static final int PRE_B = 1;
    private static final int PRE_RC = 2;
    private static final int PRE_POSITIVE_INFINITY = 3;

    /** No post release ranks below {@code .post0}, so a sentinel under zero is needed. */
    private static final BigInteger POST_ABSENT = BigInteger.valueOf(-1);

    @Override
    public String id() {
        return "pep440";
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
        Matcher m = PEP440.matcher(version.trim());
        if (!m.matches()) {
            return null;
        }

        BigInteger epoch = VersionSchemeSupport.number(m.group("epoch"));

        List<BigInteger> release = new ArrayList<>();
        for (String segment : m.group("release").split("\\.")) {
            release.add(VersionSchemeSupport.number(segment));
        }

        String preLetter = m.group("preL");
        String postNumber = m.group("postN1") != null ? m.group("postN1") : m.group("postN2");
        boolean hasPost = m.group("postN1") != null || m.group("postL") != null;
        boolean hasDev = m.group("devL") != null;

        int preRank;
        BigInteger preNumber = BigInteger.ZERO;
        if (preLetter != null) {
            preRank = rankOf(preLetter);
            preNumber = VersionSchemeSupport.number(m.group("preN"));
        } else if (hasDev && !hasPost) {
            // A dev release of an otherwise plain version precedes every
            // pre-release of that version: 1.0.dev1 < 1.0a1.
            preRank = PRE_NEGATIVE_INFINITY;
        } else {
            preRank = PRE_POSITIVE_INFINITY;
        }

        BigInteger post = hasPost ? VersionSchemeSupport.number(postNumber) : POST_ABSENT;
        BigInteger dev = hasDev ? VersionSchemeSupport.number(m.group("devN")) : null;

        return new Parsed(epoch, release, preRank, preNumber, post, dev, m.group("local"));
    }

    private static int rankOf(String letter) {
        return switch (letter.toLowerCase(Locale.ROOT)) {
            case "a", "alpha" -> PRE_A;
            case "b", "beta" -> PRE_B;
            default -> PRE_RC; // c, pre, preview, rc all normalise to rc
        };
    }

    private record Parsed(
            BigInteger epoch,
            List<BigInteger> release,
            int preRank,
            BigInteger preNumber,
            BigInteger post,
            BigInteger dev,
            String local)
            implements Comparable<Parsed> {

        @Override
        public int compareTo(Parsed other) {
            int cmp = epoch.compareTo(other.epoch);
            if (cmp != 0) {
                return cmp;
            }
            cmp = compareRelease(release, other.release);
            if (cmp != 0) {
                return cmp;
            }
            cmp = Integer.compare(preRank, other.preRank);
            if (cmp != 0) {
                return cmp;
            }
            cmp = preNumber.compareTo(other.preNumber);
            if (cmp != 0) {
                return cmp;
            }
            cmp = post.compareTo(other.post);
            if (cmp != 0) {
                return cmp;
            }
            cmp = compareDev(dev, other.dev);
            if (cmp != 0) {
                return cmp;
            }
            return compareLocal(local, other.local);
        }

        /** Trailing zeros carry no meaning: {@code 1.0} equals {@code 1.0.0}. */
        private static int compareRelease(List<BigInteger> a, List<BigInteger> b) {
            int len = Math.max(a.size(), b.size());
            for (int i = 0; i < len; i++) {
                BigInteger av = i < a.size() ? a.get(i) : BigInteger.ZERO;
                BigInteger bv = i < b.size() ? b.get(i) : BigInteger.ZERO;
                int cmp = av.compareTo(bv);
                if (cmp != 0) {
                    return cmp;
                }
            }
            return 0;
        }

        /** Absent dev is positive infinity: {@code 1.0.post1.dev1} &lt; {@code 1.0.post1}. */
        private static int compareDev(BigInteger a, BigInteger b) {
            if (a == null && b == null) {
                return 0;
            }
            if (a == null) {
                return 1;
            }
            if (b == null) {
                return -1;
            }
            return a.compareTo(b);
        }

        /** Absent local label is negative infinity: {@code 1.0} &lt; {@code 1.0+local}. */
        private static int compareLocal(String a, String b) {
            if (a == null && b == null) {
                return 0;
            }
            if (a == null) {
                return -1;
            }
            if (b == null) {
                return 1;
            }
            String[] as = a.toLowerCase(Locale.ROOT).split("[-_.]");
            String[] bs = b.toLowerCase(Locale.ROOT).split("[-_.]");
            int shared = Math.min(as.length, bs.length);
            for (int i = 0; i < shared; i++) {
                boolean aNum = VersionSchemeSupport.isNumeric(as[i]);
                boolean bNum = VersionSchemeSupport.isNumeric(bs[i]);
                int cmp;
                if (aNum && bNum) {
                    cmp = VersionSchemeSupport.number(as[i]).compareTo(VersionSchemeSupport.number(bs[i]));
                } else if (aNum) {
                    cmp = 1; // a numeric local segment outranks an alphanumeric one
                } else if (bNum) {
                    cmp = -1;
                } else {
                    cmp = as[i].compareTo(bs[i]);
                }
                if (cmp != 0) {
                    return cmp;
                }
            }
            return Integer.compare(as.length, bs.length);
        }
    }
}
