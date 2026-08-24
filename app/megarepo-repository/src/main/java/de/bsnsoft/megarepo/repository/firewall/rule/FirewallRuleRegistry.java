package de.bsnsoft.megarepo.repository.firewall.rule;

import de.bsnsoft.megarepo.core.firewall.FirewallRuleType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Collects the {@link FirewallRule} beans and dispatches to them by rule type.
 *
 * <p>The counterpart of {@code PurlBuilder} for rules: Spring injects every
 * implementation on the classpath, this indexes them, and the policy engine asks
 * it rather than knowing which rules exist. Adding {@code LICENSE} means adding
 * one bean; nothing here changes.
 *
 * <h2>Two rules for the same type is a startup failure</h2>
 *
 * Not a warning, and not "last one wins". Two beans claiming {@code MIN_AGE}
 * means half the codebase is enforcing a rule the other half is not, and which
 * half depends on classpath ordering. That is not something to discover from a
 * download that was allowed on Tuesday and denied on Wednesday.
 *
 * <h2>Rule types with no bean</h2>
 *
 * Skipped and logged at debug, never treated as matched. Phase 1 established
 * this and the reason has not changed: a policy row for a rule nobody
 * implemented must not deny anything. It stays true during Phase 2 while the
 * rule packages land one at a time — a policy may perfectly well carry a
 * {@code TYPOSQUAT} row on a build where the typosquat bean does not exist yet.
 */
@Component
public class FirewallRuleRegistry {

    private static final Logger log = LoggerFactory.getLogger(FirewallRuleRegistry.class);

    private final Map<FirewallRuleType, FirewallRule> rules = new EnumMap<>(FirewallRuleType.class);

    public FirewallRuleRegistry(List<FirewallRule> beans) {
        for (FirewallRule rule : beans) {
            FirewallRuleType type = rule.ruleType();
            if (type == null) {
                throw new IllegalStateException(
                        "Firewall rule " + rule.getClass().getName() + " declares no rule type");
            }
            FirewallRule previous = rules.put(type, rule);
            if (previous != null) {
                throw new IllegalStateException(
                        "Two firewall rules claim %s: %s and %s. Exactly one implementation per rule "
                                .formatted(type, previous.getClass().getName(), rule.getClass().getName())
                                + "type — which of the two applies would otherwise depend on classpath order.");
            }
        }
        log.info("Repository firewall rule engine: {} of {} rule types implemented ({})",
                rules.size(), FirewallRuleType.values().length, implemented());
    }

    /** The rule for this type, if one is on the classpath. */
    public Optional<FirewallRule> find(FirewallRuleType ruleType) {
        return Optional.ofNullable(rules.get(ruleType));
    }

    /** Whether a policy row of this type will actually be evaluated. */
    public boolean isImplemented(FirewallRuleType ruleType) {
        return rules.containsKey(ruleType);
    }

    /**
     * The rule types this build can evaluate.
     *
     * <p>Read by the admin API so the policy editor can mark a rule type as
     * "configurable but not yet enforced" instead of offering an operator a
     * switch that does nothing.
     */
    public Set<FirewallRuleType> implemented() {
        // EnumSet.copyOf rejects an empty non-EnumSet collection, and an empty
        // registry is the normal state of a build that ships no rule beans yet.
        return rules.isEmpty()
                ? EnumSet.noneOf(FirewallRuleType.class)
                : EnumSet.copyOf(rules.keySet());
    }

    /**
     * Evaluates one configured rule against one component.
     *
     * <p>Never throws. A rule that does is contained here and reported as
     * {@link FirewallRuleOutcome#indeterminate}, not as a match and not as a
     * clean pass — the engine's fail mode then decides, which is the only
     * honest reading of "the firewall is broken in a way nobody planned for" and
     * matches how the enforcement path already treats an evaluation it could not
     * complete.
     *
     * @return the outcome; {@link FirewallRuleOutcome#notMatched()} when no bean
     *     implements the type, or when the rule does not apply to this component
     */
    public FirewallRuleOutcome evaluate(FirewallRuleContext context, FirewallRuleSettings settings) {
        if (settings == null || !settings.enabled() || settings.ruleType() == null) {
            return FirewallRuleOutcome.notMatched();
        }
        FirewallRule rule = rules.get(settings.ruleType());
        if (rule == null) {
            log.debug("Firewall policy rule type {} is configured but not implemented in this build; skipped",
                    settings.ruleType());
            return FirewallRuleOutcome.notMatched();
        }
        if (!context.hasPurl() && !rule.appliesToUnidentifiedComponents()) {
            return FirewallRuleOutcome.notMatched();
        }
        try {
            FirewallRuleOutcome outcome = rule.evaluate(context, settings);
            return outcome == null ? FirewallRuleOutcome.notMatched() : outcome;
        } catch (RuntimeException e) {
            log.warn("Firewall rule {} threw while evaluating {} in {}; treated as undecidable",
                    settings.ruleType(), context.componentKey(), context.repositoryName(), e);
            return FirewallRuleOutcome.indeterminate(
                    "the %s rule could not be evaluated".formatted(settings.ruleType()));
        }
    }
}
