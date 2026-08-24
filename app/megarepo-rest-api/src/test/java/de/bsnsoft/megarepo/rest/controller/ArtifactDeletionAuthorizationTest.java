package de.bsnsoft.megarepo.rest.controller;

import de.bsnsoft.megarepo.core.format.ComponentUploadHandler;
import de.bsnsoft.megarepo.core.format.FormatPlugin;
import de.bsnsoft.megarepo.core.format.FormatRegistry;
import de.bsnsoft.megarepo.core.format.FormatResponse;
import de.bsnsoft.megarepo.core.repository.RepositoryConfig;
import de.bsnsoft.megarepo.core.repository.RepositoryConfigService;
import de.bsnsoft.megarepo.core.repository.RepositoryType;
import de.bsnsoft.megarepo.database.entity.ComponentEntity;
import de.bsnsoft.megarepo.database.entity.RepositoryEntity;
import de.bsnsoft.megarepo.database.repository.AssetJpaRepository;
import de.bsnsoft.megarepo.database.repository.BlobStoreJpaRepository;
import de.bsnsoft.megarepo.database.repository.ComponentJpaRepository;
import de.bsnsoft.megarepo.database.repository.GroupMemberJpaRepository;
import de.bsnsoft.megarepo.database.repository.RepositoryJpaRepository;
import de.bsnsoft.megarepo.repository.ActivityBroadcaster;
import de.bsnsoft.megarepo.repository.AssetService;
import de.bsnsoft.megarepo.repository.AuditService;
import de.bsnsoft.megarepo.repository.ComponentService;
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
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who may destroy artifacts — and, just as much, who may keep working.
 *
 * <p>Three endpoints are closed here and each of them shares its prefix with
 * endpoints that an ordinary account must keep reaching, so unlike
 * {@link OperationalAdminAuthorizationTest} the rules are verb-precise rather
 * than whole-prefix. That makes the second half of this class the more
 * important one: the assertions that browse, search, upload and repository
 * provisioning still work for a non-administrator. A too-broad matcher here
 * would not look like a security bug in production, it would look like the
 * build farm stopped publishing, which is the sort of breakage that gets a
 * security fix reverted wholesale.
 *
 * <p>What is closed:
 *
 * <ul>
 *   <li>{@code DELETE /api/v1/repositories/{name}} — the repository, gone.
 *   <li>{@code DELETE /api/v1/components/{id}} — the component, its assets and
 *       their blobs.
 *   <li>{@code DELETE /api/v1/assets/{id}} — the asset and its blob.
 * </ul>
 *
 * <p>What deliberately stays open, and why the assertions below exist:
 *
 * <ul>
 *   <li>{@code GET /api/v1/repositories} — read by eight pages of the web UI,
 *       three of them an ordinary user's own (dashboard, browse, upload), and
 *       by seven scripted call sites under {@code test-projects/}.
 *   <li>{@code POST}/{@code PUT /api/v1/repositories} — the most heavily
 *       documented write in the project: {@code docs/admin-guide.md} §5,
 *       {@code docs/migration-from-nexus.md} §2.2, {@code test-projects/setup.sh},
 *       the upgrade and docker test scripts and two k6 scenarios. A customer who
 *       followed the migration guide has this in a bootstrap job.
 *   <li>{@code POST /api/v1/components/upload} — the documented publish
 *       endpoint ({@code docs/admin-guide.md} §5, three CI recipes). It belongs
 *       to {@code UploadController}, a second controller sharing the
 *       {@code /api/v1/components} prefix with {@code ComponentController};
 *       a subtree rule would have taken it along with the delete.
 *   <li>{@code GET /api/v1/components}, {@code GET /api/v1/assets},
 *       {@code GET /api/v1/search} — browsing, which is exactly what the
 *       read-only role is for.
 * </ul>
 *
 * <p>Drives the <b>real</b> {@link SecurityConfig} bean, for the reason given
 * in {@link OperationalAdminAuthorizationTest}.
 */
@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = {SecurityConfig.class, ArtifactDeletionAuthorizationTest.TestConfig.class})
class ArtifactDeletionAuthorizationTest {

    private static final UUID COMPONENT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ASSET_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private static final String REPO_NAME = "maven-internal";

    private static final String REPOSITORIES = "/api/v1/repositories";
    private static final String REPOSITORY = REPOSITORIES + "/" + REPO_NAME;
    private static final String REPOSITORY_MEMBERS = REPOSITORY + "/members";

    private static final String COMPONENTS = "/api/v1/components";
    private static final String COMPONENT = COMPONENTS + "/" + COMPONENT_ID;
    private static final String COMPONENT_UPLOAD = COMPONENTS + "/upload";

    private static final String ASSETS = "/api/v1/assets";
    private static final String ASSET = ASSETS + "/" + ASSET_ID;

    private static final String SEARCH = "/api/v1/search";

    private static final String CREATE_REPO_BODY =
            """
            {"name":"maven-internal","format":"maven","type":"HOSTED","online":true,
             "blobStoreName":"default","attributes":{}}""";

    private static final String UPDATE_REPO_BODY = """
            {"online":true,"attributes":{}}""";

    @Autowired private WebApplicationContext context;
    @Autowired private RepositoryJpaRepository repositoryRepo;
    @Autowired private BlobStoreJpaRepository blobStoreRepo;
    @Autowired private ComponentJpaRepository componentRepo;
    @Autowired private AssetJpaRepository assetRepo;
    @Autowired private GroupMemberJpaRepository groupMemberRepo;
    @Autowired private ComponentService componentService;
    @Autowired private AssetService assetService;
    @Autowired private FormatRegistry formatRegistry;
    @Autowired private RepositoryConfigService repositoryConfigService;
    @Autowired private AuditService auditService;
    @Autowired private ActivityBroadcaster activityBroadcaster;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        // Singleton mocks in a context cached across methods; without this the
        // never() assertions would report a neighbouring method's calls.
        reset(
                repositoryRepo,
                blobStoreRepo,
                componentRepo,
                assetRepo,
                groupMemberRepo,
                componentService,
                assetService,
                formatRegistry,
                repositoryConfigService,
                auditService,
                activityBroadcaster);
    }

    // ── The three closed deletes ────────────────────────────────────────

    @Test
    @DisplayName("anonymous callers get 401 on all three deletes")
    void anonymousCannotDelete() throws Exception {
        mockMvc.perform(delete(REPOSITORY)).andExpect(status().isUnauthorized());
        mockMvc.perform(delete(COMPONENT)).andExpect(status().isUnauthorized());
        mockMvc.perform(delete(ASSET)).andExpect(status().isUnauthorized());

        assertNothingWasDeleted();
    }

    @Test
    @DisplayName("a logged-in non-admin cannot delete a repository, component or asset")
    void nonAdminCannotDelete() throws Exception {
        mockMvc.perform(delete(REPOSITORY).with(reader())).andExpect(status().isForbidden());
        mockMvc.perform(delete(COMPONENT).with(reader())).andExpect(status().isForbidden());
        mockMvc.perform(delete(ASSET).with(reader())).andExpect(status().isForbidden());

        assertNothingWasDeleted();
    }

    @Test
    @DisplayName("nx-admin still deletes")
    void adminCanDelete() throws Exception {
        var entity = new RepositoryEntity();
        entity.setName(REPO_NAME);
        when(repositoryRepo.findByName(REPO_NAME)).thenReturn(Optional.of(entity));
        when(componentService.deleteComponentWithAssets(COMPONENT_ID)).thenReturn(true);
        when(assetService.deleteAsset(ASSET_ID)).thenReturn(true);

        mockMvc.perform(delete(REPOSITORY).with(admin())).andExpect(status().isNoContent());
        mockMvc.perform(delete(COMPONENT).with(admin())).andExpect(status().isNoContent());
        mockMvc.perform(delete(ASSET).with(admin())).andExpect(status().isNoContent());

        // Reaching the handler is the point: without these the class would pass
        // against a chain that denied the deletes to everyone.
        verify(repositoryRepo).delete(entity);
        verify(componentService).deleteComponentWithAssets(COMPONENT_ID);
        verify(assetService).deleteAsset(ASSET_ID);
    }

    /**
     * The gates are tied to {@code DELETE}, and the component gate's single
     * wildcard segment also spans {@code /api/v1/components/upload}. This pins
     * that down from the other side: the same paths under a different verb are
     * not caught by the delete rules.
     */
    @Test
    @DisplayName("the delete gates do not spill onto other verbs of the same paths")
    void deleteGatesAreVerbPrecise() throws Exception {
        var entity = new RepositoryEntity();
        entity.setId(UUID.randomUUID());
        entity.setName(REPO_NAME);
        entity.setAttributes(Map.of());

        var component = new ComponentEntity();
        component.setId(COMPONENT_ID);
        component.setRepositoryId(entity.getId());

        when(repositoryRepo.findByName(REPO_NAME)).thenReturn(Optional.of(entity));
        when(repositoryRepo.findById(entity.getId())).thenReturn(Optional.of(entity));
        when(componentRepo.findById(COMPONENT_ID)).thenReturn(Optional.of(component));
        when(assetRepo.findByComponentId(any(), any())).thenReturn(Page.empty());

        mockMvc.perform(get(REPOSITORY).with(reader())).andExpect(status().isOk());
        mockMvc.perform(get(COMPONENT).with(reader())).andExpect(status().isOk());

        assertNothingWasDeleted();
    }

    // ── What has to keep working ────────────────────────────────────────

    /**
     * The regression that would hurt most. {@code GET /api/v1/repositories} is
     * the dashboard, the browse page and the upload page's repository picker;
     * closing it would leave a read-only user staring at an empty application.
     */
    @Test
    @DisplayName("a non-admin still lists and reads repositories")
    void nonAdminKeepsRepositoryReads() throws Exception {
        var entity = new RepositoryEntity();
        entity.setName(REPO_NAME);
        entity.setAttributes(Map.of());
        when(repositoryRepo.findAll()).thenReturn(List.of(entity));
        when(repositoryRepo.findByName(REPO_NAME)).thenReturn(Optional.of(entity));
        when(groupMemberRepo.findByGroupRepoIdOrderBySortOrder(any())).thenReturn(List.of());

        mockMvc.perform(get(REPOSITORIES).with(reader())).andExpect(status().isOk());
        mockMvc.perform(get(REPOSITORY).with(reader())).andExpect(status().isOk());
        mockMvc.perform(get(REPOSITORY_MEMBERS).with(reader())).andExpect(status().isOk());

        verify(repositoryRepo).findAll();
    }

    /**
     * Repository provisioning stays where it was. Every documented recipe
     * authenticates as {@code admin}, so this is not an argument that a
     * non-administrator ought to provision repositories — it is the assertion
     * that a customer's bootstrap job, whatever account it runs as, still gets
     * the behaviour it had before this change.
     */
    @Test
    @DisplayName("a non-admin still creates and updates repositories, as before this change")
    void nonAdminKeepsRepositoryProvisioning() throws Exception {
        var entity = new RepositoryEntity();
        entity.setName(REPO_NAME);
        entity.setType("HOSTED");
        entity.setAttributes(Map.of());
        when(repositoryRepo.findByName(REPO_NAME)).thenReturn(Optional.empty());
        when(blobStoreRepo.existsById("default")).thenReturn(true);
        when(formatRegistry.getSupportedFormats()).thenReturn(java.util.Set.of("maven"));
        when(repositoryRepo.save(any())).thenReturn(entity);

        mockMvc.perform(json(post(REPOSITORIES), CREATE_REPO_BODY).with(reader()))
                .andExpect(status().isCreated());

        when(repositoryRepo.findByName(REPO_NAME)).thenReturn(Optional.of(entity));
        mockMvc.perform(json(put(REPOSITORY), UPDATE_REPO_BODY).with(reader()))
                .andExpect(status().isOk());
    }

    /**
     * The documented publish endpoint. It shares {@code /api/v1/components}
     * with the component delete, so this is the assertion that the delete rule
     * did not take the build farm with it.
     */
    @Test
    @DisplayName("a non-admin still uploads a component")
    void nonAdminKeepsComponentUpload() throws Exception {
        RepositoryConfig hosted = new RepositoryConfig(
                UUID.randomUUID(), REPO_NAME, "maven", RepositoryType.HOSTED, true, "default", Map.of());
        FormatPlugin plugin = mock(FormatPlugin.class);
        ComponentUploadHandler handler = mock(ComponentUploadHandler.class);

        when(repositoryConfigService.getRepository(REPO_NAME)).thenReturn(Optional.of(hosted));
        when(formatRegistry.getPlugin("maven")).thenReturn(plugin);
        when(plugin.getComponentUploadHandler()).thenReturn(Optional.of(handler));
        when(handler.handleUpload(any(), any()))
                .thenReturn(new FormatResponse.CreatedResponse(
                        "com/example/my-lib/1.0.0/my-lib-1.0.0.jar", Map.of()));

        var jar = new MockMultipartFile(
                "asset0", "my-lib-1.0.0.jar", "application/java-archive", "content".getBytes());

        mockMvc.perform(multipart(COMPONENT_UPLOAD)
                        .file(jar)
                        .param("repository", REPO_NAME)
                        .param("groupId", "com.example")
                        .param("artifactId", "my-lib")
                        .param("version", "1.0.0")
                        .with(reader()))
                .andExpect(status().isCreated());

        verify(handler).handleUpload(any(), any());
        verify(auditService).logUpload(any(), anyString(), anyString(), anyString(), anyLong(), any());
    }

    /** Browsing and searching — the read-only role's whole purpose. */
    @Test
    @DisplayName("a non-admin still browses components and assets and searches")
    void nonAdminKeepsBrowseAndSearch() throws Exception {
        var entity = new RepositoryEntity();
        entity.setName(REPO_NAME);
        entity.setType("HOSTED");
        when(repositoryRepo.findByName(REPO_NAME)).thenReturn(Optional.of(entity));
        when(repositoryRepo.findAll()).thenReturn(List.of(entity));
        when(componentRepo.findByRepositoryId(any(), any())).thenReturn(Page.empty());
        when(assetRepo.findByRepositoryId(any(), any())).thenReturn(Page.empty());

        mockMvc.perform(get(COMPONENTS + "?repository=" + REPO_NAME).with(reader()))
                .andExpect(status().isOk());
        mockMvc.perform(get(ASSETS + "?repository=" + REPO_NAME).with(reader()))
                .andExpect(status().isOk());
        mockMvc.perform(get(SEARCH + "?q=my-lib").with(reader())).andExpect(status().isOk());
    }

    private void assertNothingWasDeleted() {
        verify(repositoryRepo, never()).delete(any());
        verify(repositoryRepo, never()).deleteById(any());
        verify(componentService, never()).deleteComponentWithAssets(any());
        verify(assetService, never()).deleteAsset(any());
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder, String body) {
        return builder.contentType(MediaType.APPLICATION_JSON).content(body);
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
        RepositoryController repositoryController(
                RepositoryJpaRepository repositoryRepo,
                BlobStoreJpaRepository blobStoreRepo,
                GroupMemberJpaRepository groupMemberRepo,
                ComponentJpaRepository componentRepo,
                AssetJpaRepository assetRepo,
                FormatRegistry formatRegistry) {
            return new RepositoryController(
                    repositoryRepo, blobStoreRepo, groupMemberRepo, componentRepo, assetRepo, formatRegistry);
        }

        @Bean
        ComponentController componentController(
                ComponentJpaRepository componentRepo,
                AssetJpaRepository assetRepo,
                RepositoryJpaRepository repositoryRepo,
                GroupMemberJpaRepository groupMemberRepo,
                ComponentService componentService) {
            return new ComponentController(
                    componentRepo, assetRepo, repositoryRepo, groupMemberRepo, componentService);
        }

        @Bean
        AssetController assetController(
                AssetJpaRepository assetRepo, RepositoryJpaRepository repositoryRepo, AssetService assetService) {
            return new AssetController(assetRepo, repositoryRepo, assetService);
        }

        @Bean
        UploadController uploadController(
                RepositoryConfigService configService,
                FormatRegistry formatRegistry,
                AuditService auditService,
                ActivityBroadcaster broadcaster) {
            return new UploadController(configService, formatRegistry, auditService, broadcaster);
        }

        @Bean
        SearchController searchController(
                ComponentJpaRepository componentRepo,
                AssetJpaRepository assetRepo,
                RepositoryJpaRepository repositoryRepo) {
            return new SearchController(componentRepo, assetRepo, repositoryRepo);
        }

        @Bean
        RepositoryJpaRepository repositoryJpaRepository() {
            return mock(RepositoryJpaRepository.class);
        }

        @Bean
        BlobStoreJpaRepository blobStoreJpaRepository() {
            return mock(BlobStoreJpaRepository.class);
        }

        @Bean
        ComponentJpaRepository componentJpaRepository() {
            return mock(ComponentJpaRepository.class);
        }

        @Bean
        AssetJpaRepository assetJpaRepository() {
            return mock(AssetJpaRepository.class);
        }

        @Bean
        GroupMemberJpaRepository groupMemberJpaRepository() {
            return mock(GroupMemberJpaRepository.class);
        }

        @Bean
        ComponentService componentService() {
            return mock(ComponentService.class);
        }

        @Bean
        AssetService assetService() {
            return mock(AssetService.class);
        }

        @Bean
        FormatRegistry formatRegistry() {
            return mock(FormatRegistry.class);
        }

        @Bean
        RepositoryConfigService repositoryConfigService() {
            return mock(RepositoryConfigService.class);
        }

        @Bean
        AuditService auditService() {
            return mock(AuditService.class);
        }

        @Bean
        ActivityBroadcaster activityBroadcaster() {
            return mock(ActivityBroadcaster.class);
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

        /** See {@link OperationalAdminAuthorizationTest.TestConfig}. */
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
