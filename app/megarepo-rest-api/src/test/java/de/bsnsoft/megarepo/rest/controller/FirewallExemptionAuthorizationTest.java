package de.bsnsoft.megarepo.rest.controller;

import de.bsnsoft.megarepo.core.firewall.FirewallComponentKeyKind;
import de.bsnsoft.megarepo.core.firewall.FirewallExemptionScope;
import de.bsnsoft.megarepo.core.firewall.FirewallExemptionState;
import de.bsnsoft.megarepo.database.repository.RepositoryJpaRepository;
import de.bsnsoft.megarepo.repository.firewall.exemption.ExemptionProperties;
import de.bsnsoft.megarepo.repository.firewall.exemption.ExemptionService;
import de.bsnsoft.megarepo.repository.firewall.exemption.FirewallExemption;
import de.bsnsoft.megarepo.security.SecurityConfig;
import de.bsnsoft.megarepo.security.auth.AnonymousAccessFilter;
import de.bsnsoft.megarepo.security.auth.JwtAuthenticationFilter;
import de.bsnsoft.megarepo.security.auth.ratelimit.LoginRateLimitFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who may ask for an exemption, and who may grant one.
 *
 * <p>Runs the <b>real</b> {@link SecurityConfig} — the actual bean, the actual
 * chain — for the reason {@code FirewallAdminAuthorizationTest} gives: this
 * project expresses authorization there and nowhere else, method security is not
 * enabled, and a test asserting against a locally assembled {@code HttpSecurity}
 * would keep passing after somebody changed the real one.
 *
 * <p>The rule under test is the asymmetric one. Filing a request is
 * {@code authenticated()}, because a developer who hits a firewall 403 has to be
 * able to ask from the block page rather than open a ticket; a request changes
 * nothing until an approver acts. Everything else on the prefix — the list of
 * what an organisation lets past its own firewall, and the act of letting
 * something past — is {@code nx-admin}.
 */
@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(
        classes = {SecurityConfig.class, FirewallExemptionAuthorizationTest.TestConfig.class})
class FirewallExemptionAuthorizationTest {

    private static final String COLLECTION = "/api/v1/firewall/exemptions";
    private static final String SUMMARY = COLLECTION + "/summary";
    private static final UUID ID = UUID.randomUUID();
    private static final String APPROVE = COLLECTION + "/" + ID + "/approve";
    private static final String REVOKE = COLLECTION + "/" + ID + "/revoke";

    private static final String REQUEST_BODY =
            "{\"componentKey\":\"pkg:maven/com.acme/util@1.0.0\",\"justification\":\"needed\"}";

    @Autowired private WebApplicationContext context;
    @Autowired private ExemptionService exemptions;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        // The context — and therefore the mock — is shared across the class, so
        // a "never called" assertion would otherwise see the previous test's call.
        reset(exemptions);
        when(exemptions.request(any())).thenReturn(exemption(FirewallExemptionState.REQUESTED));
        when(exemptions.approve(any(), any(), any(), any()))
                .thenReturn(exemption(FirewallExemptionState.APPROVED));
        when(exemptions.revoke(any(), any(), any()))
                .thenReturn(exemption(FirewallExemptionState.REVOKED));
        when(exemptions.summary()).thenReturn(new ExemptionService.ExemptionSummary(0, 0, 0, 0, 0, 0));
        Page<FirewallExemption> empty = new PageImpl<>(List.of());
        when(exemptions.list(any(), any())).thenReturn(empty);
    }

    @Test
    @DisplayName("anonymous callers reach nothing, filing included")
    void anonymousIsRejected() throws Exception {
        mockMvc.perform(get(COLLECTION)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(SUMMARY)).andExpect(status().isUnauthorized());
        mockMvc.perform(post(COLLECTION).contentType(MediaType.APPLICATION_JSON).content(REQUEST_BODY))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(APPROVE).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());

        verify(exemptions, never()).request(any());
        verify(exemptions, never()).approve(any(), any(), any(), any());
    }

    @Test
    @DisplayName("an authenticated developer may file a request — the whole point of the block page")
    void anyUserMayRequest() throws Exception {
        mockMvc.perform(post(COLLECTION)
                        .with(user("dev").roles("nx-viewer"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_BODY))
                .andExpect(status().isCreated());

        verify(exemptions).request(any());
    }

    @Test
    @DisplayName("a developer may not approve, revoke, or read the list")
    void nonAdminMayNotDecide() throws Exception {
        mockMvc.perform(post(APPROVE)
                        .with(user("dev").roles("nx-viewer"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(REVOKE)
                        .with(user("dev").roles("nx-viewer"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(COLLECTION).with(user("dev").roles("nx-viewer")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(SUMMARY).with(user("dev").roles("nx-viewer")))
                .andExpect(status().isForbidden());

        // A leak past the chain would show up as a decision having been taken.
        verify(exemptions, never()).approve(any(), any(), any(), any());
        verify(exemptions, never()).revoke(any(), any(), any());
        verify(exemptions, never()).list(any(), any());
    }

    @Test
    @DisplayName("nx-admin reaches the decision endpoints and the list")
    void adminMayDecide() throws Exception {
        mockMvc.perform(post(APPROVE)
                        .with(user("ops").roles(SecurityConfig.FIREWALL_ADMIN_ROLE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
        mockMvc.perform(get(COLLECTION).with(user("ops").roles(SecurityConfig.FIREWALL_ADMIN_ROLE)))
                .andExpect(status().isOk());

        verify(exemptions).approve(any(), any(), any(), any());
    }

    private static FirewallExemption exemption(FirewallExemptionState state) {
        return new FirewallExemption(
                ID,
                "pkg:maven/com.acme/util@1.0.0",
                FirewallComponentKeyKind.PURL,
                FirewallExemptionScope.VERSION,
                null,
                null,
                List.of(),
                state,
                null,
                null,
                "needed",
                "dev",
                Instant.parse("2026-08-24T10:00:00Z"),
                null,
                null,
                null);
    }

    @Configuration
    @EnableWebMvc
    static class TestConfig {

        @Bean
        FirewallExemptionController firewallExemptionController(
                ExemptionService exemptions, RepositoryJpaRepository repositories) {
            return new FirewallExemptionController(
                    exemptions, ExemptionProperties.defaults(), repositories);
        }

        @Bean
        ExemptionService exemptionService() {
            return mock(ExemptionService.class);
        }

        @Bean
        RepositoryJpaRepository repositoryRepository() {
            return mock(RepositoryJpaRepository.class);
        }

        /** Same reasoning as {@code FirewallAdminAuthorizationTest}: each filter
         * short-circuits before touching its dependencies for these requests. */
        @Bean
        JwtAuthenticationFilter jwtAuthenticationFilter() {
            return new JwtAuthenticationFilter(null);
        }

        @Bean
        AnonymousAccessFilter anonymousAccessFilter() {
            return new AnonymousAccessFilter(null, null);
        }

        @Bean
        LoginRateLimitFilter loginRateLimitFilter() {
            return new LoginRateLimitFilter(null);
        }

        @Bean
        AuthenticationProvider ldapAwareAuthenticationProvider() {
            return new AuthenticationProvider() {
                @Override
                public Authentication authenticate(Authentication authentication) {
                    return null;
                }

                @Override
                public boolean supports(Class<?> authentication) {
                    return false;
                }
            };
        }
    }
}
