package de.bsnsoft.megarepo.repository.firewall.rule.impl;

import de.bsnsoft.megarepo.core.firewall.FirewallRuleType;
import de.bsnsoft.megarepo.repository.advisory.AdvisoryFinding;
import de.bsnsoft.megarepo.repository.firewall.FirewallRuleViolation;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRule;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRuleContext;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRuleOutcome;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRuleSettings;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * Denies a component named by a malicious-package advisory.
 *
 * <p>OSV publishes those under {@code MAL-}. Signed off by the customer as a
 * blocking signal in its own right: a package that exists to steal credentials
 * has no CVSS score to compare against, so {@code CVSS_THRESHOLD} alone would
 * serve it.
 *
 * <p>Same shape as {@link CvssThresholdRule} — Phase 1's logic, verbatim, moved
 * out of the engine's switch into a bean. Never quarantines: waiting does not
 * make a credential stealer acceptable.
 *
 * <p>Configuration: {@code {"idPrefixes": ["MAL-"], "minConfidence": "EXACT"}}.
 */
@Component
public class KnownMaliciousRule implements FirewallRule {

    /** Advisory id prefixes that mean "this package is malicious". */
    public static final List<String> DEFAULT_ID_PREFIXES = List.of("MAL-");

    @Override
    public FirewallRuleType ruleType() {
        return FirewallRuleType.KNOWN_MALICIOUS;
    }

    @Override
    public FirewallRuleOutcome evaluate(FirewallRuleContext context, FirewallRuleSettings settings) {
        List<AdvisoryFinding> qualifying = context.findingsAtLeast(settings.minConfidence());
        if (qualifying.isEmpty()) {
            return FirewallRuleOutcome.notMatched();
        }

        List<String> prefixes = settings.textList("idPrefixes", DEFAULT_ID_PREFIXES);
        TreeSet<String> ids = new TreeSet<>();
        for (AdvisoryFinding finding : qualifying) {
            for (String id : finding.advisoryIds()) {
                if (id != null && startsWithAny(id, prefixes)) {
                    ids.add(id);
                }
            }
        }
        if (ids.isEmpty()) {
            return FirewallRuleOutcome.notMatched();
        }

        String reason = ids.size() == 1
                ? "advisory %s flags this component as malicious".formatted(ids.first())
                : "advisories %s flag this component as malicious".formatted(String.join(", ", ids));
        return FirewallRuleOutcome.matched(new FirewallRuleViolation(
                FirewallRuleType.KNOWN_MALICIOUS, settings.action(), reason, List.copyOf(ids)));
    }

    private static boolean startsWithAny(String value, List<String> prefixes) {
        String upper = value.toUpperCase(Locale.ROOT);
        for (String prefix : prefixes) {
            if (upper.startsWith(prefix.toUpperCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
