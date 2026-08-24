package de.bsnsoft.megarepo.rest.controller;

import de.bsnsoft.megarepo.core.firewall.FirewallAction;
import de.bsnsoft.megarepo.core.firewall.FirewallMode;
import de.bsnsoft.megarepo.core.firewall.FirewallQuarantineReason;
import de.bsnsoft.megarepo.core.firewall.FirewallRuleType;
import de.bsnsoft.megarepo.database.entity.FirewallPolicyEntity;
import de.bsnsoft.megarepo.database.entity.FirewallPolicyRuleEntity;
import de.bsnsoft.megarepo.database.entity.FirewallRepositoryConfigEntity;
import de.bsnsoft.megarepo.database.repository.FirewallPolicyJpaRepository;
import de.bsnsoft.megarepo.database.repository.FirewallPolicyRuleJpaRepository;
import de.bsnsoft.megarepo.database.repository.FirewallRepositoryConfigJpaRepository;
import de.bsnsoft.megarepo.repository.firewall.FirewallEnforcementSettingsService;
import de.bsnsoft.megarepo.repository.firewall.quarantine.QuarantineService;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRule;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRuleContext;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRuleOutcome;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRuleRegistry;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRuleSettings;
import de.bsnsoft.megarepo.rest.advice.GlobalExceptionHandler;
import de.bsnsoft.megarepo.security.SecurityConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestMapping;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What the policy endpoints do once a caller is past the filter chain.
 *
 * <p>Authorization is asserted separately, against the real {@code SecurityConfig},
 * in {@link FirewallPolicyApiAuthorizationTest}: a standalone MockMvc has no
 * filter chain, so nothing here could tell a protected endpoint from an open
 * one. What this class checks is the four properties an editor can break:
 * validation, the confirmation guard, the single-default invariant, and that
 * every write wakes the quarantine queue.
 */
class FirewallPolicyControllerTest {

    private static final String BASE = "/api/v1/admin/firewall/policies";

    private static final UUID DEFAULT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID STRICT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID REPOSITORY_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private FirewallPolicyJpaRepository policies;
    private FirewallPolicyRuleJpaRepository rules;
    private FirewallRepositoryConfigJpaRepository configs;
    private FirewallEnforcementSettingsService enforcementSettings;
    private QuarantineService quarantine;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        policies = mock(FirewallPolicyJpaRepository.class);
        rules = mock(FirewallPolicyRuleJpaRepository.class);
        configs = mock(FirewallRepositoryConfigJpaRepository.class);
        enforcementSettings = mock(FirewallEnforcementSettingsService.class);
        quarantine = mock(QuarantineService.class);

        // The registry is the real one with one stub rule: whether a rule type is
        // enforced has to come from the object the engine dispatches through, and
        // a mocked registry would let the controller claim anything.
        FirewallRuleRegistry registry = new FirewallRuleRegistry(List.of(new StubMinAgeRule()));

        when(policies.save(any())).thenAnswer(invocation -> {
            FirewallPolicyEntity entity = invocation.getArgument(0);
            if (entity.getId() == null) {
                entity.setId(UUID.randomUUID());
            }
            return entity;
        });
        when(policies.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(rules.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(rules.findByPolicyId(any())).thenReturn(List.of());
        when(policies.findAll()).thenReturn(List.of());
        when(configs.findAll()).thenReturn(List.of());
        when(configs.findByMode(any())).thenReturn(List.of());
        when(policies.findByName(any())).thenReturn(Optional.empty());
        when(policies.findByIsDefaultTrue()).thenReturn(Optional.empty());

        mockMvc = MockMvcBuilders.standaloneSetup(new FirewallPolicyController(
                        policies, rules, configs, enforcementSettings, registry, quarantine))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("the endpoint lives under the path SecurityConfig restricts to nx-admin")
    void endpointIsCoveredByTheAdminRule() {
        String mapping = FirewallPolicyController.class.getAnnotation(RequestMapping.class).value()[0];
        String guardedPrefix = SecurityConfig.FIREWALL_ADMIN_PATH_PATTERN.replace("**", "");

        assertThat(mapping + "/")
                .as("moving the policy editor out of %s would downgrade it to plain authenticated()",
                        SecurityConfig.FIREWALL_ADMIN_PATH_PATTERN)
                .startsWith(guardedPrefix);
    }

    // ── Reading ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("a rule of a type this build has no bean for is returned as not implemented")
    void unimplementedRuleIsMarked() throws Exception {
        FirewallPolicyEntity policy = policy(DEFAULT_ID, "Default", true);
        when(policies.findById(DEFAULT_ID)).thenReturn(Optional.of(policy));
        when(rules.findByPolicyId(DEFAULT_ID)).thenReturn(List.of(
                rule(FirewallRuleType.MIN_AGE, FirewallAction.BLOCK),
                rule(FirewallRuleType.LICENSE, FirewallAction.WARN)));

        mockMvc.perform(get(BASE + "/" + DEFAULT_ID))
                .andExpect(status().isOk())
                // Sorted by rule type name: LICENSE before MIN_AGE.
                .andExpect(jsonPath("$.rules[0].ruleType").value("LICENSE"))
                .andExpect(jsonPath("$.rules[0].implemented").value(false))
                .andExpect(jsonPath("$.rules[1].ruleType").value("MIN_AGE"))
                .andExpect(jsonPath("$.rules[1].implemented").value(true));
    }

    @Test
    @DisplayName("the default policy counts the repositories that never chose one")
    void defaultPolicyCountsUnassignedRepositories() throws Exception {
        FirewallPolicyEntity policy = policy(DEFAULT_ID, "Default", true);
        when(policies.findById(DEFAULT_ID)).thenReturn(Optional.of(policy));
        when(configs.findAll()).thenReturn(List.of(
                config(UUID.randomUUID(), null, FirewallMode.QUARANTINE),
                config(UUID.randomUUID(), null, FirewallMode.AUDIT),
                config(UUID.randomUUID(), STRICT_ID, FirewallMode.QUARANTINE)));

        mockMvc.perform(get(BASE + "/" + DEFAULT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignedRepositories").value(2))
                .andExpect(jsonPath("$.enforcingRepositories").value(1));
    }

    // ── Creating ────────────────────────────────────────────────────────

    @Test
    @DisplayName("a policy is created 201 with its rules and attributed to the caller")
    void createsPolicy() throws Exception {
        mockMvc.perform(post(BASE)
                        .contentType("application/json")
                        .content("""
                                {"name":"Strict","description":"blocks a lot","makeDefault":false,
                                 "rules":[{"ruleType":"MIN_AGE","action":"BLOCK",
                                           "config":{"minAge":"P14D"},"enabled":true}]}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Strict"));

        ArgumentCaptor<FirewallPolicyRuleEntity> stored =
                ArgumentCaptor.forClass(FirewallPolicyRuleEntity.class);
        verify(rules).save(stored.capture());
        assertThat(stored.getValue().getRuleType()).isEqualTo(FirewallRuleType.MIN_AGE);
        assertThat(stored.getValue().getConfig()).containsEntry("minAge", "P14D");
    }

    @Test
    @DisplayName("a duplicate name is a 409, not a database error")
    void duplicateNameIsRejected() throws Exception {
        when(policies.findByName("Strict")).thenReturn(Optional.of(policy(STRICT_ID, "Strict", false)));

        mockMvc.perform(post(BASE)
                        .contentType("application/json")
                        .content("{\"name\":\"Strict\",\"makeDefault\":false,\"rules\":[]}"))
                .andExpect(status().isConflict());

        verify(policies, never()).save(any());
    }

    @Test
    @DisplayName("ADVISORY_MATCH is refused: it is an observation, not a rule anything evaluates")
    void advisoryMatchIsNotAPolicyRule() throws Exception {
        mockMvc.perform(post(BASE)
                        .contentType("application/json")
                        .content("""
                                {"name":"Odd","makeDefault":false,
                                 "rules":[{"ruleType":"ADVISORY_MATCH","action":"BLOCK","enabled":true}]}"""))
                .andExpect(status().isBadRequest());

        verify(policies, never()).save(any());
        verify(rules, never()).save(any());
    }

    @Test
    @DisplayName("a rule with no type is a 400 and never reaches the database")
    void ruleTypeIsRequired() throws Exception {
        mockMvc.perform(post(BASE)
                        .contentType("application/json")
                        .content("{\"name\":\"Odd\",\"makeDefault\":false,"
                                + "\"rules\":[{\"action\":\"BLOCK\",\"enabled\":true}]}"))
                .andExpect(status().isBadRequest());

        verify(rules, never()).save(any());
    }

    // ── Replacing ───────────────────────────────────────────────────────

    @Test
    @DisplayName("a replace deletes the previous rule set rather than merging into it")
    void replaceIsAReplace() throws Exception {
        when(policies.findById(STRICT_ID)).thenReturn(Optional.of(policy(STRICT_ID, "Strict", false)));

        mockMvc.perform(put(BASE + "/" + STRICT_ID)
                        .contentType("application/json")
                        .content("""
                                {"name":"Strict","makeDefault":false,
                                 "rules":[{"ruleType":"LICENSE","action":"WARN","enabled":true}]}"""))
                .andExpect(status().isOk());

        verify(rules).deleteByPolicyId(STRICT_ID);
        verify(rules).save(any());
    }

    @Test
    @DisplayName("a policy edit schedules the components it is holding for re-evaluation")
    void editInvalidatesTheQuarantineQueue() throws Exception {
        when(policies.findById(STRICT_ID)).thenReturn(Optional.of(policy(STRICT_ID, "Strict", false)));

        mockMvc.perform(put(BASE + "/" + STRICT_ID)
                        .contentType("application/json")
                        .content("{\"name\":\"Strict\",\"makeDefault\":false,\"rules\":[]}"))
                .andExpect(status().isOk());

        // Without this call a loosened policy keeps its components held until the
        // next sweep, and the operator who just fixed the policy watches the
        // build keep failing. Deleting the call from the controller fails here.
        verify(quarantine).invalidatePolicy(STRICT_ID);
    }

    // ── The confirmation guard ──────────────────────────────────────────

    @Test
    @DisplayName("editing a policy an armed repository is using needs the typed phrase")
    void enforcedEditNeedsConfirmation() throws Exception {
        armedRepositoryUsing(STRICT_ID);
        when(policies.findById(STRICT_ID)).thenReturn(Optional.of(policy(STRICT_ID, "Strict", false)));

        mockMvc.perform(put(BASE + "/" + STRICT_ID)
                        .contentType("application/json")
                        .content("{\"name\":\"Strict\",\"makeDefault\":false,\"rules\":[]}"))
                .andExpect(status().isBadRequest());

        verify(rules, never()).deleteByPolicyId(any());
        verify(quarantine, never()).invalidatePolicy(any());

        mockMvc.perform(put(BASE + "/" + STRICT_ID)
                        .contentType("application/json")
                        .content("{\"name\":\"Strict\",\"makeDefault\":false,\"rules\":[],"
                                + "\"confirmation\":\"CHANGE POLICY Strict\"}"))
                .andExpect(status().isOk());

        verify(rules).deleteByPolicyId(STRICT_ID);
    }

    @Test
    @DisplayName("with the master switch off nothing is blocking, so nothing is confirmed")
    void guardIsNotRaisedWhileDisarmed() throws Exception {
        when(enforcementSettings.enforcementEnabled()).thenReturn(false);
        when(configs.findByMode(FirewallMode.QUARANTINE))
                .thenReturn(List.of(config(REPOSITORY_ID, STRICT_ID, FirewallMode.QUARANTINE)));
        when(policies.findById(STRICT_ID)).thenReturn(Optional.of(policy(STRICT_ID, "Strict", false)));

        mockMvc.perform(put(BASE + "/" + STRICT_ID)
                        .contentType("application/json")
                        .content("{\"name\":\"Strict\",\"makeDefault\":false,\"rules\":[]}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("editing a policy nobody has armed needs nothing")
    void guardIsNotRaisedForAnUnusedPolicy() throws Exception {
        when(enforcementSettings.enforcementEnabled()).thenReturn(true);
        when(configs.findByMode(FirewallMode.QUARANTINE))
                .thenReturn(List.of(config(REPOSITORY_ID, DEFAULT_ID, FirewallMode.QUARANTINE)));
        when(policies.findById(STRICT_ID)).thenReturn(Optional.of(policy(STRICT_ID, "Strict", false)));

        mockMvc.perform(put(BASE + "/" + STRICT_ID)
                        .contentType("application/json")
                        .content("{\"name\":\"Strict\",\"makeDefault\":false,\"rules\":[]}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("an armed repository that chose no policy is governed by the default, and confirms it")
    void guardCoversTheDefaultPolicy() throws Exception {
        when(enforcementSettings.enforcementEnabled()).thenReturn(true);
        when(configs.findByMode(FirewallMode.QUARANTINE))
                .thenReturn(List.of(config(REPOSITORY_ID, null, FirewallMode.QUARANTINE)));
        FirewallPolicyEntity defaultPolicy = policy(DEFAULT_ID, "Default", true);
        when(policies.findById(DEFAULT_ID)).thenReturn(Optional.of(defaultPolicy));
        when(policies.findByIsDefaultTrue()).thenReturn(Optional.of(defaultPolicy));

        mockMvc.perform(put(BASE + "/" + DEFAULT_ID)
                        .contentType("application/json")
                        .content("{\"name\":\"Default\",\"makeDefault\":true,\"rules\":[]}"))
                .andExpect(status().isBadRequest());

        verify(rules, never()).deleteByPolicyId(any());
    }

    // ── The single-default invariant ────────────────────────────────────

    @Test
    @DisplayName("moving the default flag clears it from the policy that held it")
    void defaultFlagMoves() throws Exception {
        FirewallPolicyEntity previous = policy(DEFAULT_ID, "Default", true);
        FirewallPolicyEntity strict = policy(STRICT_ID, "Strict", false);
        when(policies.findById(STRICT_ID)).thenReturn(Optional.of(strict));
        when(policies.findByIsDefaultTrue()).thenReturn(Optional.of(previous));

        mockMvc.perform(post(BASE + "/" + STRICT_ID + "/default"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isDefault").value(true));

        assertThat(previous.isDefault())
                .as("the schema permits exactly one default; the previous holder has to give it up")
                .isFalse();
        assertThat(strict.isDefault()).isTrue();
        verify(policies).saveAndFlush(previous);
        verify(quarantine).invalidatePolicy(DEFAULT_ID);
        verify(quarantine).invalidatePolicy(STRICT_ID);
    }

    @Test
    @DisplayName("making the current default the default again is a no-op, not a conflict")
    void makingTheDefaultDefaultIsIdempotent() throws Exception {
        FirewallPolicyEntity policy = policy(DEFAULT_ID, "Default", true);
        when(policies.findById(DEFAULT_ID)).thenReturn(Optional.of(policy));

        mockMvc.perform(post(BASE + "/" + DEFAULT_ID + "/default"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isDefault").value(true));

        verify(policies, never()).saveAndFlush(any());
    }

    // ── Deleting ────────────────────────────────────────────────────────

    @Test
    @DisplayName("the default policy cannot be deleted — an armed repository has to resolve to something")
    void defaultPolicyCannotBeDeleted() throws Exception {
        when(policies.findById(DEFAULT_ID)).thenReturn(Optional.of(policy(DEFAULT_ID, "Default", true)));

        mockMvc.perform(delete(BASE + "/" + DEFAULT_ID)).andExpect(status().isConflict());

        verify(policies, never()).delete(any());
    }

    @Test
    @DisplayName("a deleted policy releases its hold on the queue before the row disappears")
    void deleteInvalidatesBeforeDeleting() throws Exception {
        FirewallPolicyEntity strict = policy(STRICT_ID, "Strict", false);
        when(policies.findById(STRICT_ID)).thenReturn(Optional.of(strict));

        mockMvc.perform(delete(BASE + "/" + STRICT_ID)).andExpect(status().isNoContent());

        // firewall_quarantine.policy_id is ON DELETE SET NULL: after the delete
        // the entries this policy decided can no longer be found by it, so the
        // order is the whole point of the assertion.
        InOrder order = inOrder(quarantine, policies);
        order.verify(quarantine).invalidatePolicy(STRICT_ID);
        order.verify(policies).delete(strict);
        verify(rules).deleteByPolicyId(STRICT_ID);
    }

    @Test
    @DisplayName("a policy that does not exist is a 404")
    void unknownPolicyIsNotFound() throws Exception {
        when(policies.findById(STRICT_ID)).thenReturn(Optional.empty());

        mockMvc.perform(get(BASE + "/" + STRICT_ID)).andExpect(status().isNotFound());
        mockMvc.perform(delete(BASE + "/" + STRICT_ID)).andExpect(status().isNotFound());
    }

    // ── Fixtures ────────────────────────────────────────────────────────

    private void armedRepositoryUsing(UUID policyId) {
        when(enforcementSettings.enforcementEnabled()).thenReturn(true);
        when(configs.findByMode(FirewallMode.QUARANTINE))
                .thenReturn(List.of(config(REPOSITORY_ID, policyId, FirewallMode.QUARANTINE)));
    }

    private static FirewallPolicyEntity policy(UUID id, String name, boolean isDefault) {
        FirewallPolicyEntity entity = new FirewallPolicyEntity();
        entity.setId(id);
        entity.setName(name);
        entity.setDefault(isDefault);
        entity.setCreatedAt(Instant.parse("2026-08-01T09:00:00Z"));
        entity.setCreatedBy("ops");
        return entity;
    }

    private static FirewallPolicyRuleEntity rule(FirewallRuleType type, FirewallAction action) {
        FirewallPolicyRuleEntity entity = new FirewallPolicyRuleEntity();
        entity.setId(UUID.randomUUID());
        entity.setRuleType(type);
        entity.setAction(action);
        entity.setConfig(new java.util.HashMap<>(Map.of()));
        entity.setEnabled(true);
        return entity;
    }

    private static FirewallRepositoryConfigEntity config(UUID repositoryId, UUID policyId, FirewallMode mode) {
        FirewallRepositoryConfigEntity entity = new FirewallRepositoryConfigEntity();
        entity.setRepositoryId(repositoryId);
        entity.setPolicyId(policyId);
        entity.setMode(mode);
        return entity;
    }

    /** Stands in for the MIN_AGE bean so the registry reports one implemented type. */
    private static final class StubMinAgeRule implements FirewallRule {

        @Override
        public FirewallRuleType ruleType() {
            return FirewallRuleType.MIN_AGE;
        }

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
            return FirewallRuleOutcome.notMatched();
        }
    }
}
