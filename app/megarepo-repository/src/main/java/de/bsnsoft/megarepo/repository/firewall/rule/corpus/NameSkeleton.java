package de.bsnsoft.megarepo.repository.firewall.rule.corpus;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The normalised form two package names are compared in.
 *
 * <p>A typosquat is a name that <em>looks</em> like another one, and "looks
 * like" is not a property of the code points. {@code python-dateutil} and
 * {@code pythondateutil} are the same word to a reader and two different strings
 * to a computer; {@code lodash} spelled with a Cyrillic {@code о} is one code
 * point away from the real thing and zero pixels away on screen. Comparing raw
 * names with an edit distance finds neither: the first is distance 1 in a place
 * nobody would call a typo, the second is distance 1 in a place nobody can see.
 *
 * <p>So both names are reduced to a <b>skeleton</b> first, and the distance is
 * measured between skeletons:
 *
 * <ol>
 *   <li>lower-cased ({@link Locale#ROOT}, so a Turkish locale cannot change what
 *       the firewall thinks {@code I} is);</li>
 *   <li>look-alike characters folded onto one ASCII representative — Cyrillic and
 *       Greek homoglyphs, and the digits that stand in for letters
 *       ({@code 0}/{@code o}, {@code 1}/{@code l}, {@code 3}/{@code e},
 *       {@code 5}/{@code s});</li>
 *   <li>the two-character look-alikes {@code rn} → {@code m} and {@code vv} →
 *       {@code w}, which are the classic ones and invisible in most
 *       proportional fonts;</li>
 *   <li>separators removed — {@code -}, {@code _}, {@code .}, {@code ~},
 *       {@code +}, whitespace — and a leading npm {@code @} dropped.</li>
 * </ol>
 *
 * <p>Two names with the <em>same</em> skeleton and different raw spellings are
 * therefore distance 0, which is deliberately the strongest signal this class
 * can produce: it is exactly the hyphen-variant and homoglyph case
 * ({@code python-dateutil} vs {@code pythondateutil}, {@code lodash} vs
 * {@code lоdash}), and those are squats rather than typos — nobody types a
 * Cyrillic {@code о} by accident.
 *
 * <p>The folding is lossy on purpose, and the loss has a direction: it can make
 * two <em>different</em> names look alike (a false positive for the rule that
 * uses it, which is why that rule defaults to WARN and states its evidence), and
 * it can never make two look-alike names look different. For a heuristic whose
 * only job is to raise a question, that is the right direction.
 */
public final class NameSkeleton {

    private static final String SEPARATORS = "-_.~+ \t/";

    private NameSkeleton() {
    }

    /**
     * The comparison form of a package name or namespace.
     *
     * @param value a raw name; null or blank yields an empty string, so callers
     *     never have to null-check the result
     */
    public static String of(String value) {
        if (value == null) {
            return "";
        }
        String lower = value.trim().toLowerCase(Locale.ROOT);
        if (lower.isEmpty()) {
            return "";
        }
        StringBuilder folded = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (SEPARATORS.indexOf(c) >= 0 || (c == '@' && folded.isEmpty())) {
                continue;
            }
            char mapped = fold(c);
            // Two-character look-alikes are collapsed as they are produced, so
            // "rn" typed as part of a longer word ("modernizr" -> "modemizr")
            // is folded the same way whichever side of the comparison it is on.
            if (mapped == 'n' && !folded.isEmpty() && folded.charAt(folded.length() - 1) == 'r') {
                folded.setCharAt(folded.length() - 1, 'm');
                continue;
            }
            if (mapped == 'v' && !folded.isEmpty() && folded.charAt(folded.length() - 1) == 'v') {
                folded.setCharAt(folded.length() - 1, 'w');
                continue;
            }
            folded.append(mapped);
        }
        return folded.toString();
    }

    /**
     * The plain comparison form: trimmed and lower-cased, nothing else.
     *
     * <p>Used where identity is meant rather than resemblance — "is this the
     * very package that was requested", "is this exact namespace published in a
     * hosted repository". Folding there would let a look-alike coordinate pass
     * as the internal one, which is the opposite of what a firewall wants.
     */
    public static String plain(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * The separator-delimited segments of a name, lower-cased and skeletonised
     * individually.
     *
     * <p>{@code lodash.get} is {@code [lodash, get]}. Segments are what make a
     * package <em>family</em> visible — {@code lodash.get}, {@code lodash.set}
     * and {@code lodash.merge} are siblings published by one author and one
     * edit apart from each other, and a rule that cannot see the family reports
     * every one of them as a typosquat of the next.
     */
    public static List<String> segments(String value) {
        if (value == null) {
            return List.of();
        }
        List<String> segments = new ArrayList<>(4);
        StringBuilder current = new StringBuilder();
        String lower = value.trim().toLowerCase(Locale.ROOT);
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (SEPARATORS.indexOf(c) >= 0) {
                if (!current.isEmpty()) {
                    segments.add(of(current.toString()));
                    current.setLength(0);
                }
                continue;
            }
            if (c == '@' && current.isEmpty() && segments.isEmpty()) {
                continue;
            }
            current.append(c);
        }
        if (!current.isEmpty()) {
            segments.add(of(current.toString()));
        }
        segments.removeIf(String::isEmpty);
        return List.copyOf(segments);
    }

    /**
     * Whether two names differ only in a trailing run of digits — {@code lang}
     * and {@code lang3}, {@code vue2} and {@code vue3}, {@code es5} and
     * {@code es6}.
     *
     * <p>Version-suffixed siblings are one edit apart and are not typosquats:
     * ecosystems are full of them ({@code commons-lang} / {@code commons-lang3},
     * {@code python2-…} / {@code python3-…}), an operator sees them every day,
     * and a rule that flags them teaches its reader to ignore it. The cost is a
     * blind spot for a squat that appends a digit, which is accepted knowingly —
     * a heuristic that cries wolf is worth less than one that misses a case.
     */
    public static boolean differsOnlyInTrailingDigits(String left, String right) {
        String a = plainWithoutSeparators(left);
        String b = plainWithoutSeparators(right);
        String aStem = stripTrailingDigits(a);
        String bStem = stripTrailingDigits(b);
        if (aStem.isEmpty() || bStem.isEmpty()) {
            return false;
        }
        return aStem.equals(bStem) && !a.equals(b);
    }

    private static String plainWithoutSeparators(String value) {
        String lower = plain(value);
        StringBuilder builder = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (SEPARATORS.indexOf(c) < 0 && !(c == '@' && builder.isEmpty())) {
                builder.append(c);
            }
        }
        return builder.toString();
    }

    private static String stripTrailingDigits(String value) {
        int end = value.length();
        while (end > 0 && Character.isDigit(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end);
    }

    /**
     * One character folded onto its ASCII look-alike.
     *
     * <p>Deliberately a fixed table rather than Unicode's full confusables data:
     * the set below covers the scripts an attacker actually reaches for on a
     * registry that accepts non-ASCII names, is auditable in one screen, and
     * cannot start folding new pairs together when a JDK is upgraded.
     */
    private static char fold(char c) {
        return switch (c) {
            // Digits used as letters.
            case '0' -> 'o';
            case '1' -> 'l';
            case '3' -> 'e';
            case '5' -> 's';
            // Cyrillic.
            case 'а' -> 'a';
            case 'в' -> 'b';
            case 'с' -> 'c';
            case 'ԁ' -> 'd';
            case 'е', 'ё' -> 'e';
            case 'һ' -> 'h';
            case 'і', 'ї' -> 'i';
            case 'ј' -> 'j';
            case 'к' -> 'k';
            case 'ӏ' -> 'l';
            case 'м' -> 'm';
            case 'о' -> 'o';
            case 'р' -> 'p';
            case 'ѕ' -> 's';
            case 'т' -> 't';
            case 'х' -> 'x';
            case 'у' -> 'y';
            case 'ѵ' -> 'v';
            // Greek.
            case 'α' -> 'a';
            case 'β' -> 'b';
            case 'ε' -> 'e';
            case 'ι' -> 'i';
            case 'κ' -> 'k';
            case 'ν' -> 'v';
            case 'ο' -> 'o';
            case 'ρ' -> 'p';
            case 'σ' -> 'o';
            case 'τ' -> 't';
            case 'υ' -> 'u';
            case 'χ' -> 'x';
            // Latin with diacritics and dotless forms.
            case 'à', 'á', 'â', 'ã', 'ä', 'å' -> 'a';
            case 'ç' -> 'c';
            case 'è', 'é', 'ê', 'ë' -> 'e';
            case 'ì', 'í', 'î', 'ï', 'ı' -> 'i';
            case 'ñ' -> 'n';
            case 'ò', 'ó', 'ô', 'õ', 'ö', 'ø' -> 'o';
            case 'ù', 'ú', 'û', 'ü' -> 'u';
            case 'ý', 'ÿ' -> 'y';
            case 'ł' -> 'l';
            case 'ß' -> 'b';
            default -> c;
        };
    }
}
