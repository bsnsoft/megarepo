package de.bsnsoft.megarepo.repository.firewall.rule.corpus;

/**
 * Optimal string alignment distance with an upper bound and an early exit.
 *
 * <h2>Why transpositions count as one edit</h2>
 *
 * Plain Levenshtein calls {@code lodahs} two edits away from {@code lodash},
 * because swapping two letters is a delete plus an insert. But a swap is the
 * single most common thing a human does at a keyboard, and the whole rule is
 * built around a default threshold of one edit. With Levenshtein, a threshold of
 * 1 would miss every transposition and a threshold of 2 would let through
 * genuinely unrelated names — {@code minimist} vs {@code minimalist}. Counting a
 * swap as one edit (Damerau, in its optimal-string-alignment form) is what makes
 * "one edit" mean "one typo".
 *
 * <p>OSA rather than full Damerau-Levenshtein: OSA does not allow a substring to
 * be edited twice, so {@code ca} → {@code abc} is 3 for OSA and 2 for the
 * unrestricted variant. That difference needs two overlapping transpositions to
 * show up, which is no longer a typo by any reading, and OSA costs one row of
 * memory instead of a full matrix.
 *
 * <h2>Why it is bounded</h2>
 *
 * The rule compares one requested name against every corpus name of a similar
 * length, on the download path, inside a 20 ms budget for the whole firewall.
 * Nearly all of those comparisons are between names that have nothing to do with
 * each other, and the answer needed is not "how far apart" but "closer than
 * {@code max}". Both the length pre-check and the per-row minimum let the common
 * case abandon after a few cells.
 */
public final class BoundedEditDistance {

    /** Answer for "further apart than the bound". */
    public static final int EXCEEDS_BOUND = -1;

    private BoundedEditDistance() {
    }

    /**
     * The OSA distance between two strings, or {@link #EXCEEDS_BOUND} when it is
     * greater than {@code max}.
     *
     * @param a first string, never null
     * @param b second string, never null
     * @param max the largest distance the caller cares about; negative is
     *     treated as 0
     */
    public static int between(String a, String b, int max) {
        int bound = Math.max(0, max);
        int lenA = a.length();
        int lenB = b.length();
        if (Math.abs(lenA - lenB) > bound) {
            return EXCEEDS_BOUND;
        }
        if (a.equals(b)) {
            return 0;
        }
        if (lenA == 0 || lenB == 0) {
            int distance = Math.max(lenA, lenB);
            return distance <= bound ? distance : EXCEEDS_BOUND;
        }

        int[] twoAgo = new int[lenB + 1];
        int[] previous = new int[lenB + 1];
        int[] current = new int[lenB + 1];
        for (int j = 0; j <= lenB; j++) {
            previous[j] = j;
        }

        int previousRowMinimum = 0;
        for (int i = 1; i <= lenA; i++) {
            current[0] = i;
            int rowMinimum = current[0];
            char charA = a.charAt(i - 1);
            for (int j = 1; j <= lenB; j++) {
                char charB = b.charAt(j - 1);
                int cost = charA == charB ? 0 : 1;
                int value = Math.min(
                        Math.min(current[j - 1] + 1, previous[j] + 1),
                        previous[j - 1] + cost);
                if (i > 1 && j > 1 && charA == b.charAt(j - 2) && a.charAt(i - 2) == charB) {
                    value = Math.min(value, twoAgo[j - 2] + 1);
                }
                current[j] = value;
                rowMinimum = Math.min(rowMinimum, value);
            }
            if (rowMinimum > bound && previousRowMinimum > bound) {
                // A cell only ever draws from the two rows above it (the second
                // one through the transposition case), so once both of those are
                // entirely above the bound, no later row can come back under it.
                return EXCEEDS_BOUND;
            }
            previousRowMinimum = rowMinimum;
            int[] recycled = twoAgo;
            twoAgo = previous;
            previous = current;
            current = recycled;
        }

        int distance = previous[lenB];
        return distance <= bound ? distance : EXCEEDS_BOUND;
    }
}
