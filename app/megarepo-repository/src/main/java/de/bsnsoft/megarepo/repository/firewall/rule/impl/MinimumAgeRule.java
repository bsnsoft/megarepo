package de.bsnsoft.megarepo.repository.firewall.rule.impl;

import de.bsnsoft.megarepo.core.firewall.FirewallQuarantineReason;
import de.bsnsoft.megarepo.core.firewall.FirewallRuleType;
import de.bsnsoft.megarepo.repository.firewall.FirewallRuleViolation;
import de.bsnsoft.megarepo.repository.firewall.facts.ComponentFacts;
import de.bsnsoft.megarepo.repository.firewall.facts.ComponentFactsService;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRule;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRuleContext;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRuleOutcome;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRuleSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * {@code MIN_AGE}: a component version that was published more recently than the
 * policy allows is held until it is old enough.
 *
 * <h2>What the rule is for</h2>
 *
 * Almost every published supply-chain attack of the last years was found and
 * pulled within hours or days of appearing. A cooling-off period is therefore
 * the one control that costs an organisation nothing but the freshness of its
 * dependencies and removes the entire window in which those attacks work. It is
 * also the archetypal <em>quarantine</em> verdict rather than a block: nothing is
 * wrong with the component, it is merely unproven, and it stops being unproven by
 * itself at a moment the policy already states — hence {@link #quarantineOnMatch()}
 * and {@link FirewallQuarantineReason#MIN_AGE_NOT_MET}.
 *
 * <h2>Configuration</h2>
 *
 * <pre>
 *   {"minAge": "P7D"}   ISO-8601 duration
 *   {"minAge": 7}       bare number of days — how operators actually think
 * </pre>
 *
 * An unreadable value falls back to {@link #DEFAULT_MIN_AGE} and logs, per
 * {@link FirewallRuleSettings}: a typo in a policy must not be able to hold every
 * download in a repository.
 *
 * <h2>Why a missing publication date is not "old enough"</h2>
 *
 * The date comes from package metadata, which is resolved in the background —
 * see {@link ComponentFacts}. On the request path it may simply not be there
 * yet, and neither binary answer is honest: "not matched" serves a package that
 * may be four minutes old, which is precisely the attack this rule exists to
 * stop, and "matched" holds every component in the repository until the resolver
 * has caught up. So the rule reports
 * {@link FirewallRuleOutcome.Kind#INDETERMINATE} and asks for the resolution;
 * the engine, which knows the repository's fail mode, decides what that costs.
 *
 * <p>A <em>settled</em> absence is the opposite case and is answered
 * {@code NOT_MATCHED}: a {@code RESOLVED} row whose metadata simply states no
 * date, or an {@code UNAVAILABLE} one whose ecosystem publishes none, is a date
 * that is never coming. Holding a component forever waiting for it would be a
 * bug, not caution.
 */
@Component
public class MinimumAgeRule implements FirewallRule {

    private static final Logger log = LoggerFactory.getLogger(MinimumAgeRule.class);

    /** Config key holding the minimum age. */
    static final String CONFIG_MIN_AGE = "minAge";

    /**
     * Minimum age used when the rule configures none.
     *
     * <p>Seven days is the interval the published guidance around this control
     * settles on and the one the customer named: long enough to cover the window
     * in which malicious releases are typically found and pulled, short enough
     * that a team does not notice it on anything but a same-day upgrade.
     */
    static final Duration DEFAULT_MIN_AGE = Duration.ofDays(7);

    /**
     * Resolved once at construction and possibly null.
     *
     * <p>The facts store is a separate work package and an installation may be
     * built without one; a rule whose absence stops the application from starting
     * would make the firewall's own modularity a deployment risk. Without it the
     * rule still answers — {@code INDETERMINATE} for anything unresolved, which
     * is the same answer it gives while a resolution is in flight.
     */
    private final ComponentFactsService facts;

    public MinimumAgeRule(ObjectProvider<ComponentFactsService> facts) {
        this.facts = facts.getIfAvailable();
    }

    @Override
    public FirewallRuleType ruleType() {
        return FirewallRuleType.MIN_AGE;
    }

    /** True: the component gets older on its own, which is what quarantine is for. */
    @Override
    public boolean quarantineOnMatch() {
        return true;
    }

    @Override
    public FirewallQuarantineReason quarantineReason() {
        return FirewallQuarantineReason.MIN_AGE_NOT_MET;
    }

    @Override
    public FirewallRuleOutcome evaluate(FirewallRuleContext context, FirewallRuleSettings settings) {
        Duration minAge = settings.duration(CONFIG_MIN_AGE, DEFAULT_MIN_AGE);

        // A minimum age of zero holds nothing, so the rule has nothing to ask
        // about — and answering INDETERMINATE for an unresolved component would
        // let a rule that cannot possibly match quarantine a fail-closed
        // repository. An inert rule is inert on every path, not just the
        // matching one.
        if (minAge.isZero() || minAge.isNegative()) {
            return FirewallRuleOutcome.notMatched();
        }

        // The registry already skips this rule for a component with no purl. The
        // check is repeated because it is a statement about the rule and not
        // about the caller: a raw blob has no publication date, and reporting
        // INDETERMINATE for one would hold it forever.
        if (!context.hasPurl()) {
            return FirewallRuleOutcome.notMatched();
        }

        // An upload is the moment of publication: its age is zero by
        // construction, so a minimum-age rule applied to it would refuse every
        // release an organisation makes into its own hosted repository. The rule
        // guards against fresh *upstream* packages, and an upload is not one.
        if (context.upload()) {
            return FirewallRuleOutcome.notMatched();
        }

        ComponentFacts componentFacts = context.facts();
        if (componentFacts.isIndeterminate()) {
            requestResolution(context);
            return FirewallRuleOutcome.indeterminate(
                    "the publication date of %s has not been resolved yet"
                            .formatted(context.componentKey()));
        }

        Optional<Duration> age = componentFacts.age(context.evaluatedAt());
        if (age.isEmpty()) {
            // Settled, and there is no date: the metadata is silent or the
            // ecosystem publishes none. A settled "we cannot know" is an answer
            // the rule has to live with.
            return FirewallRuleOutcome.notMatched();
        }
        if (age.get().compareTo(minAge) >= 0) {
            // Exactly at the threshold the component is old enough. Stated as an
            // explicit boundary because "younger than" and "not older than"
            // differ by one evaluation, and a component released at the second
            // it becomes eligible should not need another sweep.
            return FirewallRuleOutcome.notMatched();
        }
        return FirewallRuleOutcome.matched(violation(context, settings, componentFacts, age.get(), minAge));
    }

    private FirewallRuleViolation violation(
            FirewallRuleContext context,
            FirewallRuleSettings settings,
            ComponentFacts componentFacts,
            Duration age,
            Duration minAge) {

        Instant eligibleAt = componentFacts.publishedAt().plus(minAge);
        String reason = age.isNegative()
                ? ("declares a publication date in the future (%s), so it cannot be shown to meet "
                        + "the minimum age of %s required by the policy")
                        .formatted(instant(componentFacts.publishedAt()), humanize(minAge))
                : "published %s ago, less than the minimum age of %s required by the policy; acceptable from %s"
                        .formatted(humanize(age), humanize(minAge), instant(eligibleAt));
        return new FirewallRuleViolation(
                FirewallRuleType.MIN_AGE, settings.action(), reason, List.of());
    }

    /**
     * Asks for the missing date in the background.
     *
     * <p>Not a fetch: {@link ComponentFactsService#requestResolution} enqueues and
     * returns, which is what keeps the "no I/O on a request thread" promise while
     * still making the {@code INDETERMINATE} answer temporary. Idempotence is the
     * interface's promise, so calling it on every download of an unresolved
     * component is the expected usage rather than something to guard against
     * here.
     */
    private void requestResolution(FirewallRuleContext context) {
        if (facts == null) {
            return;
        }
        try {
            facts.requestResolution(context.identity());
        } catch (RuntimeException e) {
            // Asking for facts is best-effort. A store that is down leaves the
            // outcome INDETERMINATE, which it already is.
            log.debug("Could not queue a facts resolution for {}", context.componentKey(), e);
        }
    }

    /** {@code 2026-08-24T19:07:00Z} rather than a nanosecond-precision instant. */
    private static String instant(Instant value) {
        return value.truncatedTo(ChronoUnit.SECONDS).toString();
    }

    /**
     * A duration as a developer would say it out loud — "3 days 4 hours", not
     * "PT76H". Two components at most: the third never changes a decision and
     * makes the sentence unreadable.
     */
    static String humanize(Duration duration) {
        Duration value = duration.isNegative() ? duration.negated() : duration;
        StringBuilder text = new StringBuilder();
        long days = value.toDays();
        long hours = value.toHoursPart();
        long minutes = value.toMinutesPart();
        long seconds = value.toSecondsPart();

        append(text, days, "day");
        if (days > 0) {
            append(text, hours, "hour");
            return text.toString();
        }
        append(text, hours, "hour");
        if (hours > 0) {
            append(text, minutes, "minute");
            return text.toString();
        }
        append(text, minutes, "minute");
        if (minutes > 0) {
            return text.toString();
        }
        append(text, seconds, "second");
        return text.isEmpty() ? "0 seconds" : text.toString();
    }

    private static void append(StringBuilder text, long amount, String unit) {
        if (amount <= 0) {
            return;
        }
        if (!text.isEmpty()) {
            text.append(' ');
        }
        text.append(amount).append(' ').append(unit);
        if (amount != 1) {
            text.append('s');
        }
    }
}
