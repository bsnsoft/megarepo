package de.bsnsoft.megarepo.rest.controller;

import de.bsnsoft.megarepo.core.firewall.FirewallRuleType;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRule;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRuleRegistry;
import de.bsnsoft.megarepo.rest.dto.firewall.FirewallRuleTypeXO;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * What the policy editor may offer, and which of it this build actually
 * enforces.
 *
 * <h2>Why the editor cannot hardcode this</h2>
 *
 * Rule types are added in code and never in a migration — that is the whole
 * point of keeping rule parameters in {@code firewall_policy_rule.config} — so a
 * UI shipped against a list it compiled in would either hide a rule the server
 * enforces or, worse, offer one it does not. The second is the failure this
 * endpoint exists to prevent: a policy row of an unimplemented type is
 * <em>skipped</em>, never enforced, and an operator who set it to BLOCK and
 * believes their repository is protected is worse off than one who was told the
 * rule is not available.
 *
 * <p>{@code implemented} therefore comes from {@link FirewallRuleRegistry} — the
 * same object the engine dispatches through — and not from a list maintained
 * beside it. {@link FirewallRuleCatalog} supplies only the things a registry
 * cannot know: what the rule is called, what it reads out of {@code config}, and
 * what an operator should be told before setting it to BLOCK.
 *
 * <h2>Two rules are labelled as guesses</h2>
 *
 * {@code TYPOSQUAT} and {@code NAMESPACE_CONFUSION} report that a name resembles
 * another name. The design commits to labelling them as heuristics wherever they
 * are shown, and this is one of those places — it is where somebody decides to
 * set one of them to BLOCK. {@code UNKNOWN_COMPONENT} carries a warning for a
 * different reason: it is not a heuristic, it is exactly right about nearly
 * every component a proxy has ever served, which makes it the one rule that can
 * quarantine an entire instance from a single edit.
 *
 * <h2>Access</h2>
 *
 * {@code SecurityConfig} restricts this path to {@code nx-admin}
 * ({@link de.bsnsoft.megarepo.security.SecurityConfig#FIREWALL_RULE_TYPES_PATH}).
 * Authorization in this project lives in the filter chain, not in
 * {@code @PreAuthorize} — method security is not enabled, so the annotation
 * would be decoration. Nothing here is component data, but this is the policy
 * editor's supporting call and it belongs behind the same door as the editor.
 */
@RestController
@RequestMapping(FirewallRuleTypeController.BASE_PATH)
public class FirewallRuleTypeController {

    /**
     * Sits under {@code /api/v1/firewall} rather than {@code /api/v1/admin/firewall}
     * because that is the path the contract DTO documents and the Web UI is
     * written against; {@code SecurityConfig} carries a matcher of its own for it.
     */
    static final String BASE_PATH = "/api/v1/firewall/rule-types";

    private final FirewallRuleRegistry registry;

    public FirewallRuleTypeController(FirewallRuleRegistry registry) {
        this.registry = registry;
    }

    /**
     * Every rule type a policy may carry, each marked with whether this build
     * evaluates it.
     */
    @GetMapping
    public ResponseEntity<List<FirewallRuleTypeXO>> ruleTypes() {
        List<FirewallRuleTypeXO> types = new ArrayList<>();
        for (FirewallRuleType ruleType : FirewallRuleCatalog.offered()) {
            types.add(describe(ruleType));
        }
        return ResponseEntity.ok(types);
    }

    /**
     * Merges the static description with what the registry knows.
     *
     * <p>{@code quarantines} is read off the bean when there is one:
     * {@code quarantineOnMatch()} is the rule's own answer, and the queue an
     * operator ends up reading is filled by that method and not by this
     * catalogue. The catalogue's value is only used for a type this build does
     * not implement, where there is no bean to ask.
     */
    private FirewallRuleTypeXO describe(FirewallRuleType ruleType) {
        FirewallRuleTypeXO catalogued = FirewallRuleCatalog.describe(ruleType);
        boolean implemented = registry.isImplemented(ruleType);
        boolean quarantines = registry
                .find(ruleType)
                .map(FirewallRule::quarantineOnMatch)
                .orElse(catalogued.quarantines());

        return new FirewallRuleTypeXO(
                catalogued.ruleType(),
                catalogued.label(),
                catalogued.description(),
                implemented,
                catalogued.heuristic(),
                quarantines,
                catalogued.requiresComponentFacts(),
                catalogued.recommendedAction(),
                catalogued.warning(),
                catalogued.configSchema());
    }
}
