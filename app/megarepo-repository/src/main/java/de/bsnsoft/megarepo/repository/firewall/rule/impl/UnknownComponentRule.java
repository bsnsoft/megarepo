package de.bsnsoft.megarepo.repository.firewall.rule.impl;

import de.bsnsoft.megarepo.core.firewall.FirewallQuarantineReason;
import de.bsnsoft.megarepo.core.firewall.FirewallRuleType;
import de.bsnsoft.megarepo.repository.advisory.AdvisoryFinding;
import de.bsnsoft.megarepo.repository.advisory.MatchConfidence;
import de.bsnsoft.megarepo.repository.firewall.FirewallRuleViolation;
import de.bsnsoft.megarepo.repository.firewall.identity.ComponentIdentity;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRule;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRuleContext;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRuleOutcome;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRuleSettings;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@code UNKNOWN_COMPONENT}: the firewall holds no advisory knowledge about the
 * component, or cannot name it as a package at all.
 *
 * <h2>What this rule actually says</h2>
 *
 * Not "this component is bad" — the opposite: <em>nothing is known about it</em>.
 * That is a statement about the firewall rather than about the component, which
 * is why a match is held ({@link #quarantineOnMatch()}, reason
 * {@link FirewallQuarantineReason#UNKNOWN_COMPONENT}) rather than refused: the
 * silence ends when an advisory sync brings data in, or when an operator decides
 * the silence is acceptable and grants an exemption.
 *
 * <p>It is deliberately a drastic rule. Most components no advisory names are
 * perfectly fine, so a policy that switches this on has chosen a default-deny
 * posture and expects a review queue. Everything below exists to keep that queue
 * finite and honest.
 *
 * <h2>Which components it looks at</h2>
 *
 * <ul>
 *   <li><b>Proxied components only</b>, unless {@code includeHostedComponents} says
 *       otherwise. A package a colleague published into a hosted repository is not
 *       "unknown", it is <em>ours</em>, and no advisory feed will ever name it —
 *       so a quarantine entry for it can never resolve on its own. The design is
 *       explicit that a queue which fills with entries nobody will ever release
 *       stops being read, and an internal artifact held forever is exactly that
 *       entry.</li>
 *   <li><b>Never an upload.</b> Refusing to publish a release because no advisory
 *       source has heard of it yet would deny every release on the day it is
 *       made.</li>
 *   <li><b>Components with no purl</b> — a raw blob, a Docker layer — match, since
 *       an unidentifiable artifact is what this rule is for. Formats that
 *       structurally have no coordinates can be exempted with
 *       {@code allowUnidentifiedFormats}.</li>
 * </ul>
 *
 * <h2>Configuration</h2>
 *
 * <pre>
 *   {"allowUnidentifiedFormats": ["raw"]}   formats whose artifacts never have coordinates
 *   {"includeHostedComponents": true}       also judge components published here
 *   {"minConfidence": "HEURISTIC"}          count CPE-derived matches as knowledge
 * </pre>
 *
 * <h2>Why {@code minConfidence} still applies to a rule that matches on absence</h2>
 *
 * The question the rule asks is not "does any row mention this name" but "does
 * this firewall hold advisory knowledge it would act on for this component".
 * A CPE-derived {@link MatchConfidence#HEURISTIC} finding is precisely the
 * knowledge Phase 1 decided a blocking rule may <em>not</em> act on — it matched
 * on a product name with no ecosystem and no publisher — so letting one certify a
 * component as "known" would mean an unrelated package sharing a name silently
 * clears it. The default therefore follows the action, as everywhere else in the
 * engine, and the violation reason states how many weaker findings were
 * disregarded so an operator can see why and set {@code minConfidence} if they
 * disagree.
 *
 * <h2>What it never does</h2>
 *
 * It never reports {@code INDETERMINATE}. Missing <em>advisory</em> data is this
 * rule's subject and a settled input; missing <em>component facts</em> — the
 * publication date and licenses that {@code MIN_AGE} and {@code LICENSE} read —
 * are none of its business. Confusing the two would turn every unresolved facts
 * row into an {@code EVALUATION_INCOMPLETE} quarantine on top of the entry this
 * rule already writes.
 */
@Component
public class UnknownComponentRule implements FirewallRule {

    /** Config key listing formats whose artifacts legitimately have no coordinates. */
    static final String CONFIG_ALLOW_UNIDENTIFIED_FORMATS = "allowUnidentifiedFormats";

    /** Config key opting hosted (locally published) components back in. */
    static final String CONFIG_INCLUDE_HOSTED = "includeHostedComponents";

    @Override
    public FirewallRuleType ruleType() {
        return FirewallRuleType.UNKNOWN_COMPONENT;
    }

    /** True: the data may still arrive, so the component is held rather than refused. */
    @Override
    public boolean quarantineOnMatch() {
        return true;
    }

    @Override
    public FirewallQuarantineReason quarantineReason() {
        return FirewallQuarantineReason.UNKNOWN_COMPONENT;
    }

    /** True — the only rule for which an unidentifiable artifact is the subject. */
    @Override
    public boolean appliesToUnidentifiedComponents() {
        return true;
    }

    @Override
    public FirewallRuleOutcome evaluate(FirewallRuleContext context, FirewallRuleSettings settings) {
        if (context.upload()) {
            return FirewallRuleOutcome.notMatched();
        }
        if (!context.fromProxy() && !settings.flag(CONFIG_INCLUDE_HOSTED, false)) {
            return FirewallRuleOutcome.notMatched();
        }

        if (context.identity() instanceof ComponentIdentity.Unidentified unidentified) {
            if (allowedFormats(settings).contains(normalize(unidentified.format()))) {
                return FirewallRuleOutcome.notMatched();
            }
            return matched(settings,
                    "could not be identified as a package — no coordinates and no content digest, "
                            + "so no advisory source can be asked about it");
        }
        if (context.identity() instanceof ComponentIdentity.Hash hash) {
            // A digest identifies the artifact exactly and describes it not at
            // all. allowUnidentifiedFormats cannot reach this case: a hash
            // identity carries no format and the context does not carry one
            // either, so there is nothing to compare the list against.
            return matched(settings,
                    "is identified only by its %s digest; no advisory source can be queried for a content hash"
                            .formatted(hash.algorithm()));
        }

        MatchConfidence minConfidence = settings.minConfidence();
        List<AdvisoryFinding> qualifying = context.findingsAtLeast(minConfidence);
        if (!qualifying.isEmpty()) {
            return FirewallRuleOutcome.notMatched();
        }

        int disregarded = context.findings().size();
        String reason = disregarded == 0
                ? "no advisory source has any entry for %s".formatted(context.componentKey())
                : ("no advisory source names %s with %s confidence (%d weaker %s disregarded)")
                        .formatted(
                                context.componentKey(),
                                minConfidence.name().toLowerCase(Locale.ROOT),
                                disregarded,
                                disregarded == 1 ? "match was" : "matches were");
        return matched(settings, reason);
    }

    private static FirewallRuleOutcome matched(FirewallRuleSettings settings, String reason) {
        return FirewallRuleOutcome.matched(new FirewallRuleViolation(
                FirewallRuleType.UNKNOWN_COMPONENT, settings.action(), reason, List.of()));
    }

    private static Set<String> allowedFormats(FirewallRuleSettings settings) {
        return settings.textList(CONFIG_ALLOW_UNIDENTIFIED_FORMATS, List.of()).stream()
                .map(UnknownComponentRule::normalize)
                .collect(Collectors.toSet());
    }

    /** Format keys are compared the way {@code PurlBuilder} indexes them: lowercased. */
    private static String normalize(String format) {
        return format == null ? "" : format.trim().toLowerCase(Locale.ROOT);
    }
}
