package de.bsnsoft.megarepo.repository.firewall.rule;

import de.bsnsoft.megarepo.core.firewall.FirewallAction;
import de.bsnsoft.megarepo.core.firewall.FirewallRuleType;
import de.bsnsoft.megarepo.repository.advisory.MatchConfidence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * One configured policy rule, as a rule implementation sees it.
 *
 * <p>A value type rather than {@code FirewallPolicyRuleEntity} so that a
 * {@link FirewallRule} is a pure function of its inputs: no JPA on the classpath
 * of a rule, no lazy load on the request thread, and a unit test that constructs
 * one in a line.
 *
 * <h2>Reading {@code config}</h2>
 *
 * Rule parameters live in {@code firewall_policy_rule.config} (JSONB) precisely
 * so that adding one is never a migration — design section 3. The price is that
 * every value arrives untyped and may be nonsense, and the accessors below are
 * where that is dealt with, once, for every rule.
 *
 * <p><b>A malformed value falls back to the rule's default and logs.</b> Never an
 * exception, and never "match". A typo in a policy must not be able to deny every
 * download in a repository — an operator who mistypes {@code minScore} would
 * discover it as a global outage, at which point the firewall is switched off and
 * stays off.
 */
public record FirewallRuleSettings(
        UUID id,
        FirewallRuleType ruleType,
        FirewallAction action,
        Map<String, Object> config,
        boolean enabled) {

    private static final Logger log = LoggerFactory.getLogger(FirewallRuleSettings.class);

    public FirewallRuleSettings {
        action = action == null ? FirewallAction.WARN : action;
        config = config == null ? Map.of() : Map.copyOf(config);
    }

    /** A rule with no configuration — the shape a test or a built-in default wants. */
    public static FirewallRuleSettings of(FirewallRuleType ruleType, FirewallAction action) {
        return new FirewallRuleSettings(null, ruleType, action, Map.of(), true);
    }

    /** A rule with configuration. */
    public static FirewallRuleSettings of(
            FirewallRuleType ruleType, FirewallAction action, Map<String, Object> config) {
        return new FirewallRuleSettings(null, ruleType, action, config, true);
    }

    /** Whether this rule asks for the download to be denied when it matches. */
    public boolean blocks() {
        return action == FirewallAction.BLOCK;
    }

    /** A numeric parameter, or {@code fallback} when absent or unreadable. */
    public double number(String key, double fallback) {
        Object raw = config.get(key);
        if (raw instanceof Number value) {
            return value.doubleValue();
        }
        if (raw != null) {
            try {
                return Double.parseDouble(raw.toString().trim());
            } catch (NumberFormatException e) {
                warn(key, raw, fallback);
            }
        }
        return fallback;
    }

    /** A boolean parameter, or {@code fallback} when absent or unreadable. */
    public boolean flag(String key, boolean fallback) {
        Object raw = config.get(key);
        if (raw instanceof Boolean value) {
            return value;
        }
        if (raw == null) {
            return fallback;
        }
        String text = raw.toString().trim().toLowerCase(Locale.ROOT);
        if (text.equals("true") || text.equals("false")) {
            return text.equals("true");
        }
        warn(key, raw, fallback);
        return fallback;
    }

    /** A string parameter, trimmed, or {@code fallback} when absent or blank. */
    public String text(String key, String fallback) {
        Object raw = config.get(key);
        if (raw == null) {
            return fallback;
        }
        String value = raw.toString().trim();
        return value.isEmpty() ? fallback : value;
    }

    /**
     * A list-of-strings parameter — license ids, id prefixes, namespace patterns.
     *
     * <p>A single string is accepted as a one-element list: writing
     * {@code "denied": "GPL-3.0-only"} instead of {@code ["GPL-3.0-only"]} is the
     * most common thing an operator does by hand, and refusing it silently would
     * disable the rule rather than tell anyone.
     */
    public List<String> textList(String key, List<String> fallback) {
        Object raw = config.get(key);
        if (raw == null) {
            return fallback;
        }
        List<String> values = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object entry : list) {
                if (entry != null && !entry.toString().isBlank()) {
                    values.add(entry.toString().trim());
                }
            }
        } else if (!raw.toString().isBlank()) {
            values.add(raw.toString().trim());
        }
        return values.isEmpty() ? fallback : List.copyOf(values);
    }

    /**
     * A duration parameter.
     *
     * <p>Accepts an ISO-8601 duration ({@code "P7D"}, {@code "PT36H"}) and a bare
     * number of days ({@code 7}), because "minimum age" is a thing operators
     * think about in days and an ISO string is a thing they get wrong.
     */
    public Duration duration(String key, Duration fallback) {
        Object raw = config.get(key);
        if (raw == null) {
            return fallback;
        }
        if (raw instanceof Number days) {
            return Duration.ofMinutes(Math.round(days.doubleValue() * 24 * 60));
        }
        String text = raw.toString().trim();
        try {
            return Duration.parse(text.toUpperCase(Locale.ROOT));
        } catch (DateTimeParseException e) {
            try {
                return Duration.ofMinutes(Math.round(Double.parseDouble(text) * 24 * 60));
            } catch (NumberFormatException nested) {
                warn(key, raw, fallback);
                return fallback;
            }
        }
    }

    /**
     * How trustworthy an advisory match has to be for this rule to act on it.
     *
     * <p>Defaults by action, which is the Phase 1 behaviour and the reason the
     * V8 firewall's false positives do not come back: a {@code BLOCK} rule
     * demands {@link MatchConfidence#EXACT} — the advisory named the package by
     * purl — while a {@code WARN} rule accepts CPE-derived
     * {@link MatchConfidence#HEURISTIC} matches. Warn about everything, block
     * only on what was actually identified.
     *
     * <p>Overridable per rule with {@code "minConfidence"}, for the operator who
     * decides a CPE match is grounds enough.
     */
    public MatchConfidence minConfidence() {
        MatchConfidence byAction = blocks() ? MatchConfidence.EXACT : MatchConfidence.HEURISTIC;
        Object raw = config.get("minConfidence");
        if (raw == null) {
            return byAction;
        }
        try {
            return MatchConfidence.valueOf(raw.toString().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            warn("minConfidence", raw, byAction);
            return byAction;
        }
    }

    private void warn(String key, Object raw, Object fallback) {
        log.warn("Firewall rule {} has an unusable {} '{}'; using {}", ruleType, key, raw, fallback);
    }
}
