package de.bsnsoft.megarepo.rest.dto.firewall;

import de.bsnsoft.megarepo.core.firewall.FirewallRuleType;

import java.util.List;

/**
 * What the policy editor needs to know about one rule type.
 *
 * <p>Served from {@code /api/v1/firewall/rule-types}. The editor cannot hardcode
 * this: rule types are added in code without a migration, and a UI shipped
 * against an older list would either hide a rule the server enforces or offer one
 * it does not.
 *
 * @param implemented whether this build has a bean for the type. A rule of an
 *     unimplemented type is skipped, never enforced — so the editor renders it as
 *     "not enforced by this version" instead of as a working switch
 * @param heuristic whether findings from this rule are guesses about intent
 *     rather than statements of fact. True for {@code TYPOSQUAT} and
 *     {@code NAMESPACE_CONFUSION}; the design commits to labelling them as such
 *     wherever they are shown, and the editor is one of those places — it is
 *     where somebody decides to set one of them to BLOCK
 * @param quarantines whether a match holds the component in quarantine rather
 *     than refusing it outright, so the editor can say which rules feed the queue
 * @param requiresComponentFacts whether the rule needs the publication date or
 *     declared license from the background facts resolver. Worth showing:
 *     switching on {@code MIN_AGE} in an installation where
 *     {@code megarepo.firewall.facts.enabled} is off produces an endlessly
 *     indeterminate rule, and the editor is where to catch that
 * @param configSchema the parameters this rule reads, so the editor can render
 *     fields instead of a JSON textarea
 */
public record FirewallRuleTypeXO(
        FirewallRuleType ruleType,
        String label,
        String description,
        boolean implemented,
        boolean heuristic,
        boolean quarantines,
        boolean requiresComponentFacts,
        List<ConfigField> configSchema) {

    /**
     * One configurable parameter of a rule.
     *
     * @param key the JSON key in {@code firewall_policy_rule.config}
     * @param type {@code number}, {@code boolean}, {@code string},
     *     {@code stringList}, {@code duration} or {@code enum} — matching the
     *     accessors on {@code FirewallRuleSettings}, so the editor and the engine
     *     cannot drift apart about what a value means
     * @param defaultValue what the rule uses when the key is absent, rendered as
     *     the field's placeholder
     * @param allowedValues for {@code enum}; empty otherwise
     */
    public record ConfigField(
            String key,
            String type,
            String label,
            String description,
            Object defaultValue,
            boolean required,
            List<String> allowedValues) {}
}
