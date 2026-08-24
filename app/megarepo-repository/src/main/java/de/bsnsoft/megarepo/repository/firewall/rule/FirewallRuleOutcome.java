package de.bsnsoft.megarepo.repository.firewall.rule;

import de.bsnsoft.megarepo.repository.firewall.FirewallRuleViolation;

import java.util.Objects;
import java.util.Optional;

/**
 * What one rule concluded about one component.
 *
 * <h2>Why three answers and not two</h2>
 *
 * Phase 1's evaluator returned {@code Optional<FirewallRuleViolation>}: matched,
 * or not. That works while every rule reads advisory data that is either present
 * or absent, and stops working the moment a rule needs a fact the firewall has
 * not learned yet.
 *
 * <p>{@code MIN_AGE} is the case that breaks it. Asked about a component whose
 * publication date has not been resolved, the rule has exactly one honest
 * answer, and it is neither "old enough" nor "too new" — both are guesses, and
 * they fail in opposite directions. Reporting "not matched" serves a package
 * that might have been uploaded four minutes ago, which is the attack the rule
 * exists to stop; reporting "matched" quarantines every component in the
 * repository until the resolver has caught up.
 *
 * <p>So a rule may say {@link Kind#INDETERMINATE}, and the engine — which knows
 * the repository's fail mode and the customer's grandfathering rule, and the rule
 * does not — decides what that costs. Fail-open serves; fail-closed quarantines
 * under {@code EVALUATION_INCOMPLETE}, which is one of the three quarantine
 * triggers the customer named.
 *
 * @param kind what the rule concluded
 * @param violation the matched violation; non-null exactly when kind is
 *     {@link Kind#MATCHED}
 * @param reason why the rule could not decide; non-null exactly when kind is
 *     {@link Kind#INDETERMINATE}. One sentence, and it reaches an operator in the
 *     quarantine queue, so it names the missing fact rather than the code path
 */
public record FirewallRuleOutcome(Kind kind, FirewallRuleViolation violation, String reason) {

    private static final FirewallRuleOutcome NOT_MATCHED =
            new FirewallRuleOutcome(Kind.NOT_MATCHED, null, null);

    public FirewallRuleOutcome {
        Objects.requireNonNull(kind, "kind must not be null");
        if (kind == Kind.MATCHED && violation == null) {
            throw new IllegalArgumentException("a MATCHED outcome must carry a violation");
        }
        if (kind != Kind.MATCHED && violation != null) {
            throw new IllegalArgumentException("only a MATCHED outcome may carry a violation");
        }
        if (kind == Kind.INDETERMINATE && (reason == null || reason.isBlank())) {
            throw new IllegalArgumentException("an INDETERMINATE outcome must say why");
        }
    }

    /** What a rule concluded. */
    public enum Kind {

        /** The rule looked and found nothing to object to. */
        NOT_MATCHED,

        /** The rule matched; {@link #violation()} says what it asks for. */
        MATCHED,

        /**
         * The rule could not reach a verdict because a fact it needs is not
         * available yet. Not an error: the data is expected to arrive.
         */
        INDETERMINATE
    }

    /** The rule looked and found nothing. */
    public static FirewallRuleOutcome notMatched() {
        return NOT_MATCHED;
    }

    /** The rule matched. */
    public static FirewallRuleOutcome matched(FirewallRuleViolation violation) {
        return new FirewallRuleOutcome(Kind.MATCHED, violation, null);
    }

    /**
     * The rule needs a fact that is not available yet.
     *
     * @param reason one sentence naming the missing fact, for the quarantine
     *     queue and the block response
     */
    public static FirewallRuleOutcome indeterminate(String reason) {
        return new FirewallRuleOutcome(Kind.INDETERMINATE, null, reason);
    }

    public boolean matched() {
        return kind == Kind.MATCHED;
    }

    public boolean indeterminate() {
        return kind == Kind.INDETERMINATE;
    }

    /** The violation, if this outcome is a match. */
    public Optional<FirewallRuleViolation> violationIfMatched() {
        return Optional.ofNullable(violation);
    }
}
