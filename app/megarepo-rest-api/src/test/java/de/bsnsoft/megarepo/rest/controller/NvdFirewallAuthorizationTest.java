package de.bsnsoft.megarepo.rest.controller;

import de.bsnsoft.megarepo.database.entity.NvdFirewallSettingsEntity;
import de.bsnsoft.megarepo.database.repository.NvdFirewallBlockJpaRepository;
import de.bsnsoft.megarepo.database.repository.NvdFirewallSettingsJpaRepository;
import de.bsnsoft.megarepo.database.repository.NvdFirewallWhitelistJpaRepository;
import de.bsnsoft.megarepo.repository.nvd.NvdSyncService;
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

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
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
 * Who may read and change the NVD firewall's control surface.
 *
 * <p>The companion of {@link FirewallAdminAuthorizationTest}, for the older
 * surface that {@link NvdFirewallController} serves under
 * {@code /api/v1/security/nvd-firewall}. It shipped with no authorization at
 * all: no matcher named it, so it fell through to the blanket
 * {@code /api/v1/** -> authenticated()} and any logged-in account — a build
 * agent's read-only deploy user included — could read the vulnerability
 * inventory, add and delete whitelist entries, and switch the firewall off
 * outright.
 *
 * <p>Like its companion this exercises the <b>real</b> {@link SecurityConfig}
 * bean and its real filter chain, not a copy of the rules restated for a test.
 * That is the only arrangement that can catch a regression here, because
 * authorization in this project lives in that class alone — method security is
 * not enabled, so a {@code @PreAuthorize} on the controller would authorize
 * everyone while looking like it did the opposite.
 *
 * <p>Every verb the controller actually exposes is asserted, not a
 * representative sample: a matcher can be scoped so that it covers the subtree
 * but not the bare base path, and the base path is where the settings — the
 * on/off switch and the stored NVD API key — are read and written.
 *
 * <p>The three collaborating filters are constructed with null dependencies on
 * purpose; see {@link FirewallAdminAuthorizationTest} for why each one
 * short-circuits before touching them.
 */
@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = {SecurityConfig.class, NvdFirewallAuthorizationTest.TestConfig.class})
class NvdFirewallAuthorizationTest {

    private static final String BASE = "/api/v1/security/nvd-firewall";
    private static final String SYNC_STATE = BASE + "/sync-state";
    private static final String SYNC = BASE + "/sync";
    private static final String BLOCKS = BASE + "/blocks";
    private static final String WHITELIST = BASE + "/whitelist";
    private static final String WHITELIST_ENTRY = BASE + "/whitelist/42";

    private static final String SETTINGS_BODY = "{\"enabled\":false,\"apiKey\":\"stolen\",\"cvssThreshold\":10.0}";
    private static final String WHITELIST_BODY =
            "{\"entryType\":\"CVE\",\"value\":\"CVE-2021-44228\",\"reason\":\"let log4shell through\"}";

    @Autowired private WebApplicationContext context;
    @Autowired private NvdFirewallSettingsJpaRepository settingsRepo;
    @Autowired private NvdFirewallWhitelistJpaRepository whitelistRepo;
    @Autowired private NvdSyncService syncService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        // The mock beans are singletons in a Spring context that is cached
        // across test methods, so recorded interactions would otherwise carry
        // from one method into the next and make the never() assertions below
        // report a neighbour's calls instead of their own. Ahead of the
        // stubbing, which reset() would otherwise wipe.
        reset(settingsRepo, whitelistRepo, syncService);
        when(settingsRepo.findById(1)).thenReturn(Optional.of(new NvdFirewallSettingsEntity()));
        when(whitelistRepo.findAll()).thenReturn(List.of());
    }

    @Test
    @DisplayName("anonymous callers get 401 on every endpoint, reads included")
    void anonymousIsRejected() throws Exception {
        mockMvc.perform(get(BASE)).andExpect(status().isUnauthorized());
        mockMvc.perform(put(BASE).contentType(MediaType.APPLICATION_JSON).content(SETTINGS_BODY))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(SYNC_STATE)).andExpect(status().isUnauthorized());
        mockMvc.perform(post(SYNC)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(BLOCKS)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(WHITELIST)).andExpect(status().isUnauthorized());
        mockMvc.perform(post(WHITELIST).contentType(MediaType.APPLICATION_JSON).content(WHITELIST_BODY))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete(WHITELIST_ENTRY)).andExpect(status().isUnauthorized());

        assertNothingWasChanged();
    }

    @Test
    @DisplayName("an authenticated non-admin gets 403 — being logged in is not enough")
    void authenticatedNonAdminIsForbidden() throws Exception {
        mockMvc.perform(get(BASE).with(reader())).andExpect(status().isForbidden());
        mockMvc.perform(put(BASE).with(reader()).contentType(MediaType.APPLICATION_JSON).content(SETTINGS_BODY))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(SYNC_STATE).with(reader())).andExpect(status().isForbidden());
        mockMvc.perform(post(SYNC).with(reader())).andExpect(status().isForbidden());
        mockMvc.perform(get(BLOCKS).with(reader())).andExpect(status().isForbidden());
        mockMvc.perform(get(WHITELIST).with(reader())).andExpect(status().isForbidden());
        mockMvc.perform(post(WHITELIST).with(reader()).contentType(MediaType.APPLICATION_JSON).content(WHITELIST_BODY))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete(WHITELIST_ENTRY).with(reader())).andExpect(status().isForbidden());

        assertNothingWasChanged();
    }

    @Test
    @DisplayName("nx-admin reaches the endpoints")
    void adminIsAllowed() throws Exception {
        mockMvc.perform(get(BASE).with(admin())).andExpect(status().isOk());
        mockMvc.perform(get(WHITELIST).with(admin())).andExpect(status().isOk());
    }

    /**
     * The reads are already covered by the status assertions; these guard the
     * writes, which is where a leak past the filter chain would do damage that
     * a status code alone would not reveal.
     */
    private void assertNothingWasChanged() {
        verify(settingsRepo, never()).save(any());
        verify(whitelistRepo, never()).save(any());
        verify(whitelistRepo, never()).deleteById(anyLong());
        verify(syncService, never()).triggerFullSync();
        verify(syncService, never()).triggerDeltaSync();
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor reader() {
        return user("reader").roles("nx-viewer");
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor admin() {
        return user("admin").roles(SecurityConfig.FIREWALL_ADMIN_ROLE);
    }

    @Configuration
    @EnableWebMvc
    static class TestConfig {

        @Bean
        NvdFirewallController nvdFirewallController(
                NvdFirewallSettingsJpaRepository settingsRepo,
                NvdSyncService syncService,
                NvdFirewallBlockJpaRepository blockRepo,
                NvdFirewallWhitelistJpaRepository whitelistRepo) {
            return new NvdFirewallController(settingsRepo, syncService, blockRepo, whitelistRepo);
        }

        @Bean
        NvdFirewallSettingsJpaRepository nvdFirewallSettingsRepository() {
            return mock(NvdFirewallSettingsJpaRepository.class);
        }

        @Bean
        NvdFirewallBlockJpaRepository nvdFirewallBlockRepository() {
            return mock(NvdFirewallBlockJpaRepository.class);
        }

        @Bean
        NvdFirewallWhitelistJpaRepository nvdFirewallWhitelistRepository() {
            return mock(NvdFirewallWhitelistJpaRepository.class);
        }

        @Bean
        NvdSyncService nvdSyncService() {
            return mock(NvdSyncService.class);
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
