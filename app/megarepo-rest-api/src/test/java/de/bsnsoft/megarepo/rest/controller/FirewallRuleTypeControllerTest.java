package de.bsnsoft.megarepo.rest.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.bsnsoft.megarepo.core.firewall.FirewallQuarantineReason;
import de.bsnsoft.megarepo.core.firewall.FirewallRuleType;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRule;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRuleContext;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRuleOutcome;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRuleRegistry;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRuleSettings;
import de.bsnsoft.megarepo.rest.advice.GlobalExceptionHandler;
import de.bsnsoft.megarepo.rest.dto.firewall.FirewallRuleTypeXO;
import de.bsnsoft.megarepo.security.SecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What the policy editor is told about the rules it may offer.
 *
 * <p>The property that matters most is negative: a rule type this build has no
 * bean for must come back marked as such, not omitted and not marked
 * implemented. A policy row of an unimplemented type is skipped rather than
 * enforced, and an operator who set it to BLOCK and believes their repository is
 * protected is worse off than one who was told the rule is unavailable.
 */
class FirewallRuleTypeControllerTest {

    private static final String BASE = "/api/v1/firewall/rule-types";

    /** Rule types the editor may offer — everything except the audit observation. */
    private static final Set<FirewallRuleType> OFFERED =
            EnumSet.complementOf(EnumSet.of(FirewallRuleType.ADVISORY_MATCH));

    private MockMvc mockMvc(FirewallRule... rules) {
        return MockMvcBuilders.standaloneSetup(
                        new FirewallRuleTypeController(new FirewallRuleRegistry(List.of(rules))))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("the path is the one SecurityConfig restricts to nx-admin")
    void endpointIsCoveredByItsOwnRule() {
        String mapping = FirewallRuleTypeController.class.getAnnotation(RequestMapping.class).value()[0];

        assertThat(mapping)
                .as("this path sits outside the /api/v1/admin/ prefix, so it has a matcher of its "
                        + "own; letting the two drift apart drops it to plain authenticated()")
                .isEqualTo(SecurityConfig.FIREWALL_RULE_TYPES_PATH);
    }

    @Test
    @DisplayName("every rule type a policy may carry is listed, implemented or not")
    void listsEveryOfferedType() throws Exception {
        var result = mockMvc(new StubRule(FirewallRuleType.MIN_AGE, true))
                .perform(get(BASE))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        for (FirewallRuleType type : OFFERED) {
            assertThat(body).as("%s has to be offered even when this build cannot evaluate it", type)
                    .contains("\"" + type.name() + "\"");
        }
        assertThat(body)
                .as("ADVISORY_MATCH is the Phase 1 observation, not a rule anything evaluates")
                .doesNotContain(FirewallRuleType.ADVISORY_MATCH.name());
    }

    @Test
    @DisplayName("implemented comes from the registry, not from the catalogue")
    void implementedFollowsTheRegistry() throws Exception {
        Map<String, Map<String, Object>> served = serve(new StubRule(FirewallRuleType.LICENSE, false));

        assertThat(served.get("LICENSE"))
                .as("the registry has a LICENSE bean in this build")
                .containsEntry("implemented", true);
        assertThat(served.get("MIN_AGE"))
                .as("it has no MIN_AGE bean, and a policy row of that type is skipped, not enforced")
                .containsEntry("implemented", false);

        // The same catalogue, a different build: nothing implemented at all.
        assertThat(serve().get("LICENSE")).containsEntry("implemented", false);
    }

    @Test
    @DisplayName("whether a rule quarantines is read off the bean that decides it")
    void quarantinesFollowsTheBean() throws Exception {
        // The bean says it holds components, so the API says so too, even though
        // a licence verdict does not normally change by waiting.
        assertThat(serve(new StubRule(FirewallRuleType.LICENSE, true)).get("LICENSE"))
                .containsEntry("quarantines", true);
        assertThat(serve(new StubRule(FirewallRuleType.LICENSE, false)).get("LICENSE"))
                .containsEntry("quarantines", false);
    }

    @Test
    @DisplayName("the two heuristics are labelled as heuristics and recommended as WARN")
    void heuristicsAreLabelled() throws Exception {
        Map<String, Map<String, Object>> served = serve();

        for (String heuristic : List.of("TYPOSQUAT", "NAMESPACE_CONFUSION")) {
            assertThat(served.get(heuristic))
                    .as("%s reports a resemblance, not a fact, and the editor is where somebody "
                            + "decides to set it to BLOCK", heuristic)
                    .containsEntry("heuristic", true)
                    .containsEntry("recommendedAction", "WARN");
        }
        assertThat(served.get("CVSS_THRESHOLD")).containsEntry("heuristic", false);
        assertThat(served.get("UNKNOWN_COMPONENT"))
                .as("not a heuristic — it is exactly right about nearly every proxied component, "
                        + "which is why it carries a warning instead")
                .containsEntry("heuristic", false)
                .containsEntry("recommendedAction", "WARN");
        assertThat((String) served.get("UNKNOWN_COMPONENT").get("warning")).isNotBlank();
    }

    /** The served catalogue, keyed by rule type, as the editor receives it. */
    private Map<String, Map<String, Object>> serve(FirewallRule... rules) throws Exception {
        String body = mockMvc(rules)
                .perform(get(BASE))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        List<Map<String, Object>> types =
                new ObjectMapper().readValue(body, new TypeReference<>() {});
        Map<String, Map<String, Object>> byRuleType = new LinkedHashMap<>();
        for (Map<String, Object> type : types) {
            byRuleType.put(String.valueOf(type.get("ruleType")), type);
        }
        return byRuleType;
    }

    @Test
    @DisplayName("the rules that can quarantine an instance carry a warning; the plain ones do not")
    void warningsAreCarriedWhereTheyMatter() throws Exception {
        for (FirewallRuleType type : List.of(
                FirewallRuleType.UNKNOWN_COMPONENT,
                FirewallRuleType.TYPOSQUAT,
                FirewallRuleType.NAMESPACE_CONFUSION,
                FirewallRuleType.MIN_AGE,
                FirewallRuleType.LICENSE)) {

            assertThat(FirewallRuleCatalog.describe(type))
                    .extracting(FirewallRuleTypeXO::warning)
                    .as("%s needs a sentence at the point somebody sets it to BLOCK", type)
                    .isNotNull();
        }
        assertThat(FirewallRuleCatalog.describe(FirewallRuleType.CVSS_THRESHOLD).warning())
                .as("a warning on every rule is a warning on none")
                .isNull();
    }

    @Test
    @DisplayName("the config schema names the keys the rules actually read")
    void configSchemaMatchesTheImplementations() {
        assertThat(keys(FirewallRuleType.MIN_AGE)).containsExactly("minAge");
        assertThat(keys(FirewallRuleType.UNKNOWN_COMPONENT))
                .containsExactlyInAnyOrder(
                        "allowUnidentifiedFormats", "includeHostedComponents", "minConfidence");
        assertThat(keys(FirewallRuleType.LICENSE))
                .containsExactlyInAnyOrder("allowed", "denied", "allowUndeclared");
        assertThat(keys(FirewallRuleType.TYPOSQUAT))
                .containsExactlyInAnyOrder(
                        "maxDistance",
                        "minPopularity",
                        "minLength",
                        "minFamilyMembers",
                        "charactersPerEdit",
                        "checkNamespace",
                        "ignore");
        assertThat(keys(FirewallRuleType.NAMESPACE_CONFUSION))
                .containsExactlyInAnyOrder("internalNamespaces", "deriveFromHostedRepositories", "ignore");
        assertThat(keys(FirewallRuleType.CVSS_THRESHOLD))
                .containsExactlyInAnyOrder("minScore", "minConfidence");
        assertThat(keys(FirewallRuleType.KNOWN_MALICIOUS))
                .containsExactlyInAnyOrder("idPrefixes", "minConfidence");
    }

    @Test
    @DisplayName("every field declares a type the engine's accessors can read")
    void configFieldTypesAreOnesTheEngineUnderstands() {
        Set<String> known = Set.of("number", "boolean", "string", "stringList", "duration", "enum");
        for (FirewallRuleType type : OFFERED) {
            for (FirewallRuleTypeXO.ConfigField field :
                    FirewallRuleCatalog.describe(type).configSchema()) {
                assertThat(field.type())
                        .as("%s.%s declares a field type the editor cannot render", type, field.key())
                        .isIn(known);
                assertThat(field.label()).isNotBlank();
                assertThat(field.description()).isNotBlank();
                if (field.type().equals("enum")) {
                    assertThat(field.allowedValues())
                            .as("an enum field with no values is a dropdown with no options")
                            .isNotEmpty();
                }
            }
        }
    }

    private static List<String> keys(FirewallRuleType type) {
        return FirewallRuleCatalog.describe(type).configSchema().stream()
                .map(FirewallRuleTypeXO.ConfigField::key)
                .toList();
    }

    /** A rule bean of a given type, so the registry reports it as implemented. */
    private record StubRule(FirewallRuleType type, boolean holds) implements FirewallRule {

        @Override
        public FirewallRuleType ruleType() {
            return type;
        }

        @Override
        public boolean quarantineOnMatch() {
            return holds;
        }

        @Override
        public FirewallQuarantineReason quarantineReason() {
            return FirewallQuarantineReason.EVALUATION_INCOMPLETE;
        }

        @Override
        public FirewallRuleOutcome evaluate(FirewallRuleContext context, FirewallRuleSettings settings) {
            return FirewallRuleOutcome.notMatched();
        }
    }
}
