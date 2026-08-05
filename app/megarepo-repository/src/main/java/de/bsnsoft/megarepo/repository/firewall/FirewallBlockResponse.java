package de.bsnsoft.megarepo.repository.firewall;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The 403 a blocked download gets.
 *
 * <h2>Why this is not just a status code</h2>
 *
 * The person who sees this response is not looking at MegaRepo. They are looking
 * at {@code mvn package} failing in a CI log, and what they get from a bare 403
 * is "Failed to transfer file … status code: 403". That produces a support
 * ticket, not a fix. The customer asked for this explicitly, so the body names
 * the component, the rule that denied it and the advisory ids — enough to decide
 * "upgrade the dependency" or "ask the administrator" without opening anything
 * else.
 *
 * <h2>Two shapes</h2>
 *
 * <ul>
 *   <li><b>JSON</b> when the client asked for it. npm prints the response body's
 *       {@code error} field verbatim in {@code npm ERR!}, so the human sentence
 *       goes there rather than into a machine-readable code; {@code code} carries
 *       the constant for anything that wants to branch on it.</li>
 *   <li><b>Plain text</b> otherwise, laid out as aligned lines. Maven's transport
 *       and most raw HTTP clients show the body as-is, and a wall of JSON in a
 *       build log is worse than no explanation.</li>
 * </ul>
 *
 * <p>Both carry the same facts, and the same summary sentence also goes into the
 * {@code X-MegaRepo-Firewall-*} headers, because NuGet's client shows neither
 * body reliably — {@code nuget}/{@code dotnet restore} report the status code
 * and little else. Headers are at least visible with
 * {@code -verbosity detailed} and in a proxy log.
 *
 * <p>Pure functions on purpose: rendering a refusal is the part most likely to
 * be read by a human under time pressure, and it should be assertable in a unit
 * test without a servlet container.
 */
public final class FirewallBlockResponse {

    /** Value of the {@code code}/{@code X-MegaRepo-Firewall-Reason} field. */
    public static final String ERROR_CODE = "FIREWALL_BLOCKED";

    /** Header stating that this response came from the firewall. */
    public static final String HEADER_FIREWALL = "X-MegaRepo-Firewall";

    /** Header naming the rule(s) that denied the download. */
    public static final String HEADER_RULE = "X-MegaRepo-Firewall-Rule";

    /** Header listing the advisory ids behind the decision. */
    public static final String HEADER_ADVISORIES = "X-MegaRepo-Firewall-Advisories";

    /** Header carrying the one-line summary, for clients that show no body. */
    public static final String HEADER_REASON = "X-MegaRepo-Firewall-Reason";

    /** Advisory ids beyond this many are elided from the headers, never from the body. */
    private static final int MAX_HEADER_ADVISORIES = 10;

    private FirewallBlockResponse() {}

    /** Whether the client would rather have JSON than text. */
    public static boolean prefersJson(String acceptHeader) {
        if (acceptHeader == null || acceptHeader.isBlank()) {
            return false;
        }
        String accept = acceptHeader.toLowerCase(Locale.ROOT);
        return accept.contains("application/json") || accept.contains("+json");
    }

    /** Content type for the chosen shape, charset included. */
    public static String contentType(boolean json) {
        return json ? "application/json;charset=UTF-8" : "text/plain;charset=UTF-8";
    }

    /**
     * One sentence naming the component, the rule and the advisories — the line
     * that has to survive being the only thing a client shows.
     */
    public static String summary(FirewallEvaluation evaluation) {
        FirewallDecision decision = evaluation.decision();
        String component = component(evaluation);

        if (decision.reason() == FirewallDecision.Reason.EVALUATION_UNAVAILABLE) {
            return "MegaRepo firewall blocked %s from '%s': the component could not be checked in time "
                    .formatted(component, orUnknown(evaluation.repositoryName()))
                    + "and this repository is configured to deny downloads it cannot check "
                    + "(fail_mode=FAIL_CLOSED).";
        }

        List<FirewallRuleViolation> blocking = decision.blockingViolations();
        if (blocking.isEmpty()) {
            return "MegaRepo firewall blocked %s from '%s'."
                    .formatted(component, orUnknown(evaluation.repositoryName()));
        }
        FirewallRuleViolation first = blocking.get(0);
        String ids = decision.advisoryIds().isEmpty()
                ? ""
                : " Advisories: " + String.join(", ", decision.advisoryIds()) + ".";
        String more = blocking.size() > 1
                ? " (%d further rule(s) also matched.)".formatted(blocking.size() - 1)
                : "";
        return "MegaRepo firewall blocked %s from '%s': %s — %s.%s%s".formatted(
                component,
                orUnknown(evaluation.repositoryName()),
                first.ruleType().name(),
                first.reason(),
                ids,
                more);
    }

    /** Headers to set alongside the 403. Values are ASCII-safe and single-line. */
    public static Map<String, String> headers(FirewallEvaluation evaluation) {
        FirewallDecision decision = evaluation.decision();
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(HEADER_FIREWALL, "blocked");
        headers.put(HEADER_REASON, headerSafe(summary(evaluation)));

        List<FirewallRuleViolation> blocking = decision.blockingViolations();
        if (blocking.isEmpty()) {
            headers.put(HEADER_RULE, decision.reason().name());
        } else {
            List<String> names = new ArrayList<>();
            for (FirewallRuleViolation violation : blocking) {
                names.add(violation.ruleType().name());
            }
            headers.put(HEADER_RULE, String.join(",", names));
        }

        List<String> ids = decision.advisoryIds();
        if (!ids.isEmpty()) {
            List<String> shown = ids.size() <= MAX_HEADER_ADVISORIES
                    ? ids
                    : new ArrayList<>(ids.subList(0, MAX_HEADER_ADVISORIES));
            String value = String.join(",", shown);
            if (ids.size() > MAX_HEADER_ADVISORIES) {
                value += ",+" + (ids.size() - MAX_HEADER_ADVISORIES) + " more";
            }
            headers.put(HEADER_ADVISORIES, headerSafe(value));
        }
        return headers;
    }

    /** The response body in the chosen shape. */
    public static String body(FirewallEvaluation evaluation, boolean json) {
        return json ? jsonBody(evaluation) : textBody(evaluation);
    }

    private static String textBody(FirewallEvaluation evaluation) {
        FirewallDecision decision = evaluation.decision();
        StringBuilder out = new StringBuilder();
        out.append("MegaRepo repository firewall: this download was blocked.\n\n");
        out.append("  Repository : ").append(orUnknown(evaluation.repositoryName())).append('\n');
        out.append("  Path       : ").append(orUnknown(evaluation.path())).append('\n');

        if (evaluation.componentKey() != null) {
            out.append("  Component  : ").append(evaluation.componentKey()).append('\n');
        }
        if (decision.policyName() != null) {
            out.append("  Policy     : ").append(decision.policyName()).append('\n');
        }

        if (decision.reason() == FirewallDecision.Reason.EVALUATION_UNAVAILABLE) {
            out.append("  Reason     : the firewall could not check this component in time, and this\n");
            out.append("               repository is configured to deny what it cannot check\n");
            out.append("               (fail_mode=FAIL_CLOSED).\n");
            out.append("\nRetry in a moment. If it keeps happening, the MegaRepo administrator should\n");
            out.append("check whether the advisory data and the database are healthy.\n");
            return out.toString();
        }

        List<FirewallRuleViolation> blocking = decision.blockingViolations();
        if (!blocking.isEmpty()) {
            out.append("  Violations :\n");
            for (FirewallRuleViolation violation : blocking) {
                out.append("    - ").append(violation.ruleType().name())
                        .append(" (").append(violation.action().name()).append("): ")
                        .append(violation.reason()).append('\n');
                if (!violation.advisoryIds().isEmpty()) {
                    out.append("      advisories: ")
                            .append(String.join(", ", violation.advisoryIds())).append('\n');
                }
            }
        }

        out.append("\nThe artifact itself is untouched — only this download was refused. Use a version\n");
        out.append("that is not affected, or ask your MegaRepo administrator to adjust the firewall\n");
        out.append("policy for this repository.\n");
        return out.toString();
    }

    private static String jsonBody(FirewallEvaluation evaluation) {
        FirewallDecision decision = evaluation.decision();
        StringBuilder json = new StringBuilder();
        json.append('{');
        // npm prints body.error verbatim, so the human sentence lives there.
        appendField(json, "error", summary(evaluation), true);
        appendField(json, "code", ERROR_CODE, false);
        appendField(json, "message", summary(evaluation), false);
        appendField(json, "repository", evaluation.repositoryName(), false);
        appendField(json, "path", evaluation.path(), false);
        appendField(json, "component", evaluation.componentKey(), false);
        appendField(json, "policy", decision.policyName(), false);
        appendField(json, "decision", decision.reason().name(), false);

        json.append(",\"advisoryIds\":").append(jsonArray(decision.advisoryIds()));
        json.append(",\"violations\":[");
        List<FirewallRuleViolation> blocking = decision.blockingViolations();
        for (int i = 0; i < blocking.size(); i++) {
            FirewallRuleViolation violation = blocking.get(i);
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"rule\":").append(jsonString(violation.ruleType().name()))
                    .append(",\"action\":").append(jsonString(violation.action().name()))
                    .append(",\"reason\":").append(jsonString(violation.reason()))
                    .append(",\"advisoryIds\":").append(jsonArray(violation.advisoryIds()))
                    .append('}');
        }
        json.append("]}");
        return json.toString();
    }

    private static void appendField(StringBuilder json, String name, String value, boolean first) {
        if (value == null) {
            return;
        }
        if (!first) {
            json.append(',');
        }
        json.append(jsonString(name)).append(':').append(jsonString(value));
    }

    private static String jsonArray(List<String> values) {
        StringBuilder array = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                array.append(',');
            }
            array.append(jsonString(values.get(i)));
        }
        return array.append(']').toString();
    }

    private static String component(FirewallEvaluation evaluation) {
        String key = evaluation.componentKey();
        if (key != null) {
            return key;
        }
        return "'" + orUnknown(evaluation.path()) + "'";
    }

    private static String orUnknown(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }

    /**
     * Header values must be one line of printable ASCII — a purl or an advisory
     * summary is neither guaranteed to be.
     */
    private static String headerSafe(String value) {
        StringBuilder safe = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\r' || c == '\n' || c == '\t') {
                safe.append(' ');
            } else if (c >= 0x20 && c < 0x7f) {
                safe.append(c);
            } else {
                safe.append('?');
            }
        }
        return safe.toString();
    }

    static String jsonString(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
