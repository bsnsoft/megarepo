package de.bsnsoft.megarepo.rest.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.bsnsoft.megarepo.core.repository.RepositoryConfig;
import de.bsnsoft.megarepo.core.repository.RepositoryConfigService;
import de.bsnsoft.megarepo.core.repository.RepositoryType;
import de.bsnsoft.megarepo.database.repository.AssetJpaRepository;
import de.bsnsoft.megarepo.database.repository.BlobStoreJpaRepository;
import de.bsnsoft.megarepo.database.repository.CleanupPolicyJpaRepository;
import de.bsnsoft.megarepo.database.repository.ComponentJpaRepository;
import de.bsnsoft.megarepo.database.repository.RepositoryJpaRepository;
import de.bsnsoft.megarepo.database.repository.RoutingRuleJpaRepository;
import de.bsnsoft.megarepo.database.repository.ScheduledTaskJpaRepository;
import de.bsnsoft.megarepo.repository.ActivityBroadcaster;
import de.bsnsoft.megarepo.repository.AuditService;
import de.bsnsoft.megarepo.repository.proxy.BlacklistService;
import de.bsnsoft.megarepo.repository.proxy.CacheService;
import de.bsnsoft.megarepo.security.SecurityConfig;
import de.bsnsoft.megarepo.security.auth.AnonymousAccessFilter;
import de.bsnsoft.megarepo.security.auth.JwtAuthenticationFilter;
import de.bsnsoft.megarepo.security.auth.ratelimit.LoginRateLimitFilter;
import de.bsnsoft.megarepo.storage.BlobStoreManager;
import de.bsnsoft.megarepo.tasks.TaskService;
import de.bsnsoft.megarepo.tasks.cleanup.CleanupPolicyEvaluator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who may operate the instance: run its maintenance tasks, own its storage,
 * decide what gets deleted and where traffic goes, and read the record of what
 * everyone did.
 *
 * <p>The third and last of the groups that never named itself in a matcher and
 * so ran on the blanket {@code /api/v1/** -> authenticated()}. It differs from
 * {@link SecurityAdminAuthorizationTest} and {@link SystemAdminAuthorizationTest}
 * in kind: nothing here grants a privilege and nothing here redirects trust —
 * these endpoints destroy things. {@code POST /api/v1/tasks/{id}/run} on the
 * seeded {@code repository.cleanup} task starts deleting artifacts,
 * {@code DELETE /api/v1/blobstores/{name}} drops the storage out from under
 * every asset that lives in it, and {@code POST .../cache/invalidate/pattern}
 * with {@code .*} empties a proxy cache. The read-only {@code nx-viewer} — the
 * default for an LDAP user whose groups map to nothing — could do all three.
 *
 * <p>Like the other authorization tests in this module this drives the
 * <b>real</b> {@link SecurityConfig} bean and its real filter chain. Method
 * security is not enabled in this project, so a {@code @PreAuthorize} would
 * authorize everyone while looking like it did the opposite, and a test that
 * restated the rules locally would only prove the copy agrees with itself.
 *
 * <p>Every verb of every controller behind these eight prefixes is asserted,
 * reads included. The reads are not incidental here: the blob store list
 * carries each store's raw {@code config}, which for an S3 store holds
 * {@code accessKeyId} and {@code secretAccessKey}, and the audit and activity
 * endpoints carry a per-request record of user, IP and artifact path.
 *
 * <p>The verb-precise rules — the three deletes that share a prefix with
 * endpoints an ordinary user must keep — live in
 * {@link ArtifactDeletionAuthorizationTest}, together with the non-regression
 * assertions for what stayed open.
 *
 * <p>The three collaborating filters are constructed with null dependencies on
 * purpose; see {@link FirewallAdminAuthorizationTest} for why each one
 * short-circuits before touching them.
 */
@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = {SecurityConfig.class, OperationalAdminAuthorizationTest.TestConfig.class})
class OperationalAdminAuthorizationTest {

    private static final UUID TASK_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final String TASKS = "/api/v1/tasks";
    private static final String TASK = TASKS + "/" + TASK_ID;
    private static final String TASK_RUN = TASK + "/run";
    private static final String TASK_STOP = TASK + "/stop";

    private static final String BLOB_STORES = "/api/v1/blobstores";
    private static final String BLOB_STORE_FILE = BLOB_STORES + "/file";
    private static final String BLOB_STORE_S3 = BLOB_STORES + "/s3";
    private static final String BLOB_STORE = BLOB_STORES + "/default";

    private static final String CLEANUP_POLICIES = "/api/v1/cleanup-policies";
    private static final String CLEANUP_POLICY = CLEANUP_POLICIES + "/purge-everything";
    private static final String CLEANUP_PREVIEW = CLEANUP_POLICY + "/preview?repository=maven-internal";

    private static final String ROUTING_RULES = "/api/v1/routing-rules";
    private static final String ROUTING_RULE = ROUTING_RULES + "/block-internal";

    private static final String AUDIT = "/api/v1/audit";
    private static final String AUDIT_EXPORT = AUDIT + "/export";

    private static final String ACTIVITY_STREAM = "/api/v1/activity/stream";
    private static final String ACTIVITY_RECENT = "/api/v1/activity/recent";

    private static final String PROXY_REPO = "maven-central";
    private static final String CACHE = "/api/v1/repositories/" + PROXY_REPO + "/cache";
    private static final String CACHE_ASSETS = CACHE + "/assets";
    private static final String CACHE_INVALIDATE = CACHE + "/invalidate";
    private static final String CACHE_INVALIDATE_ASSET = CACHE + "/invalidate/asset";
    private static final String CACHE_INVALIDATE_PATTERN = CACHE + "/invalidate/pattern";

    private static final String BLACKLIST = "/api/v1/repositories/" + PROXY_REPO + "/blacklist";
    private static final String BLACKLIST_CHECK = BLACKLIST + "/check";

    private static final String TASK_BODY =
            """
            {"name":"drop everything","type":"repository.cleanup","cronExpression":"0 0 * * * ?","enabled":true}""";

    private static final String FILE_BLOB_STORE_BODY = """
            {"name":"attacker","path":"/tmp/attacker"}""";

    private static final String S3_BLOB_STORE_BODY =
            """
            {"name":"exfil","bucket":"attacker-bucket","region":"eu-central-1",
             "accessKeyId":"AKIA000000000000","secretAccessKey":"s3cr3t"}""";

    private static final String CLEANUP_POLICY_BODY =
            """
            {"name":"purge-everything","format":"maven","notes":"","criteria":{"lastDownloadedDays":0}}""";

    private static final String ROUTING_RULE_BODY =
            """
            {"name":"block-internal","description":"","mode":"BLOCK","matchers":["^/com/customer/.*"]}""";

    /** The one that empties a whole proxy cache in a single call. */
    private static final String INVALIDATE_EVERYTHING_BODY = """
            {"pattern":".*"}""";

    private static final String INVALIDATE_ASSET_BODY = """
            {"path":"/org/example/lib/1.0/lib-1.0.jar"}""";

    /** Replacing the list with an empty one removes every supply-chain block. */
    private static final String EMPTY_BLACKLIST_BODY = "[]";

    private static final String BLACKLIST_CHECK_BODY = """
            {"path":"/org/example/lib/1.0/lib-1.0.jar"}""";

    @Autowired private WebApplicationContext context;
    @Autowired private ScheduledTaskJpaRepository scheduledTaskRepo;
    @Autowired private TaskService taskService;
    @Autowired private BlobStoreJpaRepository blobStoreRepo;
    @Autowired private BlobStoreManager blobStoreManager;
    @Autowired private CleanupPolicyJpaRepository cleanupPolicyRepo;
    @Autowired private RoutingRuleJpaRepository routingRuleRepo;
    @Autowired private AuditService auditService;
    @Autowired private ActivityBroadcaster activityBroadcaster;
    @Autowired private CacheService cacheService;
    @Autowired private BlacklistService blacklistService;
    @Autowired private RepositoryJpaRepository repositoryRepo;
    @Autowired private RepositoryConfigService repositoryConfigService;
    @Autowired private AssetJpaRepository assetRepo;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        // The mock beans are singletons in a Spring context cached across test
        // methods, so recorded interactions would otherwise carry from one
        // method into the next and make the never() assertions below report a
        // neighbour's calls instead of their own.
        reset(
                scheduledTaskRepo,
                taskService,
                blobStoreRepo,
                blobStoreManager,
                cleanupPolicyRepo,
                routingRuleRepo,
                auditService,
                activityBroadcaster,
                cacheService,
                blacklistService,
                repositoryRepo,
                repositoryConfigService,
                assetRepo);
    }

    @Test
    @DisplayName("anonymous callers get 401 on every operational surface")
    void anonymousIsRejectedEverywhere() throws Exception {
        mockMvc.perform(get(TASKS)).andExpect(status().isUnauthorized());
        mockMvc.perform(post(TASK_RUN)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(BLOB_STORES)).andExpect(status().isUnauthorized());
        mockMvc.perform(delete(BLOB_STORE)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(CLEANUP_POLICIES)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(ROUTING_RULES)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(AUDIT)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(AUDIT_EXPORT)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(ACTIVITY_STREAM)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(ACTIVITY_RECENT)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(CACHE)).andExpect(status().isUnauthorized());
        mockMvc.perform(post(CACHE_INVALIDATE)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(BLACKLIST)).andExpect(status().isUnauthorized());
        mockMvc.perform(json(put(BLACKLIST), EMPTY_BLACKLIST_BODY)).andExpect(status().isUnauthorized());

        assertNothingWasOperated();
    }

    @Test
    @DisplayName("a logged-in non-admin cannot list, schedule, run or stop tasks")
    void nonAdminIsForbiddenFromTasks() throws Exception {
        mockMvc.perform(get(TASKS).with(reader())).andExpect(status().isForbidden());
        mockMvc.perform(get(TASK).with(reader())).andExpect(status().isForbidden());
        mockMvc.perform(json(post(TASKS), TASK_BODY).with(reader())).andExpect(status().isForbidden());
        mockMvc.perform(delete(TASK).with(reader())).andExpect(status().isForbidden());
        // The severe one: triggering an existing task needs no body at all, and
        // the seeded set includes repository.cleanup and blobstore.compact.
        mockMvc.perform(post(TASK_RUN).with(reader())).andExpect(status().isForbidden());
        mockMvc.perform(post(TASK_STOP).with(reader())).andExpect(status().isForbidden());

        verify(taskService, never()).triggerTask(any());
        verify(taskService, never()).stopTask(any());
        verify(scheduledTaskRepo, never()).save(any());
        verify(scheduledTaskRepo, never()).deleteById(any());
        verify(scheduledTaskRepo, never()).findAll();
    }

    @Test
    @DisplayName("a logged-in non-admin cannot read or change blob stores")
    void nonAdminIsForbiddenFromBlobStores() throws Exception {
        // The read matters on its own: BlobStoreXO carries the store's raw
        // config, and an S3 store's config holds the bucket credentials.
        mockMvc.perform(get(BLOB_STORES).with(reader())).andExpect(status().isForbidden());
        mockMvc.perform(json(post(BLOB_STORE_FILE), FILE_BLOB_STORE_BODY).with(reader()))
                .andExpect(status().isForbidden());
        mockMvc.perform(json(post(BLOB_STORE_S3), S3_BLOB_STORE_BODY).with(reader()))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete(BLOB_STORE).with(reader())).andExpect(status().isForbidden());

        verify(blobStoreRepo, never()).findAll();
        verify(blobStoreRepo, never()).save(any());
        verify(blobStoreRepo, never()).deleteById(any());
        verify(blobStoreManager, never()).create(any(), any(), any());
        verify(blobStoreManager, never()).delete(any());
    }

    @Test
    @DisplayName("a logged-in non-admin cannot rewrite a cleanup policy")
    void nonAdminIsForbiddenFromCleanupPolicies() throws Exception {
        mockMvc.perform(get(CLEANUP_POLICIES).with(reader())).andExpect(status().isForbidden());
        mockMvc.perform(get(CLEANUP_POLICY).with(reader())).andExpect(status().isForbidden());
        mockMvc.perform(json(post(CLEANUP_POLICIES), CLEANUP_POLICY_BODY).with(reader()))
                .andExpect(status().isForbidden());
        mockMvc.perform(json(put(CLEANUP_POLICY), CLEANUP_POLICY_BODY).with(reader()))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete(CLEANUP_POLICY).with(reader())).andExpect(status().isForbidden());
        mockMvc.perform(post(CLEANUP_PREVIEW).with(reader())).andExpect(status().isForbidden());

        verify(cleanupPolicyRepo, never()).save(any());
        verify(cleanupPolicyRepo, never()).deleteById(any());
        verify(cleanupPolicyRepo, never()).findAll();
    }

    @Test
    @DisplayName("a logged-in non-admin cannot change routing rules")
    void nonAdminIsForbiddenFromRoutingRules() throws Exception {
        mockMvc.perform(get(ROUTING_RULES).with(reader())).andExpect(status().isForbidden());
        mockMvc.perform(get(ROUTING_RULE).with(reader())).andExpect(status().isForbidden());
        mockMvc.perform(json(post(ROUTING_RULES), ROUTING_RULE_BODY).with(reader()))
                .andExpect(status().isForbidden());
        mockMvc.perform(json(put(ROUTING_RULE), ROUTING_RULE_BODY).with(reader()))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete(ROUTING_RULE).with(reader())).andExpect(status().isForbidden());

        verify(routingRuleRepo, never()).save(any());
        verify(routingRuleRepo, never()).deleteById(any());
        verify(routingRuleRepo, never()).findAll();
    }

    @Test
    @DisplayName("a logged-in non-admin cannot read or export the audit log")
    void nonAdminIsForbiddenFromAuditLog() throws Exception {
        mockMvc.perform(get(AUDIT).with(reader())).andExpect(status().isForbidden());
        mockMvc.perform(get(AUDIT + "?user=admin").with(reader())).andExpect(status().isForbidden());
        // Up to 10,000 rows of userId, IP address and artifact path, as a file.
        mockMvc.perform(get(AUDIT_EXPORT).with(reader())).andExpect(status().isForbidden());
        mockMvc.perform(get(AUDIT_EXPORT + "?format=json").with(reader())).andExpect(status().isForbidden());

        assertAuditLogWasNotRead();
    }

    @Test
    @DisplayName("a logged-in non-admin cannot watch the live activity feed")
    void nonAdminIsForbiddenFromActivityFeed() throws Exception {
        mockMvc.perform(get(ACTIVITY_STREAM).with(reader())).andExpect(status().isForbidden());
        mockMvc.perform(get(ACTIVITY_RECENT).with(reader())).andExpect(status().isForbidden());

        verify(activityBroadcaster, never()).subscribe();
        assertAuditLogWasNotRead();
    }

    @Test
    @DisplayName("a logged-in non-admin cannot inspect or flush a proxy cache")
    void nonAdminIsForbiddenFromProxyCache() throws Exception {
        mockMvc.perform(get(CACHE).with(reader())).andExpect(status().isForbidden());
        mockMvc.perform(get(CACHE_ASSETS).with(reader())).andExpect(status().isForbidden());
        mockMvc.perform(post(CACHE_INVALIDATE).with(reader())).andExpect(status().isForbidden());
        mockMvc.perform(json(post(CACHE_INVALIDATE_ASSET), INVALIDATE_ASSET_BODY).with(reader()))
                .andExpect(status().isForbidden());
        // ".*" matches every cached path — one call, cache gone.
        mockMvc.perform(json(post(CACHE_INVALIDATE_PATTERN), INVALIDATE_EVERYTHING_BODY)
                        .with(reader()))
                .andExpect(status().isForbidden());

        verify(cacheService, never()).invalidateAll(any());
        verify(cacheService, never()).invalidateAsset(any(), anyString());
        verify(cacheService, never()).invalidateByPattern(any(), anyString());
        verify(cacheService, never()).getCacheInfo(any());
    }

    @Test
    @DisplayName("a logged-in non-admin cannot read or replace a repository blacklist")
    void nonAdminIsForbiddenFromBlacklist() throws Exception {
        mockMvc.perform(get(BLACKLIST).with(reader())).andExpect(status().isForbidden());
        // PUT replaces the whole list, so [] removes every configured block.
        mockMvc.perform(json(put(BLACKLIST), EMPTY_BLACKLIST_BODY).with(reader()))
                .andExpect(status().isForbidden());
        mockMvc.perform(json(post(BLACKLIST_CHECK), BLACKLIST_CHECK_BODY).with(reader()))
                .andExpect(status().isForbidden());

        verify(blacklistService, never()).getBlacklistPatterns(any());
        verify(blacklistService, never()).invalidateCache(any());
        verify(repositoryRepo, never()).save(any());
    }

    /**
     * The other half of every assertion above: the rules turn a reader away,
     * and they let an administrator through. Without this the whole class would
     * pass just as well against a chain that denied these paths to everyone,
     * which is the failure mode that would take the tasks page, the blob store
     * page and the audit log away from the customer's administrator.
     */
    @Test
    @DisplayName("nx-admin reaches every operational surface")
    void adminIsAllowed() throws Exception {
        RepositoryConfig proxy = new RepositoryConfig(
                UUID.randomUUID(), PROXY_REPO, "maven", RepositoryType.PROXY, true, "default", Map.of());

        when(scheduledTaskRepo.findAll()).thenReturn(List.of());
        when(blobStoreRepo.findAll()).thenReturn(List.of());
        when(cleanupPolicyRepo.findAll()).thenReturn(List.of());
        when(routingRuleRepo.findAll()).thenReturn(List.of());
        when(auditService.findAll(any())).thenReturn(Page.empty());
        when(repositoryConfigService.getRepository(PROXY_REPO)).thenReturn(Optional.of(proxy));
        when(cacheService.getCacheInfo(any()))
                .thenReturn(new CacheService.CacheInfo(PROXY_REPO, 0, 0, 0, null, null));
        when(cacheService.invalidateAll(any())).thenReturn(0);
        when(blacklistService.getBlacklistPatterns(any())).thenReturn(List.of());

        mockMvc.perform(get(TASKS).with(admin())).andExpect(status().isOk());
        mockMvc.perform(get(BLOB_STORES).with(admin())).andExpect(status().isOk());
        mockMvc.perform(get(CLEANUP_POLICIES).with(admin())).andExpect(status().isOk());
        mockMvc.perform(get(ROUTING_RULES).with(admin())).andExpect(status().isOk());
        mockMvc.perform(get(AUDIT).with(admin())).andExpect(status().isOk());
        mockMvc.perform(get(AUDIT_EXPORT).with(admin())).andExpect(status().isOk());
        mockMvc.perform(get(ACTIVITY_RECENT).with(admin())).andExpect(status().isOk());
        mockMvc.perform(get(CACHE).with(admin())).andExpect(status().isOk());
        mockMvc.perform(post(CACHE_INVALIDATE).with(admin())).andExpect(status().isOk());
        mockMvc.perform(get(BLACKLIST).with(admin())).andExpect(status().isOk());

        // Reaching the handler is the point; these prove the requests were not
        // merely accepted by the chain but actually served.
        verify(cacheService).invalidateAll(any());
        verify(blacklistService).getBlacklistPatterns(any());
    }

    private void assertNothingWasOperated() {
        verify(taskService, never()).triggerTask(any());
        verify(taskService, never()).stopTask(any());
        verify(blobStoreRepo, never()).deleteById(any());
        verify(blobStoreManager, never()).delete(any());
        verify(cacheService, never()).invalidateAll(any());
        verify(repositoryRepo, never()).save(any());
        assertAuditLogWasNotRead();
    }

    /**
     * {@code AuditController} and {@code ActivityController} read through the
     * same service and the same DTO, so one assertion covers both: no row of
     * user, IP and path left the application.
     */
    private void assertAuditLogWasNotRead() {
        verify(auditService, never()).findAll(any());
        verify(auditService, never()).findByUser(anyString(), any());
        verify(auditService, never()).findByRepository(anyString(), any());
        verify(auditService, never()).findByAction(anyString(), any());
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
        TaskController taskController(ScheduledTaskJpaRepository repo, TaskService taskService) {
            return new TaskController(repo, taskService);
        }

        @Bean
        BlobStoreController blobStoreController(
                BlobStoreJpaRepository repo, AssetJpaRepository assetRepo, BlobStoreManager manager) {
            return new BlobStoreController(repo, assetRepo, manager);
        }

        @Bean
        CleanupPolicyController cleanupPolicyController(
                CleanupPolicyJpaRepository cleanupRepo,
                RepositoryJpaRepository repositoryRepo,
                AssetJpaRepository assetRepo,
                ComponentJpaRepository componentRepo,
                CleanupPolicyEvaluator evaluator) {
            return new CleanupPolicyController(cleanupRepo, repositoryRepo, assetRepo, componentRepo, evaluator);
        }

        @Bean
        RoutingRuleController routingRuleController(RoutingRuleJpaRepository repo) {
            return new RoutingRuleController(repo);
        }

        @Bean
        AuditController auditController(AuditService auditService) {
            return new AuditController(auditService, new ObjectMapper());
        }

        @Bean
        ActivityController activityController(ActivityBroadcaster broadcaster, AuditService auditService) {
            return new ActivityController(broadcaster, auditService);
        }

        @Bean
        CacheController cacheController(RepositoryConfigService configService, CacheService cacheService) {
            return new CacheController(configService, cacheService);
        }

        @Bean
        BlacklistController blacklistController(
                RepositoryConfigService configService,
                RepositoryJpaRepository repositoryRepo,
                BlacklistService blacklistService) {
            return new BlacklistController(configService, repositoryRepo, blacklistService);
        }

        @Bean
        ScheduledTaskJpaRepository scheduledTaskJpaRepository() {
            return mock(ScheduledTaskJpaRepository.class);
        }

        @Bean
        TaskService taskService() {
            return mock(TaskService.class);
        }

        @Bean
        BlobStoreJpaRepository blobStoreJpaRepository() {
            return mock(BlobStoreJpaRepository.class);
        }

        @Bean
        BlobStoreManager blobStoreManager() {
            return mock(BlobStoreManager.class);
        }

        @Bean
        CleanupPolicyJpaRepository cleanupPolicyJpaRepository() {
            return mock(CleanupPolicyJpaRepository.class);
        }

        @Bean
        RoutingRuleJpaRepository routingRuleJpaRepository() {
            return mock(RoutingRuleJpaRepository.class);
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
        CacheService cacheService() {
            return mock(CacheService.class);
        }

        @Bean
        BlacklistService blacklistService() {
            return mock(BlacklistService.class);
        }

        @Bean
        RepositoryConfigService repositoryConfigService() {
            return mock(RepositoryConfigService.class);
        }

        @Bean
        RepositoryJpaRepository repositoryJpaRepository() {
            return mock(RepositoryJpaRepository.class);
        }

        @Bean
        AssetJpaRepository assetJpaRepository() {
            return mock(AssetJpaRepository.class);
        }

        @Bean
        ComponentJpaRepository componentJpaRepository() {
            return mock(ComponentJpaRepository.class);
        }

        @Bean
        CleanupPolicyEvaluator cleanupPolicyEvaluator() {
            return mock(CleanupPolicyEvaluator.class);
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
         * Named to match the parameter {@code SecurityConfig.filterChain}
         * declares. Never invoked: no test here authenticates through the
         * provider, they inject an already-authenticated context.
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
