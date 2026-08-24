package de.bsnsoft.megarepo.rest.controller;

import de.bsnsoft.megarepo.core.firewall.FirewallQuarantineReason;
import de.bsnsoft.megarepo.core.firewall.FirewallQuarantineResolution;
import de.bsnsoft.megarepo.core.firewall.FirewallQuarantineState;
import de.bsnsoft.megarepo.database.repository.FirewallPolicyJpaRepository;
import de.bsnsoft.megarepo.database.repository.FirewallPolicyRuleJpaRepository;
import de.bsnsoft.megarepo.database.repository.FirewallQuarantineJpaRepository;
import de.bsnsoft.megarepo.database.repository.FirewallRepositoryConfigJpaRepository;
import de.bsnsoft.megarepo.repository.firewall.FirewallEnforcementSettingsService;
import de.bsnsoft.megarepo.repository.firewall.quarantine.FirewallQuarantineEntry;
import de.bsnsoft.megarepo.repository.firewall.quarantine.QuarantineMapper;
import de.bsnsoft.megarepo.repository.firewall.quarantine.QuarantineService;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRuleRegistry;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who may edit a policy, work the quarantine queue, and read the rule
 * catalogue.
 *
 * <p>Runs the <b>real</b> {@link SecurityConfig} — the actual bean, the actual
 * chain. Authorization in this project is expressed there and nowhere else
 * (method security is not enabled, so {@code @PreAuthorize} would be decoration
 * that authorizes everyone), and a test asserting against a locally assembled
 * {@code HttpSecurity} would keep passing after somebody changed the real one.
 *
 * <p>Two of the three surfaces are covered by the existing
 * {@code /api/v1/admin/firewall/**} rule. The third,
 * {@code /api/v1/firewall/rule-types}, sits outside that prefix because the API
 * contract puts it there, and has a matcher of its own — which is exactly the
 * kind of rule that silently stops existing, so it is asserted here rather than
 * assumed.
 */
@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(
        classes = {SecurityConfig.class, FirewallPolicyApiAuthorizationTest.TestConfig.class})
class FirewallPolicyApiAuthorizationTest {

    private static final UUID ID = UUID.fromString("77777777-7777-7777-7777-777777777777");

    private static final String POLICIES = "/api/v1/admin/firewall/policies";
    private static final String QUARANTINE = "/api/v1/admin/firewall/quarantine";
    private static final String RULE_TYPES = "/api/v1/firewall/rule-types";
    private static final String REPOSITORY_POLICY =
            "/api/v1/admin/firewall/repositories/" + ID + "/policy";

    private static final String POLICY_BODY =
            "{\"name\":\"Strict\",\"makeDefault\":false,\"rules\":[]}";
    private static final String DECISION_BODY = "{\"note\":\"checked with the vendor\"}";

    @Autowired private WebApplicationContext context;
    @Autowired private QuarantineService quarantine;
    @Autowired private FirewallPolicyJpaRepository policies;
    @Autowired private FirewallPolicyRuleJpaRepository rules;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        // The context — and therefore every mock in it — is shared across the
        // class, so a "never called" assertion would otherwise see the previous
        // test's call.
        reset(quarantine, policies, rules);
        when(policies.findAll()).thenReturn(List.of());
        when(policies.findByName(any())).thenReturn(Optional.empty());
        when(policies.findByIsDefaultTrue()).thenReturn(Optional.empty());
        when(policies.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(rules.findByPolicyId(any())).thenReturn(List.of());
        when(quarantine.queue(any(), any())).thenReturn(new PageImpl<>(List.of()));
        when(quarantine.summary()).thenReturn(new QuarantineService.QuarantineSummary(0, 0, 0));
        when(quarantine.release(any(), any())).thenReturn(entry());
        when(quarantine.block(any(), any())).thenReturn(entry());
    }

    @Test
    @DisplayName("anonymous callers reach none of it, reads included")
    void anonymousIsRejected() throws Exception {
        mockMvc.perform(get(POLICIES)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(QUARANTINE)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(RULE_TYPES)).andExpect(status().isUnauthorized());
        mockMvc.perform(post(POLICIES).contentType(MediaType.APPLICATION_JSON).content(POLICY_BODY))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(QUARANTINE + "/" + ID + "/release")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(DECISION_BODY))
                .andExpect(status().isUnauthorized());

        verify(policies, never()).save(any());
        verify(quarantine, never()).release(any(), any());
    }

    @Test
    @DisplayName("being logged in is not enough — a developer reaches none of it either")
    void authenticatedNonAdminIsForbidden() throws Exception {
        mockMvc.perform(get(POLICIES).with(user("dev").roles("nx-viewer")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(QUARANTINE).with(user("dev").roles("nx-viewer")))
                .andExpect(status().isForbidden());
        // The one path with a matcher of its own; without it this would be 200.
        mockMvc.perform(get(RULE_TYPES).with(user("dev").roles("nx-viewer")))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete(POLICIES + "/" + ID).with(user("dev").roles("nx-viewer")))
                .andExpect(status().isForbidden());
        mockMvc.perform(put(REPOSITORY_POLICY)
                        .with(user("dev").roles("nx-viewer"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(QUARANTINE + "/" + ID + "/release")
                        .with(user("dev").roles("nx-viewer"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(DECISION_BODY))
                .andExpect(status().isForbidden());

        // A leak past the chain would show up as a decision having been taken.
        verify(quarantine, never()).release(any(), any());
        verify(quarantine, never()).block(any(), any());
        verify(policies, never()).delete(any());
        verify(policies, never()).save(any());
    }

    @Test
    @DisplayName("nx-admin reaches all three surfaces")
    void adminIsAllowed() throws Exception {
        mockMvc.perform(get(POLICIES).with(user("ops").roles(SecurityConfig.FIREWALL_ADMIN_ROLE)))
                .andExpect(status().isOk());
        mockMvc.perform(get(QUARANTINE).with(user("ops").roles(SecurityConfig.FIREWALL_ADMIN_ROLE)))
                .andExpect(status().isOk());
        mockMvc.perform(get(RULE_TYPES).with(user("ops").roles(SecurityConfig.FIREWALL_ADMIN_ROLE)))
                .andExpect(status().isOk());
        mockMvc.perform(post(QUARANTINE + "/" + ID + "/release")
                        .with(user("ops").roles(SecurityConfig.FIREWALL_ADMIN_ROLE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(DECISION_BODY))
                .andExpect(status().isOk());

        verify(quarantine).release(any(), any());
    }

    private static FirewallQuarantineEntry entry() {
        return new FirewallQuarantineEntry(
                ID,
                UUID.randomUUID(),
                "npm-proxy",
                "pkg:npm/left-pad@1.3.0",
                null,
                FirewallQuarantineState.RELEASED,
                FirewallQuarantineReason.MIN_AGE_NOT_MET,
                FirewallQuarantineResolution.MANUAL_RELEASE,
                null,
                Map.of(),
                Instant.parse("2026-08-20T08:00:00Z"),
                Instant.parse("2026-08-24T07:00:00Z"),
                1,
                null,
                null,
                Instant.parse("2026-08-24T07:30:00Z"),
                "ops",
                "checked with the vendor",
                null);
    }

    @Configuration
    @EnableWebMvc
    static class TestConfig {

        @Bean
        FirewallPolicyController firewallPolicyController(
                FirewallPolicyJpaRepository policies,
                FirewallPolicyRuleJpaRepository rules,
                FirewallRepositoryConfigJpaRepository configs,
                FirewallEnforcementSettingsService enforcementSettings,
                QuarantineService quarantine) {
            return new FirewallPolicyController(
                    policies,
                    rules,
                    configs,
                    enforcementSettings,
                    new FirewallRuleRegistry(List.of()),
                    quarantine);
        }

        @Bean
        FirewallQuarantineController firewallQuarantineController(
                QuarantineService quarantine,
                FirewallQuarantineJpaRepository entries,
                FirewallPolicyJpaRepository policies) {
            return new FirewallQuarantineController(
                    quarantine, entries, new QuarantineMapper(), policies);
        }

        @Bean
        FirewallRuleTypeController firewallRuleTypeController() {
            return new FirewallRuleTypeController(new FirewallRuleRegistry(List.of()));
        }

        @Bean
        FirewallPolicyJpaRepository firewallPolicyRepository() {
            return mock(FirewallPolicyJpaRepository.class);
        }

        @Bean
        FirewallPolicyRuleJpaRepository firewallPolicyRuleRepository() {
            return mock(FirewallPolicyRuleJpaRepository.class);
        }

        @Bean
        FirewallRepositoryConfigJpaRepository firewallRepositoryConfigRepository() {
            return mock(FirewallRepositoryConfigJpaRepository.class);
        }

        @Bean
        FirewallQuarantineJpaRepository firewallQuarantineRepository() {
            return mock(FirewallQuarantineJpaRepository.class);
        }

        @Bean
        FirewallEnforcementSettingsService enforcementSettingsService() {
            return mock(FirewallEnforcementSettingsService.class);
        }

        @Bean
        QuarantineService quarantineService() {
            return mock(QuarantineService.class);
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
