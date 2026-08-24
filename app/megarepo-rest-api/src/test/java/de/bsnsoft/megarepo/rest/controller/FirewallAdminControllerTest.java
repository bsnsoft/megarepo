package de.bsnsoft.megarepo.rest.controller;

import de.bsnsoft.megarepo.core.firewall.FirewallAction;
import de.bsnsoft.megarepo.core.firewall.FirewallFailMode;
import de.bsnsoft.megarepo.core.firewall.FirewallMode;
import de.bsnsoft.megarepo.core.firewall.FirewallRuleType;
import de.bsnsoft.megarepo.database.entity.FirewallEnforcementSettingsEntity;
import de.bsnsoft.megarepo.database.entity.FirewallPolicyEntity;
import de.bsnsoft.megarepo.database.entity.FirewallRepositoryConfigEntity;
import de.bsnsoft.megarepo.database.entity.FirewallViolationEntity;
import de.bsnsoft.megarepo.database.entity.RepositoryEntity;
import de.bsnsoft.megarepo.database.repository.FirewallEnforcementSettingsJpaRepository;
import de.bsnsoft.megarepo.database.repository.FirewallPolicyJpaRepository;
import de.bsnsoft.megarepo.database.repository.FirewallRepositoryConfigJpaRepository;
import de.bsnsoft.megarepo.database.repository.FirewallViolationJpaRepository;
import de.bsnsoft.megarepo.database.repository.RepositoryJpaRepository;
import de.bsnsoft.megarepo.repository.firewall.FirewallAuditProperties;
import de.bsnsoft.megarepo.repository.firewall.FirewallEnforcementSettingsService;
import de.bsnsoft.megarepo.repository.firewall.quarantine.QuarantineService;
import de.bsnsoft.megarepo.rest.advice.GlobalExceptionHandler;
import de.bsnsoft.megarepo.security.SecurityConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Behaviour of the firewall administration surface.
 *
 * <p>Two properties carry most of the weight here and are worth naming, because
 * both are safety properties rather than features:
 *
 * <ul>
 *   <li>the API never reports a repository as protected when it is not — a mode
 *       of QUARANTINE on an instance whose enforcement switch is off comes back
 *       as {@code QUARANTINE_NOT_ENFORCED}, not as "quarantine";</li>
 *   <li>every transition that can start refusing downloads needs an exact
 *       confirmation phrase, and every transition that stops refusing them needs
 *       nothing.</li>
 * </ul>
 *
 * <p>Authorization is not tested here — a standalone MockMvc has no filter
 * chain, and asserting 401/403 against one would prove nothing. It is proven
 * against the real {@code SecurityConfig} in
 * {@link FirewallAdminAuthorizationTest}; what this class checks is that the
 * mapping stays inside the prefix that config guards.
 */
@ExtendWith(MockitoExtension.class)
class FirewallAdminControllerTest {

    private static final UUID MAVEN_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID NPM_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID DEFAULT_POLICY_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID STRICT_POLICY_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

    private MockMvc mockMvc;

    @Mock private FirewallEnforcementSettingsService enforcementSettings;
    @Mock private FirewallEnforcementSettingsJpaRepository enforcementRepo;
    @Mock private FirewallRepositoryConfigJpaRepository configRepo;
    @Mock private FirewallViolationJpaRepository violationRepo;
    @Mock private RepositoryJpaRepository repositoryRepo;
    @Mock private FirewallPolicyJpaRepository policyRepo;
    @Mock private QuarantineService quarantine;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new FirewallAdminController(
                        enforcementSettings,
                        enforcementRepo,
                        configRepo,
                        violationRepo,
                        repositoryRepo,
                        policyRepo,
                        quarantine,
                        FirewallAuditProperties.defaults()))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("the endpoint lives under the path SecurityConfig restricts to nx-admin")
    void endpointIsCoveredByTheAdminRule() {
        String mapping = FirewallAdminController.class
                .getAnnotation(RequestMapping.class)
                .value()[0];
        String guardedPrefix = SecurityConfig.FIREWALL_ADMIN_PATH_PATTERN.replace("**", "");

        assertThat(mapping + "/")
                .as("moving this controller out of %s would silently downgrade the "
                        + "enforcement switch to plain authenticated()",
                        SecurityConfig.FIREWALL_ADMIN_PATH_PATTERN)
                .startsWith(guardedPrefix);
    }

    // ── Status ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("QUARANTINE while the switch is off reports as not enforced, never as protected")
    void quarantineWithoutEnforcementIsNotProtection() throws Exception {
        givenEnforcement(false);
        givenRepositories(repository(MAVEN_ID, "maven-central", "maven2", "proxy"));
        givenConfigs(config(MAVEN_ID, FirewallMode.QUARANTINE));
        givenViolationCounts();

        mockMvc.perform(get("/api/v1/admin/firewall/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enforcement.enabled").value(false))
                .andExpect(jsonPath("$.repositories[0].mode").value("QUARANTINE"))
                .andExpect(jsonPath("$.repositories[0].effectiveState").value("QUARANTINE_NOT_ENFORCED"))
                .andExpect(jsonPath("$.summary.quarantineNotEnforced").value(1))
                .andExpect(jsonPath("$.summary.blocking").value(0));
    }

    @Test
    @DisplayName("the same configuration reports as blocking once the switch is on")
    void quarantineWithEnforcementBlocks() throws Exception {
        givenEnforcement(true);
        givenRepositories(repository(MAVEN_ID, "maven-central", "maven2", "proxy"));
        givenConfigs(config(MAVEN_ID, FirewallMode.QUARANTINE));
        givenViolationCounts();

        mockMvc.perform(get("/api/v1/admin/firewall/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repositories[0].effectiveState").value("BLOCKING"))
                .andExpect(jsonPath("$.summary.blocking").value(1))
                .andExpect(jsonPath("$.summary.quarantineNotEnforced").value(0));
    }

    @Test
    @DisplayName("AUDIT observes whether the instance is armed or not")
    void auditNeverBlocks() throws Exception {
        givenEnforcement(true);
        givenRepositories(repository(NPM_ID, "npm-proxy", "npm", "proxy"));
        givenConfigs(config(NPM_ID, FirewallMode.AUDIT));
        givenViolationCounts();

        mockMvc.perform(get("/api/v1/admin/firewall/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repositories[0].effectiveState").value("OBSERVING"))
                .andExpect(jsonPath("$.summary.blocking").value(0));
    }

    @Test
    @DisplayName("a repository with no config row falls back to the instance default and says so")
    void repositoryWithoutConfigUsesTheDefault() throws Exception {
        givenEnforcement(false);
        givenRepositories(repository(NPM_ID, "npm-proxy", "npm", "proxy"));
        givenConfigs();
        givenViolationCounts();

        mockMvc.perform(get("/api/v1/admin/firewall/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repositories[0].configured").value(false))
                .andExpect(jsonPath("$.repositories[0].mode").value("OFF"))
                .andExpect(jsonPath("$.repositories[0].effectiveState").value("NOT_EVALUATED"));
    }

    @Test
    @DisplayName("recorded violations are counted per repository over the reported window")
    void violationCountsAreReported() throws Exception {
        givenEnforcement(false);
        givenRepositories(
                repository(MAVEN_ID, "maven-central", "maven2", "proxy"),
                repository(NPM_ID, "npm-proxy", "npm", "proxy"));
        givenConfigs(config(MAVEN_ID, FirewallMode.AUDIT));
        givenViolationCounts(Map.of(MAVEN_ID, 42L));

        mockMvc.perform(get("/api/v1/admin/firewall/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.violationWindowDays").value(30))
                // sorted by name: maven-central before npm-proxy
                .andExpect(jsonPath("$.repositories[0].violations").value(42))
                .andExpect(jsonPath("$.repositories[1].violations").value(0));
    }

    // ── Global switch ───────────────────────────────────────────────────

    @Test
    @DisplayName("arming without the confirmation phrase is refused and writes nothing")
    void armingNeedsConfirmation() throws Exception {
        givenEnforcement(false);

        mockMvc.perform(put("/api/v1/admin/firewall/enforcement")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString(
                                FirewallAdminController.ENFORCEMENT_CONFIRMATION)));

        verify(enforcementSettings, never()).save(anyBoolean(), any());
    }

    @Test
    @DisplayName("a wrong confirmation phrase is refused too")
    void armingRejectsAWrongPhrase() throws Exception {
        givenEnforcement(false);

        mockMvc.perform(put("/api/v1/admin/firewall/enforcement")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true,\"confirmation\":\"yes\"}"))
                .andExpect(status().isBadRequest());

        verify(enforcementSettings, never()).save(anyBoolean(), any());
    }

    @Test
    @DisplayName("arming with the exact phrase persists the switch and records who did it")
    void armingWithConfirmationPersists() throws Exception {
        givenEnforcement(false);
        givenTheSwitchCanBeWritten();

        mockMvc.perform(put("/api/v1/admin/firewall/enforcement")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true,\"confirmation\":\""
                                + FirewallAdminController.ENFORCEMENT_CONFIRMATION + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));

        // Through the service, never straight to the row: that is what claims the
        // switch, stamps the watermark and makes the change effective at once.
        // FirewallSwitchEndToEndTest proves the consequence against a live
        // download; this asserts the call that produces it.
        ArgumentCaptor<Boolean> enabled = ArgumentCaptor.forClass(Boolean.class);
        ArgumentCaptor<String> updatedBy = ArgumentCaptor.forClass(String.class);
        verify(enforcementSettings).save(enabled.capture(), updatedBy.capture());
        verify(enforcementRepo, never()).save(any());
        assertThat(enabled.getValue()).isTrue();
        assertThat(updatedBy.getValue()).isNotNull();
    }

    @Test
    @DisplayName("disarming needs no confirmation — the safe direction is never gated")
    void disarmingNeedsNoConfirmation() throws Exception {
        givenEnforcement(true);
        givenTheSwitchCanBeWritten();

        mockMvc.perform(put("/api/v1/admin/firewall/enforcement")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));

        verify(enforcementSettings).save(eq(false), any());
    }

    @Test
    @DisplayName("re-sending the state it already has is idempotent and needs no phrase")
    void armingAnAlreadyArmedInstanceIsANoOp() throws Exception {
        givenEnforcement(true);

        mockMvc.perform(put("/api/v1/admin/firewall/enforcement")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));

        verify(enforcementSettings, never()).save(anyBoolean(), any());
    }

    @Test
    @DisplayName("a body without 'enabled' is rejected rather than read as off")
    void missingEnabledIsRejected() throws Exception {
        mockMvc.perform(put("/api/v1/admin/firewall/enforcement")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmation\":\"ENABLE ENFORCEMENT\"}"))
                .andExpect(status().isBadRequest());

        verify(enforcementSettings, never()).save(anyBoolean(), any());
    }

    @Test
    @DisplayName("the switch advertises the phrase needed to arm it")
    void enforcementResourceAdvertisesItsPhrase() throws Exception {
        givenEnforcement(false);

        mockMvc.perform(get("/api/v1/admin/firewall/enforcement"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requiredConfirmation")
                        .value(FirewallAdminController.ENFORCEMENT_CONFIRMATION));
    }

    // ── Per-repository mode ─────────────────────────────────────────────

    @Test
    @DisplayName("moving a repository into QUARANTINE requires its name in the phrase")
    void quarantineNeedsTheRepositoryName() throws Exception {
        when(repositoryRepo.findById(MAVEN_ID))
                .thenReturn(Optional.of(repository(MAVEN_ID, "maven-central", "maven2", "proxy")));
        when(configRepo.findById(MAVEN_ID)).thenReturn(Optional.empty());

        mockMvc.perform(put("/api/v1/admin/firewall/repositories/" + MAVEN_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"QUARANTINE\",\"confirmation\":\"QUARANTINE npm-proxy\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("QUARANTINE maven-central")));

        verify(configRepo, never()).save(any());
    }

    @Test
    @DisplayName("with the right phrase the mode is written and the effective state comes back")
    void quarantineWithConfirmationIsWritten() throws Exception {
        when(repositoryRepo.findById(MAVEN_ID))
                .thenReturn(Optional.of(repository(MAVEN_ID, "maven-central", "maven2", "proxy")));
        when(configRepo.findById(MAVEN_ID)).thenReturn(Optional.empty());
        when(configRepo.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        givenEnforcement(false);
        givenViolationCounts();

        mockMvc.perform(put("/api/v1/admin/firewall/repositories/" + MAVEN_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"QUARANTINE\",\"confirmation\":\"QUARANTINE maven-central\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("QUARANTINE"))
                .andExpect(jsonPath("$.effectiveState").value("QUARANTINE_NOT_ENFORCED"));

        ArgumentCaptor<FirewallRepositoryConfigEntity> saved =
                ArgumentCaptor.forClass(FirewallRepositoryConfigEntity.class);
        verify(configRepo).save(saved.capture());
        assertThat(saved.getValue().getRepositoryId()).isEqualTo(MAVEN_ID);
        assertThat(saved.getValue().getMode()).isEqualTo(FirewallMode.QUARANTINE);
    }

    @Test
    @DisplayName("switching back to AUDIT needs no phrase, and leaves failMode untouched")
    void leavingQuarantineNeedsNoConfirmation() throws Exception {
        FirewallRepositoryConfigEntity existing = config(MAVEN_ID, FirewallMode.QUARANTINE);
        existing.setFailMode(FirewallFailMode.FAIL_CLOSED);

        when(repositoryRepo.findById(MAVEN_ID))
                .thenReturn(Optional.of(repository(MAVEN_ID, "maven-central", "maven2", "proxy")));
        when(configRepo.findById(MAVEN_ID)).thenReturn(Optional.of(existing));
        when(configRepo.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        givenEnforcement(true);
        givenViolationCounts();

        mockMvc.perform(put("/api/v1/admin/firewall/repositories/" + MAVEN_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"AUDIT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effectiveState").value("OBSERVING"))
                .andExpect(jsonPath("$.failMode").value("FAIL_CLOSED"));
    }

    @Test
    @DisplayName("an unknown repository is a 404, not a config row for something that does not exist")
    void unknownRepositoryIsNotFound() throws Exception {
        UUID unknown = UUID.randomUUID();
        when(repositoryRepo.findById(unknown)).thenReturn(Optional.empty());

        mockMvc.perform(put("/api/v1/admin/firewall/repositories/" + unknown)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"AUDIT\"}"))
                .andExpect(status().isNotFound());

        verify(configRepo, never()).save(any());
    }

    @Test
    @DisplayName("an unknown mode is rejected instead of being coerced")
    void unknownModeIsRejected() throws Exception {
        mockMvc.perform(put("/api/v1/admin/firewall/repositories/" + MAVEN_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"BLOCK_EVERYTHING\"}"))
                .andExpect(status().isBadRequest());

        verify(configRepo, never()).save(any());
    }

    // ── Per-repository policy ───────────────────────────────────────────

    @Test
    @DisplayName("assigning a policy to a repository that is only observing needs no phrase")
    void assigningAPolicyToAnObservingRepositoryIsUngated() throws Exception {
        FirewallRepositoryConfigEntity existing = config(MAVEN_ID, FirewallMode.AUDIT);
        when(repositoryRepo.findById(MAVEN_ID))
                .thenReturn(Optional.of(repository(MAVEN_ID, "maven-central", "maven2", "proxy")));
        when(configRepo.findById(MAVEN_ID)).thenReturn(Optional.of(existing));
        when(configRepo.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(policyRepo.findById(STRICT_POLICY_ID)).thenReturn(Optional.of(policy(STRICT_POLICY_ID, "Strict")));
        when(policyRepo.findAll()).thenReturn(List.of(policy(STRICT_POLICY_ID, "Strict")));
        givenEnforcement(true);
        givenViolationCounts();

        mockMvc.perform(put("/api/v1/admin/firewall/repositories/" + MAVEN_ID + "/policy")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"policyId\":\"" + STRICT_POLICY_ID + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.policyId").value(STRICT_POLICY_ID.toString()))
                .andExpect(jsonPath("$.policyName").value("Strict"));

        assertThat(existing.getPolicyId()).isEqualTo(STRICT_POLICY_ID);
    }

    @Test
    @DisplayName("swapping the policy under an armed repository needs the typed phrase")
    void assigningAPolicyToAnArmedRepositoryNeedsConfirmation() throws Exception {
        when(repositoryRepo.findById(MAVEN_ID))
                .thenReturn(Optional.of(repository(MAVEN_ID, "maven-central", "maven2", "proxy")));
        when(configRepo.findById(MAVEN_ID))
                .thenReturn(Optional.of(config(MAVEN_ID, FirewallMode.QUARANTINE)));
        when(policyRepo.findById(STRICT_POLICY_ID)).thenReturn(Optional.of(policy(STRICT_POLICY_ID, "Strict")));
        givenEnforcement(true);

        mockMvc.perform(put("/api/v1/admin/firewall/repositories/" + MAVEN_ID + "/policy")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"policyId\":\"" + STRICT_POLICY_ID + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("CHANGE POLICY maven-central")));

        verify(configRepo, never()).save(any());
        verify(quarantine, never()).invalidatePolicy(any());
    }

    @Test
    @DisplayName("with the phrase the assignment is written and the quarantine queue is woken")
    void assigningAPolicyInvalidatesBothSides() throws Exception {
        FirewallRepositoryConfigEntity existing = config(MAVEN_ID, FirewallMode.QUARANTINE);
        existing.setPolicyId(DEFAULT_POLICY_ID);
        when(repositoryRepo.findById(MAVEN_ID))
                .thenReturn(Optional.of(repository(MAVEN_ID, "maven-central", "maven2", "proxy")));
        when(configRepo.findById(MAVEN_ID)).thenReturn(Optional.of(existing));
        when(configRepo.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(policyRepo.findById(STRICT_POLICY_ID)).thenReturn(Optional.of(policy(STRICT_POLICY_ID, "Strict")));
        when(policyRepo.findAll()).thenReturn(List.of(policy(STRICT_POLICY_ID, "Strict")));
        givenEnforcement(true);
        givenViolationCounts();

        mockMvc.perform(put("/api/v1/admin/firewall/repositories/" + MAVEN_ID + "/policy")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"policyId\":\"" + STRICT_POLICY_ID + "\",\"failMode\":\"FAIL_CLOSED\","
                                + "\"confirmation\":\"CHANGE POLICY maven-central\"}"))
                .andExpect(status().isOk());

        assertThat(existing.getFailMode()).isEqualTo(FirewallFailMode.FAIL_CLOSED);
        // Components held here were judged by the policy the repository is
        // leaving; without both calls they stay held on a verdict that no longer
        // applies until the next sweep.
        verify(quarantine).invalidatePolicy(DEFAULT_POLICY_ID);
        verify(quarantine).invalidatePolicy(STRICT_POLICY_ID);
    }

    @Test
    @DisplayName("clearing the assignment resolves 'the default' to the policy that holds the flag")
    void clearingAnAssignmentInvalidatesTheDefaultPolicy() throws Exception {
        FirewallRepositoryConfigEntity existing = config(MAVEN_ID, FirewallMode.AUDIT);
        existing.setPolicyId(STRICT_POLICY_ID);
        when(repositoryRepo.findById(MAVEN_ID))
                .thenReturn(Optional.of(repository(MAVEN_ID, "maven-central", "maven2", "proxy")));
        when(configRepo.findById(MAVEN_ID)).thenReturn(Optional.of(existing));
        when(configRepo.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(policyRepo.findByIsDefaultTrue())
                .thenReturn(Optional.of(policy(DEFAULT_POLICY_ID, "Default")));
        givenEnforcement(false);
        givenViolationCounts();

        mockMvc.perform(put("/api/v1/admin/firewall/repositories/" + MAVEN_ID + "/policy")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"policyId\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.policyId").value(org.hamcrest.Matchers.nullValue()));

        verify(quarantine).invalidatePolicy(STRICT_POLICY_ID);
        verify(quarantine).invalidatePolicy(DEFAULT_POLICY_ID);
    }

    @Test
    @DisplayName("creating the config row here must not start auditing a repository that was off")
    void assignmentDoesNotChangeTheMode() throws Exception {
        when(repositoryRepo.findById(MAVEN_ID))
                .thenReturn(Optional.of(repository(MAVEN_ID, "maven-central", "maven2", "proxy")));
        when(configRepo.findById(MAVEN_ID)).thenReturn(Optional.empty());
        when(configRepo.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(policyRepo.findById(STRICT_POLICY_ID)).thenReturn(Optional.of(policy(STRICT_POLICY_ID, "Strict")));
        givenEnforcement(false);
        givenViolationCounts();

        mockMvc.perform(put("/api/v1/admin/firewall/repositories/" + MAVEN_ID + "/policy")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"policyId\":\"" + STRICT_POLICY_ID + "\"}"))
                .andExpect(status().isOk())
                // The instance default is OFF; the entity's own default is AUDIT,
                // and taking it would have this endpoint quietly start evaluating
                // downloads it was only asked to configure.
                .andExpect(jsonPath("$.mode").value("OFF"))
                .andExpect(jsonPath("$.effectiveState").value("NOT_EVALUATED"));

        ArgumentCaptor<FirewallRepositoryConfigEntity> saved =
                ArgumentCaptor.forClass(FirewallRepositoryConfigEntity.class);
        verify(configRepo).save(saved.capture());
        assertThat(saved.getValue().getMode()).isEqualTo(FirewallMode.OFF);
    }

    @Test
    @DisplayName("a group repository is refused a policy, for the reason it is refused a mode")
    void groupsGetNoPolicy() throws Exception {
        when(repositoryRepo.findById(MAVEN_ID))
                .thenReturn(Optional.of(repository(MAVEN_ID, "all-maven", "maven2", "GROUP")));

        mockMvc.perform(put("/api/v1/admin/firewall/repositories/" + MAVEN_ID + "/policy")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"policyId\":\"" + STRICT_POLICY_ID + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("member repositories")));

        verify(configRepo, never()).save(any());
    }

    @Test
    @DisplayName("a policy that does not exist is a 404, not a dangling assignment")
    void unknownPolicyIsNotAssigned() throws Exception {
        when(repositoryRepo.findById(MAVEN_ID))
                .thenReturn(Optional.of(repository(MAVEN_ID, "maven-central", "maven2", "proxy")));
        when(policyRepo.findById(STRICT_POLICY_ID)).thenReturn(Optional.empty());

        mockMvc.perform(put("/api/v1/admin/firewall/repositories/" + MAVEN_ID + "/policy")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"policyId\":\"" + STRICT_POLICY_ID + "\"}"))
                .andExpect(status().isNotFound());

        verify(configRepo, never()).save(any());
    }

    @Test
    @DisplayName("the overview says which policy each repository resolves to")
    void statusReportsThePolicyAssignment() throws Exception {
        FirewallRepositoryConfigEntity assigned = config(MAVEN_ID, FirewallMode.AUDIT);
        assigned.setPolicyId(STRICT_POLICY_ID);
        givenEnforcement(false);
        givenRepositories(
                repository(MAVEN_ID, "maven-central", "maven2", "proxy"),
                repository(NPM_ID, "npm-proxy", "npm", "proxy"));
        givenConfigs(assigned, config(NPM_ID, FirewallMode.AUDIT));
        givenViolationCounts();
        when(policyRepo.findAll()).thenReturn(List.of(policy(STRICT_POLICY_ID, "Strict")));

        mockMvc.perform(get("/api/v1/admin/firewall/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repositories[0].policyName").value("Strict"))
                // Null rather than the default policy's name: this repository has
                // chosen nothing, and showing a name here would read as a choice.
                .andExpect(jsonPath("$.repositories[1].policyId")
                        .value(org.hamcrest.Matchers.nullValue()));
    }

    // ── Violations ──────────────────────────────────────────────────────

    @Test
    @DisplayName("violations come back newest first with a continuation token while more remain")
    void violationsArePaged() throws Exception {
        Page<FirewallViolationEntity> page = new PageImpl<>(
                List.of(violation(1L, "maven-central", "pkg:maven/org.example/lib@1.0")),
                PageRequest.of(0, 50),
                120);
        when(violationRepo.findAllByOrderByOccurredAtDesc(any(Pageable.class))).thenReturn(page);

        mockMvc.perform(get("/api/v1/admin/firewall/violations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].repositoryName").value("maven-central"))
                .andExpect(jsonPath("$.items[0].purl").value("pkg:maven/org.example/lib@1.0"))
                .andExpect(jsonPath("$.items[0].advisoryIds[0]").value("CVE-2021-44228"))
                .andExpect(jsonPath("$.items[0].action").value("BLOCK"))
                .andExpect(jsonPath("$.continuationToken").isNotEmpty());
    }

    @Test
    @DisplayName("a repositoryId filter reaches the repository-scoped query")
    void violationsCanBeFilteredByRepository() throws Exception {
        when(violationRepo.findByRepositoryIdOrderByOccurredAtDesc(any(UUID.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 50), 0));

        mockMvc.perform(get("/api/v1/admin/firewall/violations").param("repositoryId", MAVEN_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.continuationToken").value(org.hamcrest.Matchers.nullValue()));

        verify(violationRepo).findByRepositoryIdOrderByOccurredAtDesc(any(UUID.class), any(Pageable.class));
        verify(violationRepo, never()).findAllByOrderByOccurredAtDesc(any(Pageable.class));
    }

    // ── Fixtures ────────────────────────────────────────────────────────

    /**
     * The switch as the controller must see it: its value from the service that
     * enforces it, and only the audit metadata from the row.
     *
     * <p>The row stub is lenient because the paths that refuse a request answer
     * before they need any metadata — but they all consult the service first, so
     * that stub stays strict.
     */
    private void givenEnforcement(boolean enabled) {
        when(enforcementSettings.enforcementEnabled()).thenReturn(enabled);

        FirewallEnforcementSettingsEntity settings = new FirewallEnforcementSettingsEntity();
        settings.setConfigured(true);
        settings.setEnabled(enabled);
        settings.setUpdatedAt(Instant.parse("2026-08-01T09:00:00Z"));
        settings.setUpdatedBy("admin");
        lenient().when(enforcementRepo.current()).thenReturn(settings);
    }

    /** Makes {@code save} behave like the real service: it claims the row and returns it. */
    private void givenTheSwitchCanBeWritten() {
        when(enforcementSettings.save(anyBoolean(), any())).thenAnswer(invocation -> {
            FirewallEnforcementSettingsEntity saved = new FirewallEnforcementSettingsEntity();
            saved.setConfigured(true);
            saved.setEnabled(invocation.getArgument(0));
            saved.setUpdatedAt(Instant.parse("2026-08-01T10:00:00Z"));
            saved.setUpdatedBy(invocation.getArgument(1));
            return saved;
        });
    }

    private void givenRepositories(RepositoryEntity... repositories) {
        when(repositoryRepo.findAll()).thenReturn(List.of(repositories));
    }

    private void givenConfigs(FirewallRepositoryConfigEntity... configs) {
        when(configRepo.findAll()).thenReturn(List.of(configs));
    }

    private void givenViolationCounts() {
        givenViolationCounts(Map.of());
    }

    private void givenViolationCounts(Map<UUID, Long> counts) {
        List<FirewallViolationJpaRepository.RepositoryViolationCount> rows = counts.entrySet().stream()
                .map(entry -> (FirewallViolationJpaRepository.RepositoryViolationCount)
                        new RepositoryViolationCountStub(entry.getKey(), entry.getValue()))
                .toList();
        when(violationRepo.countByRepositorySince(any(Instant.class))).thenReturn(rows);
    }

    private record RepositoryViolationCountStub(UUID repositoryId, long violations)
            implements FirewallViolationJpaRepository.RepositoryViolationCount {

        @Override
        public UUID getRepositoryId() {
            return repositoryId;
        }

        @Override
        public long getViolations() {
            return violations;
        }
    }

    private static RepositoryEntity repository(UUID id, String name, String format, String type) {
        RepositoryEntity entity = new RepositoryEntity();
        entity.setId(id);
        entity.setName(name);
        entity.setFormat(format);
        entity.setType(type);
        return entity;
    }

    private static FirewallPolicyEntity policy(UUID id, String name) {
        FirewallPolicyEntity entity = new FirewallPolicyEntity();
        entity.setId(id);
        entity.setName(name);
        return entity;
    }

    private static FirewallRepositoryConfigEntity config(UUID repositoryId, FirewallMode mode) {
        FirewallRepositoryConfigEntity entity = new FirewallRepositoryConfigEntity();
        entity.setRepositoryId(repositoryId);
        entity.setMode(mode);
        return entity;
    }

    private static FirewallViolationEntity violation(Long id, String repositoryName, String purl) {
        FirewallViolationEntity entity = new FirewallViolationEntity();
        entity.setId(id);
        entity.setRepositoryId(MAVEN_ID);
        entity.setRepositoryName(repositoryName);
        entity.setPurl(purl);
        entity.setRuleType(FirewallRuleType.CVSS_THRESHOLD);
        entity.setAction(FirewallAction.BLOCK);
        entity.setAdvisoryIds(new String[] {"CVE-2021-44228"});
        entity.setOccurredAt(Instant.parse("2026-08-04T12:00:00Z"));
        entity.setRequestContext(Map.of("user", "ci-runner", "ip", "10.0.0.5"));
        return entity;
    }
}
