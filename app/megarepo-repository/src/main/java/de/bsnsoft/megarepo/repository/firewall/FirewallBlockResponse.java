package de.bsnsoft.megarepo.repository.firewall;

import de.bsnsoft.megarepo.core.firewall.FirewallApiPaths;
import de.bsnsoft.megarepo.core.firewall.FirewallQuarantineState;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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
 * <h2>What Phase 2 adds</h2>
 *
 * <ul>
 *   <li><b>The policy's name</b>, so the operator who is asked about it knows
 *       which one to look at. Suppressible with
 *       {@link FirewallBlockProperties#includePolicyName()} for an installation
 *       whose policy names mean nothing outside the security team.</li>
 *   <li><b>Advisory links</b>, not only ids: the id answers "what is wrong", the
 *       link answers "is it wrong for me".</li>
 *   <li><b>How to ask for an exemption</b> — a link to the request form and the
 *       API call behind it, built from {@link FirewallApiPaths#EXEMPTIONS} rather
 *       than from a string that drifts. The next step after a block should be a
 *       request, not a message to whoever owns the repository manager.</li>
 *   <li><b>What is being held, and until when.</b> A quarantined component is not
 *       refused, it is <em>waiting</em>, and telling a developer that it will be
 *       looked at again in eleven minutes is the difference between waiting and
 *       opening a ticket.</li>
 *   <li><b>A configurable sentence</b> from
 *       {@link FirewallBlockProperties#contactMessage()}, for the administrator
 *       who wants to name a team or a queue. It is <em>appended</em>: no
 *       configuration may produce a 403 that fails to say what was blocked and
 *       why.</li>
 * </ul>
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
 * {@code -verbosity detailed} and in a proxy log; the exemption link is among
 * them for the same reason.
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

    /** Header carrying the exemption-request link, for clients that show no body. */
    public static final String HEADER_EXEMPTION = "X-MegaRepo-Firewall-Exemption-Request";

    /** Header naming the quarantine state of a held component. */
    public static final String HEADER_QUARANTINE = "X-MegaRepo-Firewall-Quarantine";

    /** Advisory ids beyond this many are elided from the headers, never from the body. */
    private static final int MAX_HEADER_ADVISORIES = 10;

    private FirewallBlockResponse() {}

    /**
     * Everything about the deployment and the request that the rendered refusal
     * needs and the evaluation does not carry.
     *
     * @param viaRepository the group's name when the client addressed one, else
     *     null
     * @param requestBaseUrl the externally reachable base URL as this request saw
     *     it, used when {@link FirewallBlockProperties#baseUrl()} is not pinned.
     *     Blank simply leaves links relative, which is still usable
     * @param properties the deployment's block-body configuration
     */
    public record Context(String viaRepository, String requestBaseUrl, FirewallBlockProperties properties) {

        public Context {
            properties = properties == null ? FirewallBlockProperties.defaults() : properties;
            requestBaseUrl = requestBaseUrl == null ? "" : stripTrailingSlash(requestBaseUrl.trim());
        }

        /** A direct download on a deployment that configured nothing. */
        public static Context direct() {
            return new Context(null, null, null);
        }

        /** A download routed through a group, on a deployment that configured nothing. */
        public static Context via(String group) {
            return new Context(group, null, null);
        }

        /** The pinned base URL when there is one, else what the request implied. */
        public String baseUrl() {
            return properties.baseUrl().isEmpty() ? requestBaseUrl : properties.baseUrl();
        }

        private static String stripTrailingSlash(String value) {
            return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
        }
    }

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
        return summary(evaluation, Context.direct());
    }

    /**
     * The same sentence, naming the group the client addressed as well as the
     * member the verdict is about.
     *
     * <p>Both, not one. The repository in the developer's {@code settings.xml} is
     * the group, so a message naming only {@code maven-central-proxy} sends them
     * looking for a repository they have never configured; a message naming only
     * the group sends the operator to a repository whose firewall settings are
     * irrelevant to the decision. The two names are also the shortest possible
     * statement of the rule itself: <em>the member decides, the group only
     * routes.</em>
     *
     * @param viaRepository the group's name, or null for a direct download
     */
    public static String summary(FirewallEvaluation evaluation, String viaRepository) {
        return summary(evaluation, Context.via(viaRepository));
    }

    /** The same sentence, with the deployment's configuration in scope. */
    public static String summary(FirewallEvaluation evaluation, Context context) {
        FirewallDecision decision = evaluation.decision();
        String component = component(evaluation);
        String from = from(evaluation, context.viaRepository());

        if (decision.reason() == FirewallDecision.Reason.EVALUATION_UNAVAILABLE) {
            return "MegaRepo firewall blocked %s from %s: the component could not be checked in time "
                    .formatted(component, from)
                    + "and this repository is configured to deny downloads it cannot check "
                    + "(fail_mode=FAIL_CLOSED).";
        }

        if (decision.held() && decision.blockingViolations().isEmpty()) {
            // Held by an entry that was already on file: the rules did not run
            // again, so there is no matched rule to name — only the reason it is
            // being held under, which is the thing that will change by itself.
            return "MegaRepo firewall is holding %s from %s: %s.%s".formatted(
                    component, from, holdSentence(decision.hold()), nextLookSentence(decision.hold()));
        }

        List<FirewallRuleViolation> blocking = decision.blockingViolations();
        if (blocking.isEmpty()) {
            return "MegaRepo firewall blocked %s from %s.".formatted(component, from);
        }
        FirewallRuleViolation first = blocking.get(0);
        String ids = decision.advisoryIds().isEmpty()
                ? ""
                : " Advisories: " + String.join(", ", decision.advisoryIds()) + ".";
        String more = blocking.size() > 1
                ? " (%d further rule(s) also matched.)".formatted(blocking.size() - 1)
                : "";
        return "MegaRepo firewall %s %s from %s: %s — %s.%s%s%s".formatted(
                decision.held() ? "is holding" : "blocked",
                component,
                from,
                first.ruleType().name(),
                first.reason(),
                ids,
                more,
                decision.held() ? nextLookSentence(decision.hold()) : "");
    }

    /** Where the artifact came from, as one phrase. */
    private static String from(FirewallEvaluation evaluation, String viaRepository) {
        String member = "'" + orUnknown(evaluation.repositoryName()) + "'";
        return viaRepository == null || viaRepository.isBlank()
                ? member
                : "%s (via group '%s')".formatted(member, viaRepository);
    }

    /** Headers to set alongside the 403. Values are ASCII-safe and single-line. */
    public static Map<String, String> headers(FirewallEvaluation evaluation) {
        return headers(evaluation, Context.direct());
    }

    /** Headers to set alongside the 403. Values are ASCII-safe and single-line. */
    public static Map<String, String> headers(FirewallEvaluation evaluation, String viaRepository) {
        return headers(evaluation, Context.via(viaRepository));
    }

    /** Headers to set alongside the 403. Values are ASCII-safe and single-line. */
    public static Map<String, String> headers(FirewallEvaluation evaluation, Context context) {
        FirewallDecision decision = evaluation.decision();
        Map<String, String> headers = new LinkedHashMap<>();
        // Stays "blocked" for a held component too. This header is the stable
        // machine-readable answer to "was this refusal the firewall's?", and a
        // proxy or a CI plugin keying on it must not start missing refusals
        // because Phase 2 introduced a second kind. Which kind it is lives in
        // HEADER_QUARANTINE, where a reader who cares can find it.
        headers.put(HEADER_FIREWALL, "blocked");
        headers.put(HEADER_REASON, headerSafe(summary(evaluation, context)));

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

        FirewallDecision.Hold hold = decision.hold();
        if (hold != null && hold.reason() != null) {
            String value = hold.state() + ":" + hold.reason();
            if (hold.nextEvaluationAt() != null) {
                value += ";next=" + hold.nextEvaluationAt();
            }
            headers.put(HEADER_QUARANTINE, headerSafe(value));
        }

        String exemptionUrl = exemptionRequestUrl(evaluation, context);
        if (exemptionUrl != null) {
            headers.put(HEADER_EXEMPTION, headerSafe(exemptionUrl));
        }
        return headers;
    }

    /** The response body in the chosen shape. */
    public static String body(FirewallEvaluation evaluation, boolean json) {
        return body(evaluation, json, Context.direct());
    }

    /**
     * The response body in the chosen shape, naming the group the request was
     * addressed to when there was one.
     */
    public static String body(FirewallEvaluation evaluation, boolean json, String viaRepository) {
        return body(evaluation, json, Context.via(viaRepository));
    }

    /** The response body in the chosen shape, with the deployment's configuration in scope. */
    public static String body(FirewallEvaluation evaluation, boolean json, Context context) {
        return json ? jsonBody(evaluation, context) : textBody(evaluation, context);
    }

    private static String textBody(FirewallEvaluation evaluation, Context context) {
        FirewallDecision decision = evaluation.decision();
        FirewallBlockProperties properties = context.properties();
        String viaRepository = context.viaRepository();

        StringBuilder out = new StringBuilder();
        out.append(decision.held()
                ? "MegaRepo repository firewall: this download is being held.\n\n"
                : "MegaRepo repository firewall: this download was blocked.\n\n");
        if (viaRepository != null && !viaRepository.isBlank()) {
            // The group first: it is the name the client asked for, so it is the
            // one the reader recognises. The member below it explains where the
            // artifact actually came from and whose policy refused it.
            out.append("  Requested  : ").append(viaRepository).append(" (group)\n");
            out.append("  Resolved by: ").append(orUnknown(evaluation.repositoryName())).append('\n');
        } else {
            out.append("  Repository : ").append(orUnknown(evaluation.repositoryName())).append('\n');
        }
        out.append("  Path       : ").append(orUnknown(evaluation.path())).append('\n');

        if (evaluation.componentKey() != null) {
            out.append("  Component  : ").append(evaluation.componentKey()).append('\n');
        }
        if (properties.includePolicyName() && decision.policyName() != null) {
            out.append("  Policy     : ").append(decision.policyName()).append('\n');
        }

        if (decision.reason() == FirewallDecision.Reason.EVALUATION_UNAVAILABLE) {
            out.append("  Reason     : the firewall could not check this component in time, and this\n");
            out.append("               repository is configured to deny what it cannot check\n");
            out.append("               (fail_mode=FAIL_CLOSED).\n");
            out.append("\nRetry in a moment. If it keeps happening, the MegaRepo administrator should\n");
            out.append("check whether the advisory data and the database are healthy.\n");
            appendContactMessage(out, properties);
            return out.toString();
        }

        FirewallDecision.Hold hold = decision.hold();
        if (decision.held() && hold != null) {
            out.append("  Held       : ").append(holdSentence(hold)).append('\n');
            if (hold.nextEvaluationAt() != null) {
                out.append("  Next check : ").append(hold.nextEvaluationAt()).append('\n');
            }
        }

        List<FirewallRuleViolation> blocking = decision.blockingViolations();
        if (!blocking.isEmpty()) {
            out.append("  Violations :\n");
            for (FirewallRuleViolation violation : blocking) {
                out.append("    - ").append(violation.ruleType().name())
                        .append(" (").append(violation.action().name());
                // Otherwise "MIN_AGE (BLOCK): no publication date yet" reads as
                // though the rule found something, when it found nothing and the
                // repository's fail mode is what withheld the artifact.
                if (violation.undecided()) {
                    out.append(", could not be decided");
                }
                out.append("): ").append(violation.reason()).append('\n');
                if (!violation.advisoryIds().isEmpty()) {
                    out.append("      advisories: ")
                            .append(String.join(", ", violation.advisoryIds())).append('\n');
                    if (properties.includeAdvisoryLinks()) {
                        for (String id : violation.advisoryIds()) {
                            String url = advisoryUrl(id);
                            if (url != null) {
                                out.append("        ").append(id).append(": ").append(url).append('\n');
                            }
                        }
                    }
                }
            }
        }

        if (decision.held()) {
            out.append("\nNothing is broken and nobody has to do anything: a held component is checked\n");
            out.append("again by itself and released as soon as the reason above no longer applies.\n");
        } else {
            out.append("\nThe artifact itself is untouched — only this download was refused. Use a version\n");
            out.append("that is not affected, or ask your MegaRepo administrator to adjust the firewall\n");
            out.append("policy for this repository.\n");
        }

        appendExemptionSection(out, evaluation, context);
        appendContactMessage(out, properties);
        return out.toString();
    }

    /**
     * How to ask for this component to be let through, in both the form a human
     * clicks and the call a script makes.
     *
     * <p>Suppressed entirely when the deployment blanked
     * {@code exemption-request-url-template}, which is the right setting when
     * self-service requests are off: offering a link to a form nobody may use is
     * worse than offering none.
     */
    private static void appendExemptionSection(
            StringBuilder out, FirewallEvaluation evaluation, Context context) {

        String url = exemptionRequestUrl(evaluation, context);
        if (url == null) {
            return;
        }
        out.append("\nTo ask for an exemption:\n");
        out.append("  ").append(url).append('\n');
        out.append("  or POST ").append(context.baseUrl()).append(FirewallApiPaths.EXEMPTIONS)
                .append(" with componentKey=").append(orUnknown(evaluation.componentKey()))
                .append(", ruleType=").append(blockingRuleName(evaluation.decision()))
                .append(", repositoryId=").append(String.valueOf(evaluation.repositoryId()))
                .append('\n');
    }

    private static void appendContactMessage(StringBuilder out, FirewallBlockProperties properties) {
        if (properties.contactMessage().isEmpty()) {
            return;
        }
        out.append('\n').append(properties.contactMessage()).append('\n');
    }

    /**
     * The exemption-request link, or null when the deployment turned it off.
     *
     * <p>{@code {baseUrl}} expands to the pinned or request-derived base URL,
     * {@code {repository}} to the member that resolved the artifact (never the
     * group — the exemption is scoped to where the component lives),
     * {@code {componentKey}} to the purl or digest, {@code {rule}} to the rule
     * that denied it. All of them URL-encoded, because a purl contains
     * {@code /}, {@code @} and {@code :}.
     */
    static String exemptionRequestUrl(FirewallEvaluation evaluation, Context context) {
        FirewallBlockProperties properties = context.properties();
        if (!properties.offersExemptionRequests()) {
            return null;
        }
        String template = properties.exemptionRequestUrlTemplate();
        return template
                .replace("{baseUrl}", context.baseUrl())
                .replace("{repository}", encode(orUnknown(evaluation.repositoryName())))
                .replace("{repositoryId}", encode(String.valueOf(evaluation.repositoryId())))
                .replace("{componentKey}", encode(orUnknown(evaluation.componentKey())))
                .replace("{rule}", encode(blockingRuleName(evaluation.decision())));
    }

    /** The rule an exemption would have to name, or the decision's reason when no rule matched. */
    private static String blockingRuleName(FirewallDecision decision) {
        List<FirewallRuleViolation> blocking = decision.blockingViolations();
        if (!blocking.isEmpty()) {
            return blocking.get(0).ruleType().name();
        }
        return decision.reason().name();
    }

    /** One sentence saying what the hold is about. */
    private static String holdSentence(FirewallDecision.Hold hold) {
        if (hold == null || hold.reason() == null) {
            return "quarantined";
        }
        String state = hold.state() == FirewallQuarantineState.BLOCKED
                ? "blocked in the quarantine queue"
                : "quarantined";
        return switch (hold.reason()) {
            case MIN_AGE_NOT_MET -> state + " because it is newer than this repository's policy allows";
            case UNKNOWN_COMPONENT -> state + " because nothing is known about this component yet";
            case EVALUATION_INCOMPLETE -> state
                    + " because the firewall is still missing a fact it needs to judge it";
            case POLICY_VIOLATION -> state + " by policy";
        };
    }

    /** " It will be checked again at …" — the part that turns a refusal into a wait. */
    private static String nextLookSentence(FirewallDecision.Hold hold) {
        if (hold == null || hold.nextEvaluationAt() == null
                || hold.state() == FirewallQuarantineState.BLOCKED) {
            return "";
        }
        return " It will be checked again at " + hold.nextEvaluationAt() + ".";
    }

    /**
     * Where to read about an advisory.
     *
     * <p>Derived from the id's own prefix rather than configured: the three feeds
     * MegaRepo ingests each have exactly one canonical page per id, and an
     * operator should not have to configure a URL that is a property of the feed.
     * An id from somewhere else gets no link rather than a guessed one.
     */
    static String advisoryUrl(String advisoryId) {
        if (advisoryId == null || advisoryId.isBlank()) {
            return null;
        }
        String id = advisoryId.trim();
        String upper = id.toUpperCase(Locale.ROOT);
        if (upper.startsWith("CVE-")) {
            return "https://nvd.nist.gov/vuln/detail/" + id;
        }
        if (upper.startsWith("GHSA-")) {
            return "https://github.com/advisories/" + id;
        }
        if (upper.startsWith("MAL-") || upper.startsWith("OSV-") || upper.startsWith("PYSEC-")
                || upper.startsWith("GO-") || upper.startsWith("RUSTSEC-")) {
            return "https://osv.dev/vulnerability/" + id;
        }
        return null;
    }

    private static String jsonBody(FirewallEvaluation evaluation, Context context) {
        FirewallDecision decision = evaluation.decision();
        FirewallBlockProperties properties = context.properties();
        StringBuilder json = new StringBuilder();
        json.append('{');
        // npm prints body.error verbatim, so the human sentence lives there.
        appendField(json, "error", summary(evaluation, context), true);
        appendField(json, "code", ERROR_CODE, false);
        appendField(json, "message", summary(evaluation, context), false);
        // "repository" stays the member that holds the component: it is the one
        // an operator can act on, and a tool that keys on this field must not
        // start pointing at a group once a consumer switches to one.
        appendField(json, "repository", evaluation.repositoryName(), false);
        appendField(json, "viaRepository", context.viaRepository(), false);
        appendField(json, "path", evaluation.path(), false);
        appendField(json, "component", evaluation.componentKey(), false);
        if (properties.includePolicyName()) {
            appendField(json, "policy", decision.policyName(), false);
        }
        appendField(json, "decision", decision.reason().name(), false);
        json.append(",\"quarantined\":").append(decision.held());

        FirewallDecision.Hold hold = decision.hold();
        if (hold != null && hold.reason() != null) {
            json.append(",\"quarantine\":{")
                    .append("\"state\":").append(jsonString(hold.state().name()))
                    .append(",\"reason\":").append(jsonString(hold.reason().name()))
                    .append(",\"nextEvaluationAt\":")
                    .append(hold.nextEvaluationAt() == null
                            ? "null" : jsonString(hold.nextEvaluationAt().toString()))
                    .append(",\"hitCount\":").append(hold.hitCount())
                    .append('}');
        }

        json.append(",\"advisoryIds\":").append(jsonArray(decision.advisoryIds()));
        if (properties.includeAdvisoryLinks()) {
            json.append(",\"advisoryLinks\":{");
            boolean first = true;
            for (String id : decision.advisoryIds()) {
                String url = advisoryUrl(id);
                if (url == null) {
                    continue;
                }
                if (!first) {
                    json.append(',');
                }
                json.append(jsonString(id)).append(':').append(jsonString(url));
                first = false;
            }
            json.append('}');
        }

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
        json.append(']');

        String exemptionUrl = exemptionRequestUrl(evaluation, context);
        if (exemptionUrl != null) {
            json.append(",\"exemptionRequest\":{")
                    .append("\"url\":").append(jsonString(exemptionUrl))
                    .append(",\"api\":")
                    .append(jsonString(context.baseUrl() + FirewallApiPaths.EXEMPTIONS))
                    .append(",\"method\":\"POST\"")
                    .append(",\"componentKey\":").append(jsonString(evaluation.componentKey()))
                    .append(",\"ruleType\":").append(jsonString(blockingRuleName(decision)))
                    .append(",\"repositoryId\":")
                    .append(evaluation.repositoryId() == null
                            ? "null" : jsonString(evaluation.repositoryId().toString()))
                    .append('}');
        }
        if (!properties.contactMessage().isEmpty()) {
            json.append(",\"contact\":").append(jsonString(properties.contactMessage()));
        }
        json.append('}');
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

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
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
