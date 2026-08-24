package de.bsnsoft.megarepo.rest.controller;

import de.bsnsoft.megarepo.database.entity.FirewallEnforcementSettingsEntity;
import de.bsnsoft.megarepo.database.repository.FirewallEnforcementSettingsJpaRepository;
import de.bsnsoft.megarepo.database.repository.FirewallPolicyJpaRepository;
import de.bsnsoft.megarepo.database.repository.FirewallRepositoryConfigJpaRepository;
import de.bsnsoft.megarepo.database.repository.FirewallViolationJpaRepository;
import de.bsnsoft.megarepo.database.repository.RepositoryJpaRepository;
import de.bsnsoft.megarepo.repository.firewall.FirewallAuditProperties;
import de.bsnsoft.megarepo.repository.firewall.FirewallEnforcementSettingsService;
import de.bsnsoft.megarepo.repository.firewall.quarantine.QuarantineService;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who may read and change the firewall's control surface.
 *
 * <p>This runs the <b>real</b> {@link SecurityConfig} — the actual bean, the
 * actual filter chain, the actual rules — rather than a copy of its rules
 * rewritten for a test. That distinction is the whole point: authorization in
 * this project is expressed in that one class and nowhere else (method security
 * is not enabled, so a {@code @PreAuthorize} on the controller would be
 * decoration that silently authorizes everyone). A test that asserted against a
 * locally assembled {@code HttpSecurity} would keep passing after someone
 * changed the real one.
 *
 * <p>The three collaborating filters are constructed with null dependencies on
 * purpose. Each one short-circuits before touching them for the requests made
 * here: {@code JwtAuthenticationFilter} only consults its token provider when a
 * Bearer token, NuGet key or access cookie is present; {@code AnonymousAccessFilter}
 * only reads its repositories for {@code /repository/**} and {@code /v2/**}; and
 * {@code LoginRateLimitFilter} only for {@code POST} on the login path. Wiring
 * real ones would drag a database into a test about a filter-chain rule.
 */
@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = {SecurityConfig.class, FirewallAdminAuthorizationTest.TestConfig.class})
class FirewallAdminAuthorizationTest {

    private static final String STATUS = "/api/v1/admin/firewall/status";
    private static final String ENFORCEMENT = "/api/v1/admin/firewall/enforcement";
    private static final String VIOLATIONS = "/api/v1/admin/firewall/violations";

    @Autowired private WebApplicationContext context;
    @Autowired private FirewallEnforcementSettingsJpaRepository enforcementRepo;
    @Autowired private FirewallEnforcementSettingsService enforcementSettings;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        when(enforcementRepo.current()).thenReturn(new FirewallEnforcementSettingsEntity());
    }

    @Test
    @DisplayName("anonymous callers get 401 on every endpoint, reads included")
    void anonymousIsRejected() throws Exception {
        mockMvc.perform(get(STATUS)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(ENFORCEMENT)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(VIOLATIONS)).andExpect(status().isUnauthorized());
        mockMvc.perform(put(ENFORCEMENT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true,\"confirmation\":\"ENABLE ENFORCEMENT\"}"))
                .andExpect(status().isUnauthorized());

        // The switch is written through the service, so that is where a leak
        // past the filter chain would show up first.
        verify(enforcementSettings, never()).save(anyBoolean(), any());
        verify(enforcementRepo, never()).save(any());
    }

    @Test
    @DisplayName("an authenticated non-admin gets 403 — being logged in is not enough")
    void authenticatedNonAdminIsForbidden() throws Exception {
        mockMvc.perform(get(STATUS).with(user("reader").roles("nx-viewer")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(VIOLATIONS).with(user("reader").roles("nx-viewer")))
                .andExpect(status().isForbidden());
        mockMvc.perform(put(ENFORCEMENT)
                        .with(user("reader").roles("nx-viewer"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true,\"confirmation\":\"ENABLE ENFORCEMENT\"}"))
                .andExpect(status().isForbidden());

        // The switch is written through the service, so that is where a leak
        // past the filter chain would show up first.
        verify(enforcementSettings, never()).save(anyBoolean(), any());
        verify(enforcementRepo, never()).save(any());
    }

    @Test
    @DisplayName("nx-admin reaches the endpoints")
    void adminIsAllowed() throws Exception {
        mockMvc.perform(get(ENFORCEMENT).with(user("admin").roles(SecurityConfig.FIREWALL_ADMIN_ROLE)))
                .andExpect(status().isOk());
    }

    @Configuration
    @EnableWebMvc
    static class TestConfig {

        @Bean
        FirewallAdminController firewallAdminController(
                FirewallEnforcementSettingsService enforcementSettings,
                FirewallEnforcementSettingsJpaRepository enforcementRepo,
                FirewallRepositoryConfigJpaRepository configRepo,
                FirewallViolationJpaRepository violationRepo,
                RepositoryJpaRepository repositoryRepo,
                FirewallPolicyJpaRepository policyRepo,
                QuarantineService quarantine) {
            return new FirewallAdminController(
                    enforcementSettings,
                    enforcementRepo,
                    configRepo,
                    violationRepo,
                    repositoryRepo,
                    policyRepo,
                    quarantine,
                    FirewallAuditProperties.defaults());
        }

        @Bean
        FirewallPolicyJpaRepository firewallPolicyRepository() {
            return mock(FirewallPolicyJpaRepository.class);
        }

        @Bean
        QuarantineService quarantineService() {
            return mock(QuarantineService.class);
        }

        @Bean
        FirewallEnforcementSettingsService enforcementSettingsService() {
            return mock(FirewallEnforcementSettingsService.class);
        }

        @Bean
        FirewallEnforcementSettingsJpaRepository enforcementSettingsRepository() {
            return mock(FirewallEnforcementSettingsJpaRepository.class);
        }

        @Bean
        FirewallRepositoryConfigJpaRepository firewallRepositoryConfigRepository() {
            return mock(FirewallRepositoryConfigJpaRepository.class);
        }

        @Bean
        FirewallViolationJpaRepository firewallViolationRepository() {
            return mock(FirewallViolationJpaRepository.class);
        }

        @Bean
        RepositoryJpaRepository repositoryRepository() {
            return mock(RepositoryJpaRepository.class);
        }

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

        /**
         * Named to match the parameter {@code SecurityConfig.filterChain} declares.
         * Never invoked: no test here authenticates through the provider, they
         * inject an already-authenticated context.
         */
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
