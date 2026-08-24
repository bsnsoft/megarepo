package de.bsnsoft.megarepo.repository.firewall.identity;

import java.util.ArrayList;
import java.util.List;

/**
 * Fallback ordering for formats that have no version grammar — raw repositories
 * and docker tags — and the last resort when an ecosystem scheme cannot parse
 * its input.
 *
 * <p>There is no correct answer for {@code latest} versus {@code 1.2.3}, so the
 * goal here is not correctness but <strong>predictability</strong>: a total,
 * transitive, documented order, so that sorting a list of raw artifacts or
 * evaluating a range against a docker tag produces the same result every time
 * instead of depending on input order.
 *
 * <h2>Defined behaviour</h2>
 * <ol>
 *   <li>Both strings are trimmed; two equal strings compare equal.</li>
 *   <li>Separators {@code . - _ +} are delimiters and carry no weight of their
 *       own. The rest is tokenised into maximal runs of digits and maximal runs
 *       of non-digits, so {@code 1.0rc2} yields {@code [1, 0, rc, 2]}.</li>
 *   <li>Tokens are compared pairwise from the left. Two digit runs compare
 *       numerically, so {@code 1.10} &gt; {@code 1.9} and leading zeros do not
 *       matter. Two non-digit runs compare case-insensitively, falling back to
 *       case-sensitive order only to break an exact tie.</li>
 *   <li>A digit run outranks a non-digit run, so {@code 1.2} &gt; {@code 1.rc}.</li>
 *   <li>When all shared tokens are equal the shorter token list ranks lower:
 *       {@code 1.0} &lt; {@code 1.0.1} and {@code 1.0} &lt; {@code 1.0-rc}.</li>
 * </ol>
 *
 * <h2>Consequences worth knowing</h2>
 * <ul>
 *   <li>Trailing zeros are <em>significant</em>: {@code 1.0} and {@code 1.0.0}
 *       are different versions here. Unlike Maven or PEP 440, this scheme has
 *       no basis for declaring them equal.</li>
 *   <li>Because separators are ignored, {@code 1.0} and {@code 1-0} compare
 *       equal.</li>
 *   <li>Rule 5 means a pre-release suffix ranks <em>above</em> the bare
 *       version, the opposite of semver. Nothing in a raw repository or a
 *       docker tag identifies a suffix as a pre-release, so no such meaning is
 *       invented.</li>
 * </ul>
 */
final class GenericVersionScheme implements VersionScheme {

    @Override
    public String id() {
        return "generic";
    }

    @Override
    public int compare(String a, String b) {
        if (a == null || b == null) {
            return VersionSchemeSupport.compareNulls(a, b);
        }
        String left = a.trim();
        String right = b.trim();
        if (left.equals(right)) {
            return 0;
        }

        List<String> lt = tokenize(left);
        List<String> rt = tokenize(right);
        int shared = Math.min(lt.size(), rt.size());
        for (int i = 0; i < shared; i++) {
            int cmp = compareToken(lt.get(i), rt.get(i));
            if (cmp != 0) {
                return cmp;
            }
        }
        return Integer.compare(lt.size(), rt.size());
    }

    private static int compareToken(String a, String b) {
        boolean aNum = VersionSchemeSupport.isNumeric(a);
        boolean bNum = VersionSchemeSupport.isNumeric(b);
        if (aNum && bNum) {
            return VersionSchemeSupport.number(a).compareTo(VersionSchemeSupport.number(b));
        }
        if (aNum) {
            return 1;
        }
        if (bNum) {
            return -1;
        }
        int cmp = String.CASE_INSENSITIVE_ORDER.compare(a, b);
        return cmp != 0 ? cmp : a.compareTo(b);
    }

    /** Splits into maximal digit and non-digit runs, dropping {@code . - _ +}. */
    private static List<String> tokenize(String version) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean currentIsDigit = false;

        for (int i = 0; i < version.length(); i++) {
            char c = version.charAt(i);
            if (c == '.' || c == '-' || c == '_' || c == '+') {
                flush(tokens, current);
                continue;
            }
            boolean isDigit = c >= '0' && c <= '9';
            if (current.length() > 0 && isDigit != currentIsDigit) {
                flush(tokens, current);
            }
            currentIsDigit = isDigit;
            current.append(c);
        }
        flush(tokens, current);
        return tokens;
    }

    private static void flush(List<String> tokens, StringBuilder buffer) {
        if (buffer.length() > 0) {
            tokens.add(buffer.toString());
            buffer.setLength(0);
        }
    }
}
