package de.bsnsoft.megarepo.repository.firewall.rule;

import de.bsnsoft.megarepo.core.firewall.FirewallQuarantineReason;
import de.bsnsoft.megarepo.core.firewall.FirewallRuleType;

/**
 * One kind of policy rule, as an independently implementable Spring bean.
 *
 * <p>Exactly one implementation per {@link FirewallRuleType}, collected by
 * {@link FirewallRuleRegistry} — the same SPI shape the format modules already
 * use for {@code PurlMapper}, and chosen for the same reason: a rule type should
 * be addable by writing one class and its test, without touching the engine,
 * the schema, or anybody else's file.
 *
 * <h2>What an implementation may do</h2>
 *
 * Read {@link FirewallRuleContext} and its own {@link FirewallRuleSettings}, and
 * return an answer. That is the whole contract.
 *
 * <ul>
 *   <li><b>No I/O on the request thread.</b> No network, and no query for data
 *       the context does not already carry — the engine read everything once for
 *       every rule, and the customer's budget is 20 ms for a cache hit.</li>
 *   <li><b>Never throw.</b> A rule that throws is a rule the engine has to guess
 *       about, and both guesses are wrong: treating it as matched denies
 *       downloads for a defect, treating it as clean hides one. Return
 *       {@link FirewallRuleOutcome#indeterminate} instead and let the fail mode
 *       decide. The registry catches anything that escapes anyway, but a rule
 *       relying on that is a rule with no opinion about its own failure.</li>
 *   <li><b>Be a pure function.</b> No state between calls, no caches keyed on
 *       anything but immutable deployment configuration. Several downloads are
 *       evaluated concurrently on the same bean.</li>
 *   <li><b>Decide, do not enforce.</b> A rule says what it found. Whether that
 *       denies a download depends on the action, the mode, the master switch,
 *       exemptions and grandfathering, all of which belong to the engine.</li>
 * </ul>
 *
 * <h2>Labelling heuristics honestly</h2>
 *
 * {@code TYPOSQUAT} and {@code NAMESPACE_CONFUSION} are guesses about intent,
 * not statements of fact, and the design commits to labelling them as such. An
 * implementation of either states its evidence in the violation's reason — the
 * package it resembles, the distance, the namespace it was expected under — so
 * that a developer reading a build log can tell "this is a known-malicious
 * package" from "this name looks a bit like another one".
 */
public interface FirewallRule {

    /**
     * The rule type this bean implements. The registry rejects a second bean for
     * the same type at startup rather than picking one at random.
     */
    FirewallRuleType ruleType();

    /**
     * Judges one component.
     *
     * @param context everything readable about the component, already fetched
     * @param settings this rule's row from the policy: its action and its
     *     {@code config} parameters
     * @return what the rule concluded; never null
     */
    FirewallRuleOutcome evaluate(FirewallRuleContext context, FirewallRuleSettings settings);

    /**
     * Whether a match by this rule should hold the component in quarantine
     * rather than refuse it outright.
     *
     * <p>Default false, which is the right answer for every rule that reports a
     * property of the component itself: a critical advisory or a malicious
     * package does not become acceptable by waiting, and a release queue for
     * those only invites somebody to approve one.
     *
     * <p>True for the rules whose verdict is expected to change on its own —
     * {@code MIN_AGE} (the component gets older) and {@code UNKNOWN_COMPONENT}
     * (the data arrives). Those are two of the three quarantine triggers the
     * customer named; the third is an evaluation that could not finish, which is
     * the engine's own {@link FirewallQuarantineReason#EVALUATION_INCOMPLETE} and
     * not any rule's doing.
     *
     * <p>Only consulted for a matched rule whose action is {@code BLOCK}. A
     * {@code WARN} rule records and serves, and there is nothing to hold.
     */
    default boolean quarantineOnMatch() {
        return false;
    }

    /**
     * The quarantine reason to record when this rule holds a component. Only
     * meaningful when {@link #quarantineOnMatch()} is true.
     */
    default FirewallQuarantineReason quarantineReason() {
        return FirewallQuarantineReason.EVALUATION_INCOMPLETE;
    }

    /**
     * Whether this rule can say anything at all about a component with no
     * package coordinates — a raw file, a Docker layer.
     *
     * <p>Default false: the advisory-driven rules, {@code MIN_AGE} and
     * {@code LICENSE} all key on a purl, and running them against a content
     * digest wastes work to reach a foregone conclusion. {@code UNKNOWN_COMPONENT}
     * overrides it, because an unidentifiable artifact is exactly its subject.
     */
    default boolean appliesToUnidentifiedComponents() {
        return false;
    }
}
