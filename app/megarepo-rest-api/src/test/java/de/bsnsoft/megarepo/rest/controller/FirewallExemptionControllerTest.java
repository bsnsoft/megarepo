package de.bsnsoft.megarepo.rest.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.bsnsoft.megarepo.core.firewall.FirewallComponentKeyKind;
import de.bsnsoft.megarepo.core.firewall.FirewallExemptionScope;
import de.bsnsoft.megarepo.core.firewall.FirewallExemptionState;
import de.bsnsoft.megarepo.core.firewall.FirewallRuleType;
import de.bsnsoft.megarepo.database.repository.RepositoryJpaRepository;
import de.bsnsoft.megarepo.repository.firewall.exemption.ExemptionProperties;
import de.bsnsoft.megarepo.repository.firewall.exemption.ExemptionRequest;
import de.bsnsoft.megarepo.repository.firewall.exemption.ExemptionService;
import de.bsnsoft.megarepo.repository.firewall.exemption.FirewallExemption;
import de.bsnsoft.megarepo.rest.advice.GlobalExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What the endpoints do once a caller is past the filter chain.
 *
 * <p>Authorization itself is asserted in {@link FirewallExemptionAuthorizationTest}
 * against the real {@code SecurityConfig}; standalone MockMvc has no filter
 * chain, so a test here could not tell a protected endpoint from an open one.
 * What it can tell is validation, the state machine's HTTP shape, and the one
 * check that is genuinely the controller's: the self-service switch.
 */
class FirewallExemptionControllerTest {

    private static final String BASE = "/api/v1/firewall/exemptions";
    private static final String PURL = "pkg:maven/com.acme/util@1.0.0";
    private static final UUID ID = UUID.randomUUID();

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    private ExemptionService exemptions;
    private RepositoryJpaRepository repositories;
    private MockMvc mockMvc;

    private void withController(ExemptionProperties properties) {
        var controller = new FirewallExemptionController(exemptions, properties, repositories);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @BeforeEach
    void setUp() {
        exemptions = mock(ExemptionService.class);
        repositories = mock(RepositoryJpaRepository.class);
        when(repositories.findAllById(any())).thenReturn(List.of());
        withController(ExemptionProperties.defaults());
        authenticateAs("dev");
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ── Requesting ──────────────────────────────────────────────────────

    @Test
    @DisplayName("a request is created 201 and attributed to the caller")
    void createsRequest() throws Exception {
        when(exemptions.request(any())).thenReturn(exemption(FirewallExemptionState.REQUESTED, null));

        mockMvc.perform(post(BASE)
                        .contentType("application/json")
                        .content(json.writeValueAsString(body(PURL, "needed for the release"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("REQUESTED"))
                .andExpect(jsonPath("$.componentKey").value(PURL));

        ArgumentCaptor<ExemptionRequest> captured = ArgumentCaptor.forClass(ExemptionRequest.class);
        verify(exemptions).request(captured.capture());
        assertThat(captured.getValue().requestedBy())
                .as("the requester is the authenticated caller, never a body field")
                .isEqualTo("dev");
    }

    @Test
    @DisplayName("an unexplained request is a 400 and never reaches the service")
    void justificationIsRequired() throws Exception {
        mockMvc.perform(post(BASE)
                        .contentType("application/json")
                        .content(json.writeValueAsString(body(PURL, "   "))))
                .andExpect(status().isBadRequest());

        verify(exemptions, never()).request(any());
    }

    @Test
    @DisplayName("a request naming no component is a 400")
    void componentKeyIsRequired() throws Exception {
        mockMvc.perform(post(BASE)
                        .contentType("application/json")
                        .content(json.writeValueAsString(body("", "why not"))))
                .andExpect(status().isBadRequest());

        verify(exemptions, never()).request(any());
    }

    @Test
    @DisplayName("with self-service off a non-administrator is refused, and nothing is filed")
    void selfServiceCanBeSwitchedOff() throws Exception {
        withController(new ExemptionProperties(false, Duration.ofDays(90), Duration.ofDays(7), Duration.ofDays(3650)));

        mockMvc.perform(post(BASE)
                        .contentType("application/json")
                        .content(json.writeValueAsString(body(PURL, "needed for the release"))))
                .andExpect(status().isForbidden());

        verify(exemptions, never()).request(any());
    }

    @Test
    @DisplayName("with self-service off an administrator may still file one")
    void adminMayAlwaysFile() throws Exception {
        withController(new ExemptionProperties(false, Duration.ofDays(90), Duration.ofDays(7), Duration.ofDays(3650)));
        authenticateAs("ops", "ROLE_nx-admin");
        when(exemptions.request(any())).thenReturn(exemption(FirewallExemptionState.REQUESTED, null));

        mockMvc.perform(post(BASE)
                        .contentType("application/json")
                        .content(json.writeValueAsString(body(PURL, "on behalf of the team"))))
                .andExpect(status().isCreated());
    }

    // ── Deciding ────────────────────────────────────────────────────────

    @Test
    @DisplayName("approve passes the expiry through verbatim, including 'never'")
    void approvePassesTheExpiry() throws Exception {
        Instant expiry = Instant.parse("2026-12-01T00:00:00Z");
        when(exemptions.approve(eq(ID), eq("dev"), any(), any()))
                .thenReturn(exemption(FirewallExemptionState.APPROVED, expiry));

        mockMvc.perform(post(BASE + "/" + ID + "/approve")
                        .contentType("application/json")
                        .content("{\"expiresAt\":\"2026-12-01T00:00:00Z\",\"note\":\"audited\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("APPROVED"))
                .andExpect(jsonPath("$.expired").value(false));

        verify(exemptions).approve(ID, "dev", "audited", expiry);
    }

    @Test
    @DisplayName("an omitted expiry is not turned into the default — 'never' has to be said")
    void omittedExpiryIsNotDefaulted() throws Exception {
        when(exemptions.approve(any(), any(), any(), any()))
                .thenReturn(exemption(FirewallExemptionState.APPROVED, null));

        mockMvc.perform(post(BASE + "/" + ID + "/approve")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isOk());

        verify(exemptions).approve(eq(ID), eq("dev"), isNull(), isNull());
    }

    @Test
    @DisplayName("an illegal transition is a 409, not a silent no-op")
    void illegalTransitionIsAConflict() throws Exception {
        when(exemptions.approve(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("Exemption is APPROVED and cannot become APPROVED"));

        mockMvc.perform(post(BASE + "/" + ID + "/approve")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("an expiry beyond max-validity is a 400")
    void expiryBeyondCeilingIsRejected() throws Exception {
        when(exemptions.approve(any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("beyond max-validity"));

        mockMvc.perform(post(BASE + "/" + ID + "/approve")
                        .contentType("application/json")
                        .content("{\"expiresAt\":\"2099-01-01T00:00:00Z\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("reject and revoke are their own endpoints, not a field in a body")
    void rejectAndRevoke() throws Exception {
        when(exemptions.reject(any(), any(), any()))
                .thenReturn(exemption(FirewallExemptionState.REJECTED, null));
        when(exemptions.revoke(any(), any(), any()))
                .thenReturn(exemption(FirewallExemptionState.REVOKED, null));

        mockMvc.perform(post(BASE + "/" + ID + "/reject")
                        .contentType("application/json")
                        .content("{\"note\":\"use 2.0\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("REJECTED"));

        mockMvc.perform(post(BASE + "/" + ID + "/revoke")
                        .contentType("application/json")
                        .content("{\"note\":\"supplier compromised\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("REVOKED"));

        verify(exemptions).reject(ID, "dev", "use 2.0");
        verify(exemptions).revoke(ID, "dev", "supplier compromised");
    }

    @Test
    @DisplayName("there is no DELETE — withdrawing is a revocation that stays on the record")
    void thereIsNoDelete() throws Exception {
        mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                                BASE + "/" + ID))
                .andExpect(status().isMethodNotAllowed());
    }

    // ── Reading ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("an exemption that is not there is a 404")
    void unknownIdIsNotFound() throws Exception {
        when(exemptions.find(ID)).thenReturn(Optional.empty());

        mockMvc.perform(get(BASE + "/" + ID)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("the list reports a lapsed exemption as expired even before the sweep flips it")
    void listMarksLapsedRows() throws Exception {
        when(exemptions.list(any(), any())).thenReturn(new PageImpl<>(
                List.of(exemption(FirewallExemptionState.APPROVED, Instant.now().minusSeconds(60))),
                Pageable.ofSize(50),
                1));

        mockMvc.perform(get(BASE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].state").value("APPROVED"))
                .andExpect(jsonPath("$.items[0].expired").value(true));
    }

    @Test
    @DisplayName("the summary carries the default validity, so the dialog can pre-fill the bounded answer")
    void summaryCarriesTheDefaults() throws Exception {
        when(exemptions.summary())
                .thenReturn(new ExemptionService.ExemptionSummary(1, 2, 3, 4, 5, 6));

        mockMvc.perform(get(BASE + "/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requested").value(1))
                .andExpect(jsonPath("$.legacy").value(6))
                .andExpect(jsonPath("$.selfServiceRequests").value(true))
                .andExpect(jsonPath("$.defaultValidity").exists());
    }

    @Test
    @DisplayName("the word 'exemption' is what the path says")
    void theWordIsExemption() {
        assertThat(FirewallExemptionController.BASE_PATH).isEqualTo(BASE);
        assertThat(FirewallExemptionController.BASE_PATH).doesNotContain("waiver", "whitelist");
    }

    // ── Fixtures ────────────────────────────────────────────────────────

    private static void authenticateAs(String user, String... authorities) {
        var token = new UsernamePasswordAuthenticationToken(
                user,
                "n/a",
                java.util.Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList());
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    private static java.util.Map<String, Object> body(String componentKey, String justification) {
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("componentKey", componentKey);
        body.put("scope", "VERSION");
        body.put("justification", justification);
        return body;
    }

    private static FirewallExemption exemption(FirewallExemptionState state, Instant expiresAt) {
        return new FirewallExemption(
                ID,
                PURL,
                FirewallComponentKeyKind.PURL,
                FirewallExemptionScope.VERSION,
                null,
                FirewallRuleType.MIN_AGE,
                List.of(),
                state,
                expiresAt,
                null,
                "needed for the release",
                "dev",
                Instant.parse("2026-08-24T10:00:00Z"),
                state == FirewallExemptionState.REQUESTED ? null : "ops",
                state == FirewallExemptionState.REQUESTED ? null : Instant.parse("2026-08-24T11:00:00Z"),
                null);
    }
}
