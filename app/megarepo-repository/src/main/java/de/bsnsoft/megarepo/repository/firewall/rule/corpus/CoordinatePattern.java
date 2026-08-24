package de.bsnsoft.megarepo.repository.firewall.rule.corpus;

import java.util.ArrayList;
import java.util.List;

/**
 * A {@code *}-glob over a coordinate string, as operators write it in a rule's
 * {@code config}.
 *
 * <p>{@code com.acme}, {@code com.acme.*}, {@code @acme/*}, {@code acme-*}.
 * {@code *} stands for any run of characters including none; there is no other
 * metacharacter, and nothing here is a regular expression — an operator typing a
 * namespace into a JSON array should not have to know that {@code .} means
 * something.
 *
 * <h2>{@code com.acme.*} also matches {@code com.acme}</h2>
 *
 * A prefix pattern includes the prefix itself. Read literally it would not, and
 * an operator who writes {@code ["com.acme.*"]} to protect their namespace would
 * be left with {@code com.acme} — the artifact most likely to be squatted —
 * unprotected, without any sign of it. For a rule whose entire purpose is to
 * refuse internal coordinates from upstream, the inclusive reading is the only
 * one whose failure mode is not a silent hole.
 */
public final class CoordinatePattern {

    private final String raw;
    private final String pattern;
    private final String prefixAlias;

    private CoordinatePattern(String raw, String pattern, String prefixAlias) {
        this.raw = raw;
        this.pattern = pattern;
        this.prefixAlias = prefixAlias;
    }

    /** Parses one pattern, or null if it is blank. */
    public static CoordinatePattern of(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        String lower = NameSkeleton.plain(trimmed);
        String alias = null;
        for (String suffix : new String[] {".*", "/*", "-*", ":*"}) {
            if (lower.length() > suffix.length() && lower.endsWith(suffix)) {
                alias = lower.substring(0, lower.length() - suffix.length());
                break;
            }
        }
        return new CoordinatePattern(trimmed, lower, alias);
    }

    /** Parses a list of patterns, skipping blanks. */
    public static List<CoordinatePattern> all(List<String> raws) {
        if (raws == null || raws.isEmpty()) {
            return List.of();
        }
        List<CoordinatePattern> patterns = new ArrayList<>(raws.size());
        for (String raw : raws) {
            CoordinatePattern parsed = of(raw);
            if (parsed != null) {
                patterns.add(parsed);
            }
        }
        return List.copyOf(patterns);
    }

    /** The pattern as the operator wrote it, for the evidence text. */
    public String raw() {
        return raw;
    }

    /** Whether this pattern covers the given coordinate string. */
    public boolean matches(String value) {
        if (value == null) {
            return false;
        }
        String candidate = NameSkeleton.plain(value);
        if (candidate.isEmpty()) {
            return false;
        }
        return globMatches(pattern, candidate)
                || (prefixAlias != null && prefixAlias.equals(candidate));
    }

    /** The first pattern in the list matching any of the candidate strings, or null. */
    public static CoordinatePattern firstMatch(List<CoordinatePattern> patterns, String... values) {
        if (patterns == null || patterns.isEmpty() || values == null) {
            return null;
        }
        for (CoordinatePattern pattern : patterns) {
            for (String value : values) {
                if (value != null && pattern.matches(value)) {
                    return pattern;
                }
            }
        }
        return null;
    }

    /**
     * Iterative glob match with backtracking on the last {@code *}. Linear in
     * the common case and never recursive, because this runs on the download
     * path and a pattern is operator input.
     */
    private static boolean globMatches(String pattern, String candidate) {
        int p = 0;
        int c = 0;
        int starAt = -1;
        int matchAt = 0;
        while (c < candidate.length()) {
            if (p < pattern.length() && pattern.charAt(p) == '*') {
                starAt = p++;
                matchAt = c;
            } else if (p < pattern.length() && pattern.charAt(p) == candidate.charAt(c)) {
                p++;
                c++;
            } else if (starAt >= 0) {
                p = starAt + 1;
                c = ++matchAt;
            } else {
                return false;
            }
        }
        while (p < pattern.length() && pattern.charAt(p) == '*') {
            p++;
        }
        return p == pattern.length();
    }
}
