package de.bsnsoft.megarepo.rest.controller;

import de.bsnsoft.megarepo.core.firewall.FirewallQuarantineReason;
import de.bsnsoft.megarepo.core.firewall.FirewallQuarantineResolution;
import de.bsnsoft.megarepo.core.firewall.FirewallQuarantineState;
import de.bsnsoft.megarepo.database.entity.FirewallPolicyEntity;
import de.bsnsoft.megarepo.database.entity.FirewallQuarantineEntity;
import de.bsnsoft.megarepo.database.repository.FirewallPolicyJpaRepository;
import de.bsnsoft.megarepo.database.repository.FirewallQuarantineJpaRepository;
import de.bsnsoft.megarepo.repository.firewall.quarantine.FirewallQuarantineEntry;
import de.bsnsoft.megarepo.repository.firewall.quarantine.QuarantineDecision;
import de.bsnsoft.megarepo.repository.firewall.quarantine.QuarantineMapper;
import de.bsnsoft.megarepo.repository.firewall.quarantine.QuarantineQuery;
import de.bsnsoft.megarepo.repository.firewall.quarantine.QuarantineService;
import de.bsnsoft.megarepo.rest.advice.GlobalExceptionHandler;
import de.bsnsoft.megarepo.security.SecurityConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What the quarantine queue does over HTTP.
 *
 * <p>Three properties carry the weight, and all three are about a queue an
 * operator has to be able to trust: a decision is attributed to whoever took it
 * and carries a reason, an illegal transition is refused rather than absorbed,
 * and nothing here can make a row disappear.
 *
 * <p>Authorization is proven against the real {@code SecurityConfig} in
 * {@link FirewallPolicyApiAuthorizationTest}; a standalone MockMvc has no filter
 * chain.
 */
class FirewallQuarantineControllerTest {

    private static final String BASE = "/api/v1/admin/firewall/quarantine";
    private static final UUID ENTRY_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID REPOSITORY_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID POLICY_ID = UUID.fromString("66666666-6666-6666-6666-666666666666");
    private static final String PURL = "pkg:npm/left-pad@1.3.0";

    private QuarantineService quarantine;
    private FirewallQuarantineJpaRepository entries;
    private FirewallPolicyJpaRepository policies;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        quarantine = mock(QuarantineService.class);
        entries = mock(FirewallQuarantineJpaRepository.class);
        policies = mock(FirewallPolicyJpaRepository.class);
        when(policies.findAllById(any())).thenReturn(List.of());

        mockMvc = MockMvcBuilders.standaloneSetup(new FirewallQuarantineController(
                        quarantine, entries, new QuarantineMapper(), policies))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken("ops", "n/a", List.of()));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("the endpoint lives under the path SecurityConfig restricts to nx-admin")
    void endpointIsCoveredByTheAdminRule() {
        String mapping = FirewallQuarantineController.class.getAnnotation(RequestMapping.class).value()[0];
        String guardedPrefix = SecurityConfig.FIREWALL_ADMIN_PATH_PATTERN.replace("**", "");

        assertThat(mapping + "/")
                .as("the queue lists what this instance is refusing and offers a button that "
                        + "serves it; moving it out of %s would leave that to any logged-in user",
                        SecurityConfig.FIREWALL_ADMIN_PATH_PATTERN)
                .startsWith(guardedPrefix);
    }

    // ── Reading ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("the filters reach the service unchanged, and none of them defaults to a value")
    void filtersArePassedThrough() throws Exception {
        when(quarantine.queue(any(), any())).thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get(BASE)
                        .param("state", "QUARANTINED")
                        .param("repositoryId", REPOSITORY_ID.toString())
                        .param("reason", "MIN_AGE_NOT_MET")
                        .param("search", "left-pad"))
                .andExpect(status().isOk());

        ArgumentCaptor<QuarantineQuery> query = ArgumentCaptor.forClass(QuarantineQuery.class);
        verify(quarantine).queue(query.capture(), eq(PageRequest.of(0, 50)));
        assertThat(query.getValue().state()).isEqualTo(FirewallQuarantineState.QUARANTINED);
        assertThat(query.getValue().repositoryId()).isEqualTo(REPOSITORY_ID);
        assertThat(query.getValue().reason()).isEqualTo(FirewallQuarantineReason.MIN_AGE_NOT_MET);
        assertThat(query.getValue().componentKeyContains()).isEqualTo("left-pad");
    }

    @Test
    @DisplayName("an unfiltered list asks for everything — released and blocked rows are the audit trail")
    void listDoesNotHideDecidedEntries() throws Exception {
        when(quarantine.queue(any(), any())).thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get(BASE)).andExpect(status().isOk());

        ArgumentCaptor<QuarantineQuery> query = ArgumentCaptor.forClass(QuarantineQuery.class);
        verify(quarantine).queue(query.capture(), any());
        assertThat(query.getValue().state())
                .as("defaulting to QUARANTINED here would make the retention window invisible")
                .isNull();
    }

    @Test
    @DisplayName("advisory ids are hoisted out of the snapshot and the policy name is resolved live")
    void listFlattensTheSnapshot() throws Exception {
        when(quarantine.queue(any(), any())).thenReturn(new PageImpl<>(List.of(entry(
                FirewallQuarantineState.QUARANTINED,
                Map.of(
                        "advisoryIds", List.of("GHSA-aaaa", "CVE-2026-1"),
                        "policyName", "Default (as it was called then)")))));
        when(policies.findAllById(any())).thenReturn(List.of(policy("Strict")));

        mockMvc.perform(get(BASE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].componentKey").value(PURL))
                .andExpect(jsonPath("$.items[0].advisoryIds[0]").value("GHSA-aaaa"))
                .andExpect(jsonPath("$.items[0].advisoryIds[1]").value("CVE-2026-1"))
                .andExpect(jsonPath("$.items[0].policyName").value("Strict"))
                .andExpect(jsonPath("$.items[0].nextEvaluationAt").exists());
    }

    @Test
    @DisplayName("a deleted policy leaves the snapshot's name, which is all that is left of it")
    void policyNameFallsBackToTheSnapshot() throws Exception {
        when(quarantine.queue(any(), any())).thenReturn(new PageImpl<>(List.of(
                entry(FirewallQuarantineState.QUARANTINED, Map.of("policyName", "Retired policy")))));
        when(policies.findAllById(any())).thenReturn(List.of());

        mockMvc.perform(get(BASE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].policyName").value("Retired policy"));
    }

    @Test
    @DisplayName("the summary is the three state counts and nothing else")
    void summaryReportsCounts() throws Exception {
        when(quarantine.summary()).thenReturn(new QuarantineService.QuarantineSummary(7, 2, 1));

        mockMvc.perform(get(BASE + "/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quarantined").value(7))
                .andExpect(jsonPath("$.released").value(2))
                .andExpect(jsonPath("$.blocked").value(1));
    }

    @Test
    @DisplayName("one entry can be read by id, and an unknown one is a 404")
    void readsOneEntry() throws Exception {
        when(entries.findById(ENTRY_ID)).thenReturn(Optional.of(entity()));

        mockMvc.perform(get(BASE + "/" + ENTRY_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.componentKey").value(PURL));

        when(entries.findById(ENTRY_ID)).thenReturn(Optional.empty());
        mockMvc.perform(get(BASE + "/" + ENTRY_ID)).andExpect(status().isNotFound());
    }

    // ── Deciding ────────────────────────────────────────────────────────

    @Test
    @DisplayName("a release is recorded as MANUAL_RELEASE, by the caller, with their note")
    void releaseIsAttributed() throws Exception {
        when(quarantine.release(any(), any()))
                .thenReturn(entry(FirewallQuarantineState.RELEASED, Map.of()));

        mockMvc.perform(post(BASE + "/" + ENTRY_ID + "/release")
                        .contentType("application/json")
                        .content("{\"note\":\"vendor confirmed the release is genuine\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("RELEASED"));

        ArgumentCaptor<QuarantineDecision> decision = ArgumentCaptor.forClass(QuarantineDecision.class);
        verify(quarantine).release(eq(ENTRY_ID), decision.capture());
        assertThat(decision.getValue().resolution())
                .isEqualTo(FirewallQuarantineResolution.MANUAL_RELEASE);
        assertThat(decision.getValue().decidedBy())
                .as("who decided is the authenticated caller, never a body field")
                .isEqualTo("ops");
        assertThat(decision.getValue().note()).isEqualTo("vendor confirmed the release is genuine");
    }

    @Test
    @DisplayName("a block is recorded as MANUAL_BLOCK")
    void blockIsAttributed() throws Exception {
        when(quarantine.block(any(), any())).thenReturn(entry(FirewallQuarantineState.BLOCKED, Map.of()));

        mockMvc.perform(post(BASE + "/" + ENTRY_ID + "/block")
                        .contentType("application/json")
                        .content("{\"note\":\"replaced by an internal build\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("BLOCKED"));

        ArgumentCaptor<QuarantineDecision> decision = ArgumentCaptor.forClass(QuarantineDecision.class);
        verify(quarantine).block(eq(ENTRY_ID), decision.capture());
        assertThat(decision.getValue().resolution()).isEqualTo(FirewallQuarantineResolution.MANUAL_BLOCK);
    }

    @Test
    @DisplayName("an unexplained decision is a 400 and never reaches the state machine")
    void noteIsRequired() throws Exception {
        mockMvc.perform(post(BASE + "/" + ENTRY_ID + "/release")
                        .contentType("application/json")
                        .content("{\"note\":\"   \"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(BASE + "/" + ENTRY_ID + "/block")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest());

        verify(quarantine, never()).release(any(), any());
        verify(quarantine, never()).block(any(), any());
    }

    @Test
    @DisplayName("an illegal transition comes back as 409, not as a 500")
    void illegalTransitionIsAConflict() throws Exception {
        when(quarantine.release(any(), any()))
                .thenThrow(new IllegalStateException("Quarantine entry is RELEASED and cannot be released"));

        mockMvc.perform(post(BASE + "/" + ENTRY_ID + "/release")
                        .contentType("application/json")
                        .content("{\"note\":\"again\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("an entry that is not there is a 404 from the state machine's own lookup")
    void unknownEntryIsNotFound() throws Exception {
        when(quarantine.block(any(), any()))
                .thenThrow(new java.util.NoSuchElementException("Quarantine entry not found"));

        mockMvc.perform(post(BASE + "/" + ENTRY_ID + "/block")
                        .contentType("application/json")
                        .content("{\"note\":\"gone\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("there is no DELETE — a decided entry is the record of what the firewall did")
    void deletionIsNotOffered() throws Exception {
        mockMvc.perform(delete(BASE + "/" + ENTRY_ID))
                .andExpect(status().isMethodNotAllowed());
    }

    // ── Fixtures ────────────────────────────────────────────────────────

    private static FirewallQuarantineEntry entry(
            FirewallQuarantineState state, Map<String, Object> evaluation) {

        return new FirewallQuarantineEntry(
                ENTRY_ID,
                REPOSITORY_ID,
                "npm-proxy",
                PURL,
                "left-pad/-/left-pad-1.3.0.tgz",
                state,
                FirewallQuarantineReason.MIN_AGE_NOT_MET,
                state == FirewallQuarantineState.QUARANTINED
                        ? null
                        : FirewallQuarantineResolution.MANUAL_RELEASE,
                POLICY_ID,
                evaluation,
                Instant.parse("2026-08-20T08:00:00Z"),
                Instant.parse("2026-08-24T07:00:00Z"),
                12,
                Instant.parse("2026-08-24T06:00:00Z"),
                Instant.parse("2026-08-27T08:00:00Z"),
                null,
                null,
                null,
                null);
    }

    private static FirewallQuarantineEntity entity() {
        FirewallQuarantineEntity entity = new FirewallQuarantineEntity();
        entity.setId(ENTRY_ID);
        entity.setRepositoryId(REPOSITORY_ID);
        entity.setRepositoryName("npm-proxy");
        entity.setComponentKey(PURL);
        entity.setState(FirewallQuarantineState.QUARANTINED);
        entity.setReasonCode(FirewallQuarantineReason.MIN_AGE_NOT_MET);
        entity.setFirstSeen(Instant.parse("2026-08-20T08:00:00Z"));
        entity.setLastSeen(Instant.parse("2026-08-24T07:00:00Z"));
        return entity;
    }

    private static FirewallPolicyEntity policy(String name) {
        FirewallPolicyEntity policy = new FirewallPolicyEntity();
        policy.setId(POLICY_ID);
        policy.setName(name);
        return policy;
    }
}
