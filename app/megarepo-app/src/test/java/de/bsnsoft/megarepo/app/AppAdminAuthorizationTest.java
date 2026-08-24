package de.bsnsoft.megarepo.app;

import de.bsnsoft.megarepo.app.admin.AdminController;
import de.bsnsoft.megarepo.app.license.LicenseController;
import de.bsnsoft.megarepo.app.license.LicenseService;
import de.bsnsoft.megarepo.app.migration.MigrationController;
import de.bsnsoft.megarepo.app.migration.NexusMigrationService;
import de.bsnsoft.megarepo.app.setup.RepositoryPresetLoader;
import de.bsnsoft.megarepo.database.repository.GroupMemberJpaRepository;
import de.bsnsoft.megarepo.database.repository.RepositoryJpaRepository;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who may rewrite the repository set in bulk, drive a Nexus migration, and
 * install or remove the license.
 *
 * <p>The half of the system-administration rules whose controllers live in this
 * module rather than in {@code megarepo-rest-api}; its sibling there is
 * {@code SystemAdminAuthorizationTest}, and the split follows the module
 * boundary, not a difference in severity. Both were reachable by any logged-in
 * account: {@code POST /api/v1/admin/import-repos} takes a YAML document
 * describing repositories to create, and the migration endpoints take a remote
 * Nexus URL with credentials and run against it.
 *
 * <p>Drives the real {@link SecurityConfig} bean and its real filter chain, for
 * the reason spelled out in {@code NvdFirewallAuthorizationTest}: authorization
 * in this project exists only there, so a test that restated the rules would
 * prove nothing about the running application.
 *
 * <p>The license read is the one deliberate exception in this group and is
 * asserted from both sides — a non-admin must still get it, an anonymous caller
 * must not — because the sidebar and the dashboard fetch it on every page load
 * for every logged-in user. Gating it would have blanked that banner for
 * non-administrators, which is a behavior change with no security gain: the
 * payload is the edition, the licensee and a seat count.
 */
@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = {SecurityConfig.class, AppAdminAuthorizationTest.TestConfig.class})
class AppAdminAuthorizationTest {

    private static final String IMPORT_REPOS = "/api/v1/admin/import-repos";
    private static final String EXPORT_REPOS = "/api/v1/admin/export-repos";
    private static final String MIGRATE_PREVIEW = "/api/v1/admin/migrate/nexus/preview";
    private static final String MIGRATE_EXECUTE = "/api/v1/admin/migrate/nexus/execute";
    private static final String LICENSE = "/api/v1/system/license";

    private static final String YAML_BODY = "repositories:\n  - name: attacker\n    format: maven2\n    type: proxy\n";

    private static final String MIGRATION_BODY =
            """
            {"nexusUrl":"https://attacker.example","username":"u","password":"p"}""";

    @Autowired private WebApplicationContext context;
    @Autowired private RepositoryPresetLoader presetLoader;
    @Autowired private NexusMigrationService migrationService;
    @Autowired private LicenseService licenseService;
    @Autowired private RepositoryJpaRepository repositoryJpaRepository;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        // The mock beans are singletons in a Spring context that is cached
        // across test methods, so recorded interactions would otherwise carry
        // from one method into the next and make the never() assertions below
        // report a neighbour's calls instead of their own.
        reset(presetLoader, migrationService, licenseService, repositoryJpaRepository);
    }

    @Test
    @DisplayName("anonymous callers get 401 on bulk import, migration and license changes")
    void anonymousIsRejected() throws Exception {
        mockMvc.perform(yaml(post(IMPORT_REPOS))).andExpect(status().isUnauthorized());
        mockMvc.perform(get(EXPORT_REPOS)).andExpect(status().isUnauthorized());
        mockMvc.perform(json(post(MIGRATE_PREVIEW))).andExpect(status().isUnauthorized());
        mockMvc.perform(json(post(MIGRATE_EXECUTE))).andExpect(status().isUnauthorized());
        mockMvc.perform(json(post(LICENSE))).andExpect(status().isUnauthorized());
        mockMvc.perform(delete(LICENSE)).andExpect(status().isUnauthorized());

        // The read is open to logged-in users, not to the public.
        mockMvc.perform(get(LICENSE)).andExpect(status().isUnauthorized());

        assertNothingWasChanged();
    }

    @Test
    @DisplayName("a logged-in non-admin cannot import repositories or run a migration")
    void nonAdminIsForbidden() throws Exception {
        mockMvc.perform(yaml(post(IMPORT_REPOS)).with(reader())).andExpect(status().isForbidden());
        mockMvc.perform(get(EXPORT_REPOS).with(reader())).andExpect(status().isForbidden());
        mockMvc.perform(json(post(MIGRATE_PREVIEW)).with(reader())).andExpect(status().isForbidden());
        mockMvc.perform(json(post(MIGRATE_EXECUTE)).with(reader())).andExpect(status().isForbidden());
        mockMvc.perform(json(post(LICENSE)).with(reader())).andExpect(status().isForbidden());
        mockMvc.perform(delete(LICENSE).with(reader())).andExpect(status().isForbidden());

        assertNothingWasChanged();
    }

    /**
     * The carve-out. Asserted on its own so that tightening
     * {@code SYSTEM_ADMIN_PATH_PATTERN} later cannot silently take the sidebar
     * banner away from every non-administrator.
     */
    @Test
    @DisplayName("a non-admin still reads the license banner the sidebar shows them")
    void nonAdminKeepsLicenseRead() throws Exception {
        when(licenseService.getLicenseInfo()).thenReturn(Optional.empty());

        mockMvc.perform(get(LICENSE).with(reader())).andExpect(status().isOk());

        assertNothingWasChanged();
    }

    @Test
    @DisplayName("nx-admin reaches the administrative endpoints")
    void adminIsAllowed() throws Exception {
        when(repositoryJpaRepository.findAll()).thenReturn(List.of());
        when(licenseService.getLicenseInfo()).thenReturn(Optional.empty());

        mockMvc.perform(get(EXPORT_REPOS).with(admin())).andExpect(status().isOk());
        mockMvc.perform(get(LICENSE).with(admin())).andExpect(status().isOk());
    }

    private void assertNothingWasChanged() throws Exception {
        verify(presetLoader, never()).loadFromYamlString(anyString());
        verify(migrationService, never()).preview(any());
        verify(migrationService, never()).execute(any());
        verify(licenseService, never()).uploadLicense(any());
        verify(licenseService, never()).removeLicense();
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder json(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder) {
        return builder.contentType(MediaType.APPLICATION_JSON).content(MIGRATION_BODY);
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder yaml(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder) {
        return builder.contentType(MediaType.valueOf("text/yaml")).content(YAML_BODY);
    }

    private static RequestPostProcessor reader() {
        return user("reader").roles("nx-viewer");
    }

    private static RequestPostProcessor admin() {
        return user("admin").roles(SecurityConfig.ADMIN_ROLE);
    }

    @Configuration
    @EnableWebMvc
    static class TestConfig {

        @Bean
        AdminController adminController(
                RepositoryPresetLoader presetLoader,
                RepositoryJpaRepository repositoryJpaRepository,
                GroupMemberJpaRepository groupMemberJpaRepository) {
            return new AdminController(presetLoader, repositoryJpaRepository, groupMemberJpaRepository);
        }

        @Bean
        MigrationController migrationController(NexusMigrationService migrationService) {
            return new MigrationController(migrationService);
        }

        @Bean
        LicenseController licenseController(LicenseService licenseService) {
            return new LicenseController(licenseService);
        }

        @Bean
        RepositoryPresetLoader repositoryPresetLoader() {
            return mock(RepositoryPresetLoader.class);
        }

        @Bean
        RepositoryJpaRepository repositoryJpaRepository() {
            return mock(RepositoryJpaRepository.class);
        }

        @Bean
        GroupMemberJpaRepository groupMemberJpaRepository() {
            return mock(GroupMemberJpaRepository.class);
        }

        @Bean
        NexusMigrationService nexusMigrationService() {
            return mock(NexusMigrationService.class);
        }

        @Bean
        LicenseService licenseService() {
            return mock(LicenseService.class);
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
