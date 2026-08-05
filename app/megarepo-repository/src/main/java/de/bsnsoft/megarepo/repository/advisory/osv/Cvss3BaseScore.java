package de.bsnsoft.megarepo.repository.advisory.osv;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Derives the CVSS v3.0/v3.1 base score from a vector string.
 *
 * <p>OSV's {@code severity[]} publishes the CVSS <em>vector</em>, not a number:
 * {@code {"type":"CVSS_V3","score":"CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:C/C:H/I:H/A:H"}}.
 * Without this class the {@code CVSS_THRESHOLD} rule would have nothing to compare
 * against for any OSV-sourced advisory.
 *
 * <p>Computing the score is not the same as inventing one. The base score is a total
 * function of the eight base metrics — the specification defines it as arithmetic, the
 * same arithmetic NVD runs before publishing its {@code baseScore}. What this class never
 * does is produce a number when the vector is absent, incomplete or of a version it does
 * not implement: those all yield {@code null}, and {@code advisory.cvss_score} stays
 * empty rather than defaulting to a 0.0 that a policy would read as "harmless".
 *
 * <p>CVSS v2 and v4 vectors return {@code null}. v2 is legacy and OSV rarely carries it
 * alone; v4's scoring is a lookup table rather than a formula and is not worth carrying
 * until a feed actually needs it. The vector itself is still stored in both cases, so
 * nothing is lost and the score can be filled in later.
 *
 * @see <a href="https://www.first.org/cvss/v3.1/specification-document">CVSS v3.1
 *     specification, section 8.1</a>
 */
public final class Cvss3BaseScore {

    private static final Map<String, Double> ATTACK_VECTOR =
            Map.of("N", 0.85, "A", 0.62, "L", 0.55, "P", 0.2);
    private static final Map<String, Double> ATTACK_COMPLEXITY = Map.of("L", 0.77, "H", 0.44);
    private static final Map<String, Double> PRIVILEGES_UNCHANGED =
            Map.of("N", 0.85, "L", 0.62, "H", 0.27);
    private static final Map<String, Double> PRIVILEGES_CHANGED =
            Map.of("N", 0.85, "L", 0.68, "H", 0.50);
    private static final Map<String, Double> USER_INTERACTION = Map.of("N", 0.85, "R", 0.62);
    private static final Map<String, Double> IMPACT = Map.of("H", 0.56, "L", 0.22, "N", 0.0);

    private Cvss3BaseScore() {}

    /**
     * @param vector a CVSS vector string, e.g. {@code CVSS:3.1/AV:N/AC:L/...}
     * @return the base score rounded the way the specification rounds it, or {@code null}
     *     when the vector is null, not v3.x, or missing a base metric
     */
    public static Double fromVector(String vector) {
        if (vector == null) {
            return null;
        }
        String trimmed = vector.trim();
        if (!trimmed.regionMatches(true, 0, "CVSS:3.", 0, 7)) {
            return null;
        }

        Map<String, String> metrics = new HashMap<>();
        for (String part : trimmed.split("/")) {
            int colon = part.indexOf(':');
            if (colon > 0 && colon < part.length() - 1) {
                metrics.put(
                        part.substring(0, colon).toUpperCase(Locale.ROOT),
                        part.substring(colon + 1).toUpperCase(Locale.ROOT));
            }
        }

        String scope = metrics.get("S");
        if (scope == null || !(scope.equals("U") || scope.equals("C"))) {
            return null;
        }
        boolean scopeChanged = scope.equals("C");

        Double av = weight(ATTACK_VECTOR, metrics.get("AV"));
        Double ac = weight(ATTACK_COMPLEXITY, metrics.get("AC"));
        Double pr = weight(scopeChanged ? PRIVILEGES_CHANGED : PRIVILEGES_UNCHANGED, metrics.get("PR"));
        Double ui = weight(USER_INTERACTION, metrics.get("UI"));
        Double c = weight(IMPACT, metrics.get("C"));
        Double i = weight(IMPACT, metrics.get("I"));
        Double a = weight(IMPACT, metrics.get("A"));
        if (av == null || ac == null || pr == null || ui == null || c == null || i == null || a == null) {
            return null;
        }

        double iss = 1 - ((1 - c) * (1 - i) * (1 - a));
        double impact = scopeChanged
                ? 7.52 * (iss - 0.029) - 3.25 * Math.pow(iss - 0.02, 15)
                : 6.42 * iss;
        if (impact <= 0) {
            return 0.0;
        }
        double exploitability = 8.22 * av * ac * pr * ui;
        double raw = scopeChanged
                ? Math.min(1.08 * (impact + exploitability), 10)
                : Math.min(impact + exploitability, 10);
        return roundUp(raw);
    }

    /**
     * Metric weight, or null when the metric is absent or carries a value the
     * specification does not define. Written as a helper because the immutable maps above
     * throw on a null key rather than returning null — a missing metric is a normal
     * outcome here, not a programming error.
     */
    private static Double weight(Map<String, Double> weights, String value) {
        return value == null ? null : weights.get(value);
    }

    /** Textual severity band for a base score, per CVSS v3.1 section 5. */
    public static String severityBand(Double score) {
        if (score == null) {
            return null;
        }
        if (score <= 0.0) {
            return "NONE";
        }
        if (score < 4.0) {
            return "LOW";
        }
        if (score < 7.0) {
            return "MEDIUM";
        }
        if (score < 9.0) {
            return "HIGH";
        }
        return "CRITICAL";
    }

    /**
     * The specification's {@code Roundup}: the smallest number to one decimal place that
     * is not smaller than the input. Implemented on scaled integers as the v3.1 spec
     * prescribes, because the naive {@code ceil(x * 10) / 10} rounds 4.02 up to 4.1 for
     * inputs that are 4.0 with a floating-point crumb attached.
     */
    private static double roundUp(double input) {
        long scaled = Math.round(input * 100_000);
        if (scaled % 10_000 == 0) {
            return scaled / 100_000.0;
        }
        return (Math.floorDiv(scaled, 10_000) + 1) / 10.0;
    }
}
