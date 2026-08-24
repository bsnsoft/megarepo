package de.bsnsoft.megarepo.repository.advisory.ghsa;

/**
 * Extracts the OSV-shaped bounds ({@code introduced} / {@code fixed} /
 * {@code lastAffected}) from a GHSA {@code vulnerable_version_range} expression.
 *
 * <p>GHSA publishes ranges as a human-readable conjunction of comparators, e.g.
 * {@code ">= 2.0.1, < 2.15.0"}, {@code "< 4.17.20"}, {@code "= 1.2.3"}. The raw string is
 * always kept in {@code advisory_affected.version_range} — this parser only adds the
 * indexed boundaries. Whenever the two disagree the raw expression is the truth, which is
 * why nothing here ever throws or drops the entry: an expression this parser does not
 * understand simply leaves all three bounds null.
 *
 * <p>Two deliberate lossy points, both erring towards over- rather than under-reporting,
 * because a firewall that misses a vulnerable version is worse than one that flags an
 * extra one — and the raw expression is right there for an exact re-evaluation:
 * <ul>
 *   <li>{@code > X} has no representation ({@code introduced} is inclusive per OSV), so X
 *       itself is recorded as introduced.</li>
 *   <li>{@code fixed} and {@code lastAffected} are alternatives, never both: an explicit
 *       {@code first_patched_version} wins, then {@code < Y} → fixed, then {@code <= Y} →
 *       lastAffected.</li>
 * </ul>
 */
final class GhsaRanges {

    private GhsaRanges() {}

    /**
     * @param introduced first affected version, inclusive; null for "from the beginning"
     * @param fixed first fixed version, exclusive; null when no fix is known
     * @param lastAffected last affected version, inclusive; only set when fixed is null
     */
    record Bounds(String introduced, String fixed, String lastAffected) {

        static final Bounds NONE = new Bounds(null, null, null);
    }

    /**
     * Parses {@code range} and folds in an explicitly published first patched version.
     *
     * @param range the raw {@code vulnerable_version_range}, may be null
     * @param firstPatchedVersion GHSA's curated fix version, may be null
     */
    static Bounds parse(String range, String firstPatchedVersion) {
        String patched = trimToNull(firstPatchedVersion);
        String introduced = null;
        String fixed = null;
        String lastAffected = null;

        String expression = trimToNull(range);
        if (expression != null) {
            for (String part : expression.split(",")) {
                String constraint = part.trim();
                if (constraint.isEmpty()) {
                    continue;
                }
                if (startsWith(constraint, ">=")) {
                    introduced = firstNonNull(introduced, operand(constraint, 2));
                } else if (startsWith(constraint, "<=")) {
                    lastAffected = firstNonNull(lastAffected, operand(constraint, 2));
                } else if (startsWith(constraint, "==")) {
                    String exact = operand(constraint, 2);
                    introduced = firstNonNull(introduced, exact);
                    lastAffected = firstNonNull(lastAffected, exact);
                } else if (startsWith(constraint, ">")) {
                    // Exclusive lower bound; OSV's introduced is inclusive. Recording X
                    // itself widens the range by one version rather than dropping the
                    // bound entirely, which would widen it to "everything below".
                    introduced = firstNonNull(introduced, operand(constraint, 1));
                } else if (startsWith(constraint, "<")) {
                    fixed = firstNonNull(fixed, operand(constraint, 1));
                } else if (startsWith(constraint, "=")) {
                    String exact = operand(constraint, 1);
                    introduced = firstNonNull(introduced, exact);
                    lastAffected = firstNonNull(lastAffected, exact);
                }
                // Anything else (a bare version, a wildcard, an unknown operator) is left
                // to the raw expression.
            }
        }

        if (patched != null) {
            fixed = patched;
        }
        if (fixed != null) {
            lastAffected = null;
        }
        if (introduced == null && fixed == null && lastAffected == null) {
            return Bounds.NONE;
        }
        return new Bounds(introduced, fixed, lastAffected);
    }

    private static boolean startsWith(String constraint, String operator) {
        return constraint.startsWith(operator);
    }

    private static String operand(String constraint, int operatorLength) {
        return trimToNull(constraint.substring(operatorLength));
    }

    private static String firstNonNull(String current, String candidate) {
        return current != null ? current : candidate;
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String trimmed = s.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
