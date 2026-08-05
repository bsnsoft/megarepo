package de.bsnsoft.megarepo.repository;

import de.bsnsoft.megarepo.core.format.FormatPlugin;
import de.bsnsoft.megarepo.core.format.FormatRegistry;
import de.bsnsoft.megarepo.core.format.FormatRequestHandler;
import de.bsnsoft.megarepo.core.format.FormatResponse;
import de.bsnsoft.megarepo.core.repository.RepositoryConfig;
import de.bsnsoft.megarepo.core.repository.RepositoryConfigService;
import de.bsnsoft.megarepo.core.repository.RepositoryType;
import de.bsnsoft.megarepo.core.firewall.FirewallAction;
import de.bsnsoft.megarepo.core.firewall.FirewallFailMode;
import de.bsnsoft.megarepo.core.firewall.FirewallMode;
import de.bsnsoft.megarepo.core.firewall.FirewallRuleType;
import de.bsnsoft.megarepo.repository.firewall.FirewallDecision;
import de.bsnsoft.megarepo.repository.firewall.FirewallDownloadObserver;
import de.bsnsoft.megarepo.repository.firewall.FirewallEnforcementService;
import de.bsnsoft.megarepo.repository.firewall.FirewallEvaluation;
import de.bsnsoft.megarepo.repository.firewall.FirewallRepositorySettings;
import de.bsnsoft.megarepo.repository.firewall.FirewallRequestContext;
import de.bsnsoft.megarepo.repository.firewall.FirewallRuleViolation;
import de.bsnsoft.megarepo.repository.group.GroupHandler;
import de.bsnsoft.megarepo.repository.nvd.NvdFirewallService;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.Mockito;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RepositoryRouterTest {

    @Mock
    private RepositoryConfigService repositoryConfigService;

    @Mock
    private FormatRegistry formatRegistry;

    @Mock
    private FormatPlugin formatPlugin;

    @Mock
    private FormatRequestHandler formatRequestHandler;

    @Mock
    private GroupHandler groupHandler;

    @Mock
    private AuditService auditService;

    @Mock
    private ActivityBroadcaster activityBroadcaster;

    @Mock
    private NvdFirewallService nvdFirewallService;

    @Mock
    private FirewallDownloadObserver firewallDownloadObserver;

    @Mock
    private FirewallEnforcementService firewallEnforcementService;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    private RepositoryRouter router;

    @BeforeEach
    void setUp() {
        Mockito.lenient().when(nvdFirewallService.checkDownload(Mockito.any(), Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
                .thenReturn(NvdFirewallService.CheckResult.allowed());
        // The shipped default: the enforcement master switch is off, so the
        // enforcement path declines to decide and the observation path runs.
        Mockito.lenient().when(firewallEnforcementService.evaluate(
                        Mockito.any(), Mockito.anyString(), Mockito.anyString(), Mockito.any()))
                .thenAnswer(invocation -> notEnforcing(invocation.getArgument(2)));
        router = new RepositoryRouter(repositoryConfigService, formatRegistry, groupHandler, auditService, activityBroadcaster, nvdFirewallService, firewallDownloadObserver, firewallEnforcementService);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void setAuthenticated() {
        var auth = new TestingAuthenticationToken("testuser", "password", "ROLE_USER");
        auth.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test
    void handleGet_repositoryNotFound() throws IOException {
        when(request.getRequestURI()).thenReturn("/repository/missing/some/path");
        when(repositoryConfigService.getRepository("missing")).thenReturn(Optional.empty());

        router.handleGet("missing", request, response);

        verify(response).sendError(eq(404), anyString());
    }

    @Test
    void handleGet_repositoryOffline() throws IOException {
        var repo = new RepositoryConfig(
                UUID.randomUUID(), "my-repo", "maven2", RepositoryType.HOSTED, false, "default", Map.of());
        when(request.getRequestURI()).thenReturn("/repository/my-repo/some/path");
        when(repositoryConfigService.getRepository("my-repo")).thenReturn(Optional.of(repo));

        router.handleGet("my-repo", request, response);

        verify(response).sendError(eq(503), anyString());
    }

    @Test
    void handleGet_hosted_contentResponse() throws IOException {
        var repo = new RepositoryConfig(
                UUID.randomUUID(), "my-repo", "maven2", RepositoryType.HOSTED, true, "default", Map.of());
        byte[] data = "file content".getBytes(StandardCharsets.UTF_8);
        var contentResponse = new FormatResponse.ContentResponse(
                new ByteArrayInputStream(data),
                "application/java-archive",
                data.length,
                Map.of(),
                Map.of("sha1", "abc123", "md5", "def456"));

        when(request.getRequestURI()).thenReturn("/repository/my-repo/com/example/artifact.jar");
        when(request.getMethod()).thenReturn("GET");
        when(repositoryConfigService.getRepository("my-repo")).thenReturn(Optional.of(repo));
        when(formatRegistry.getPlugin("maven2")).thenReturn(formatPlugin);
        when(formatPlugin.getRequestHandler()).thenReturn(formatRequestHandler);
        when(formatRequestHandler.handleHostedGet(eq(repo), eq("com/example/artifact.jar"), eq(request)))
                .thenReturn(contentResponse);

        var outputBuffer = new ByteArrayOutputStream();
        var servletOutputStream = new TestServletOutputStream(outputBuffer);
        when(response.getOutputStream()).thenReturn(servletOutputStream);

        router.handleGet("my-repo", request, response);

        verify(response).setStatus(200);
        verify(response).setContentType("application/java-archive");
        verify(response).setHeader("ETag", "\"abc123\"");
        verify(response).setHeader("X-Checksum-Sha1", "abc123");
        verify(response).setHeader("X-Checksum-Md5", "def456");
        assertArrayEquals(data, outputBuffer.toByteArray());
    }

    @Test
    void handleGet_contentResponse_notifiesFirewallObserverAfterServing() throws IOException {
        var repo = new RepositoryConfig(
                UUID.randomUUID(), "my-repo", "maven2", RepositoryType.HOSTED, true, "default", Map.of());
        byte[] data = "file content".getBytes(StandardCharsets.UTF_8);
        givenServedArtifact(repo, data, new ByteArrayOutputStream());

        router.handleGet("my-repo", request, response);

        var captor = ArgumentCaptor.forClass(FirewallRequestContext.class);
        verify(firewallDownloadObserver)
                .observeDownload(eq(repo.id()), eq("my-repo"), eq("com/example/artifact.jar"), captor.capture());
        assertEquals("com/example/artifact.jar", captor.getValue().path());
        assertEquals("GET", captor.getValue().method());
    }

    /**
     * The Phase 1 promise, as a test: AUDIT records and serves anyway, and a
     * firewall that is broken outright still cannot cost a client its artifact.
     * The hook sits behind the completed response, so the bytes are already out
     * by the time it runs — this asserts that the exception does not undo them.
     */
    @Test
    void handleGet_firewallObserverThrows_downloadIsStillComplete() throws IOException {
        var repo = new RepositoryConfig(
                UUID.randomUUID(), "my-repo", "maven2", RepositoryType.HOSTED, true, "default", Map.of());
        byte[] data = "file content".getBytes(StandardCharsets.UTF_8);
        var outputBuffer = new ByteArrayOutputStream();
        givenServedArtifact(repo, data, outputBuffer);
        Mockito.doThrow(new RuntimeException("firewall is broken"))
                .when(firewallDownloadObserver)
                .observeDownload(Mockito.any(), Mockito.anyString(), Mockito.anyString(), Mockito.any());

        assertDoesNotThrow(() -> router.handleGet("my-repo", request, response));

        verify(response).setStatus(200);
        assertArrayEquals(data, outputBuffer.toByteArray());
    }

    @Test
    void handleGet_nvdFirewallBlocks_noFirewallObservation() throws IOException {
        var repo = new RepositoryConfig(
                UUID.randomUUID(), "my-repo", "maven2", RepositoryType.HOSTED, true, "default", Map.of());
        byte[] data = "file content".getBytes(StandardCharsets.UTF_8);
        var contentResponse = new FormatResponse.ContentResponse(
                new ByteArrayInputStream(data), "application/java-archive", data.length, Map.of(), Map.of());

        when(request.getRequestURI()).thenReturn("/repository/my-repo/com/example/artifact.jar");
        when(repositoryConfigService.getRepository("my-repo")).thenReturn(Optional.of(repo));
        when(formatRegistry.getPlugin("maven2")).thenReturn(formatPlugin);
        when(formatPlugin.getRequestHandler()).thenReturn(formatRequestHandler);
        when(formatRequestHandler.handleHostedGet(eq(repo), eq("com/example/artifact.jar"), eq(request)))
                .thenReturn(contentResponse);
        when(response.getWriter()).thenReturn(new java.io.PrintWriter(new java.io.StringWriter()));
        when(nvdFirewallService.checkDownload(Mockito.any(), Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
                .thenReturn(new NvdFirewallService.CheckResult(true, 10.0, java.util.List.of()));

        router.handleGet("my-repo", request, response);

        verify(response).setStatus(403);
        verify(firewallDownloadObserver, never())
                .observeDownload(Mockito.any(), Mockito.anyString(), Mockito.anyString(), Mockito.any());
    }

    /**
     * The switch, from the router's side: when enforcement says "blocked", the
     * bytes must not reach the client and the response has to explain itself.
     */
    @Test
    void handleGet_firewallEnforcementBlocks_403WithReadableBodyAndNoContent() throws IOException {
        var repo = new RepositoryConfig(
                UUID.randomUUID(), "my-repo", "maven2", RepositoryType.HOSTED, true, "default", Map.of());
        byte[] data = "file content".getBytes(StandardCharsets.UTF_8);
        var outputBuffer = new ByteArrayOutputStream();
        givenRequestedArtifact(repo, data);

        var bodyWriter = new java.io.StringWriter();
        when(response.getWriter()).thenReturn(new java.io.PrintWriter(bodyWriter));
        when(firewallEnforcementService.evaluate(
                        Mockito.any(), Mockito.anyString(), Mockito.anyString(), Mockito.any()))
                .thenReturn(blockedBy(
                        "com/example/artifact.jar",
                        new FirewallRuleViolation(
                                FirewallRuleType.CVSS_THRESHOLD,
                                FirewallAction.BLOCK,
                                "CVSS 10 is at or above the configured threshold of 9",
                                java.util.List.of("CVE-2021-44228", "GHSA-jfh8-c2jp-5v3q"))));

        router.handleGet("my-repo", request, response);

        verify(response).setStatus(403);
        assertEquals(0, outputBuffer.size(), "a blocked download must deliver no bytes");

        String body = bodyWriter.toString();
        assertTrue(body.contains("blocked"), body);
        assertTrue(body.contains("CVSS_THRESHOLD"), body);
        assertTrue(body.contains("CVE-2021-44228"), body);
        assertTrue(body.contains("GHSA-jfh8-c2jp-5v3q"), body);
        assertTrue(body.contains("my-repo"), body);
        verify(firewallDownloadObserver, never())
                .observeDownload(Mockito.any(), Mockito.anyString(), Mockito.anyString(), Mockito.any());
    }

    /**
     * Enforcement that took a decision has already looked the component up and
     * recorded a richer row; running the observation hook as well would double
     * every violation for an enforcing repository.
     */
    @Test
    void handleGet_firewallEnforcementAllowed_observationHookDoesNotRunTwice() throws IOException {
        var repo = new RepositoryConfig(
                UUID.randomUUID(), "my-repo", "maven2", RepositoryType.HOSTED, true, "default", Map.of());
        byte[] data = "file content".getBytes(StandardCharsets.UTF_8);
        var outputBuffer = new ByteArrayOutputStream();
        givenServedArtifact(repo, data, outputBuffer);
        when(firewallEnforcementService.evaluate(
                        Mockito.any(), Mockito.anyString(), Mockito.anyString(), Mockito.any()))
                .thenReturn(new FirewallEvaluation(
                        repo.id(), "my-repo", "com/example/artifact.jar",
                        new FirewallRepositorySettings(
                                FirewallMode.QUARANTINE, FirewallFailMode.FAIL_OPEN, null, true),
                        null, java.util.List.of(), FirewallEvaluation.Outcome.CLEAN,
                        false, FirewallDecision.allowed()));

        router.handleGet("my-repo", request, response);

        verify(response).setStatus(200);
        assertArrayEquals(data, outputBuffer.toByteArray());
        verify(firewallDownloadObserver, never())
                .observeDownload(Mockito.any(), Mockito.anyString(), Mockito.anyString(), Mockito.any());
    }

    /**
     * A firewall that throws outright is a firewall problem, never the client's.
     * Unlike the observation hook this one runs <em>before</em> the response, so
     * "the bytes were already out" is not the reason it holds — the router
     * catching and serving is.
     */
    @Test
    void handleGet_firewallEnforcementThrows_downloadIsStillServed() throws IOException {
        var repo = new RepositoryConfig(
                UUID.randomUUID(), "my-repo", "maven2", RepositoryType.HOSTED, true, "default", Map.of());
        byte[] data = "file content".getBytes(StandardCharsets.UTF_8);
        var outputBuffer = new ByteArrayOutputStream();
        givenServedArtifact(repo, data, outputBuffer);
        when(firewallEnforcementService.evaluate(
                        Mockito.any(), Mockito.anyString(), Mockito.anyString(), Mockito.any()))
                .thenThrow(new RuntimeException("firewall is broken"));

        assertDoesNotThrow(() -> router.handleGet("my-repo", request, response));

        verify(response).setStatus(200);
        assertArrayEquals(data, outputBuffer.toByteArray());
    }

    private static FirewallEvaluation notEnforcing(String path) {
        return new FirewallEvaluation(
                UUID.randomUUID(), "my-repo", path,
                FirewallRepositorySettings.fallback(FirewallMode.OFF),
                null, java.util.List.of(), FirewallEvaluation.Outcome.NOT_ENFORCING);
    }

    private static FirewallEvaluation blockedBy(String path, FirewallRuleViolation violation) {
        return new FirewallEvaluation(
                UUID.randomUUID(), "my-repo", path,
                new FirewallRepositorySettings(
                        FirewallMode.QUARANTINE, FirewallFailMode.FAIL_OPEN, null, true),
                null, java.util.List.of(), FirewallEvaluation.Outcome.MATCHED, false,
                FirewallDecision.blocked(UUID.randomUUID(), "Default", java.util.List.of(violation)));
    }

    private void givenServedArtifact(RepositoryConfig repo, byte[] data, ByteArrayOutputStream sink)
            throws IOException {
        givenRequestedArtifact(repo, data);
        when(response.getOutputStream()).thenReturn(new TestServletOutputStream(sink));
    }

    /**
     * Everything up to the point where the router decides what to do with the
     * content — without stubbing the output stream, which a blocked download
     * never reaches for.
     */
    private void givenRequestedArtifact(RepositoryConfig repo, byte[] data) {
        var contentResponse = new FormatResponse.ContentResponse(
                new ByteArrayInputStream(data),
                "application/java-archive",
                data.length,
                Map.of(),
                Map.of());
        when(request.getRequestURI()).thenReturn("/repository/my-repo/com/example/artifact.jar");
        when(request.getMethod()).thenReturn("GET");
        when(repositoryConfigService.getRepository(repo.name())).thenReturn(Optional.of(repo));
        when(formatRegistry.getPlugin("maven2")).thenReturn(formatPlugin);
        when(formatPlugin.getRequestHandler()).thenReturn(formatRequestHandler);
        when(formatRequestHandler.handleHostedGet(eq(repo), eq("com/example/artifact.jar"), eq(request)))
                .thenReturn(contentResponse);
    }

    @Test
    void handleGet_head_noBody() throws IOException {
        var repo = new RepositoryConfig(
                UUID.randomUUID(), "my-repo", "maven2", RepositoryType.HOSTED, true, "default", Map.of());
        byte[] data = "file content".getBytes(StandardCharsets.UTF_8);
        var contentResponse = new FormatResponse.ContentResponse(
                new ByteArrayInputStream(data),
                "application/java-archive",
                data.length,
                Map.of(),
                Map.of("sha1", "abc123"));

        when(request.getRequestURI()).thenReturn("/repository/my-repo/artifact.jar");
        when(request.getMethod()).thenReturn("HEAD");
        when(repositoryConfigService.getRepository("my-repo")).thenReturn(Optional.of(repo));
        when(formatRegistry.getPlugin("maven2")).thenReturn(formatPlugin);
        when(formatPlugin.getRequestHandler()).thenReturn(formatRequestHandler);
        when(formatRequestHandler.handleHostedGet(eq(repo), eq("artifact.jar"), eq(request)))
                .thenReturn(contentResponse);

        router.handleGet("my-repo", request, response);

        verify(response).setStatus(200);
        verify(response).setContentType("application/java-archive");
        // Should NOT call getOutputStream for HEAD
        verify(response, never()).getOutputStream();
    }

    @Test
    void handlePut_nonHosted_rejected() throws IOException {
        setAuthenticated();
        var repo = new RepositoryConfig(
                UUID.randomUUID(), "proxy-repo", "maven2", RepositoryType.PROXY, true, "default", Map.of());
        when(request.getRequestURI()).thenReturn("/repository/proxy-repo/some/path");
        when(repositoryConfigService.getRepository("proxy-repo")).thenReturn(Optional.of(repo));

        router.handlePut("proxy-repo", request, response);

        verify(response).sendError(eq(400), anyString());
    }

    @Test
    void handleDelete_nonHosted_rejected() throws IOException {
        setAuthenticated();
        var repo = new RepositoryConfig(
                UUID.randomUUID(), "group-repo", "maven2", RepositoryType.GROUP, true, "default", Map.of());
        when(request.getRequestURI()).thenReturn("/repository/group-repo/some/path");
        when(repositoryConfigService.getRepository("group-repo")).thenReturn(Optional.of(repo));

        router.handleDelete("group-repo", request, response);

        verify(response).sendError(eq(400), anyString());
    }

    @Test
    void handleGet_notFoundResponse() throws IOException {
        var repo = new RepositoryConfig(
                UUID.randomUUID(), "my-repo", "maven2", RepositoryType.HOSTED, true, "default", Map.of());
        var notFound = new FormatResponse.NotFoundResponse("Asset not found");

        when(request.getRequestURI()).thenReturn("/repository/my-repo/missing/path");
        when(repositoryConfigService.getRepository("my-repo")).thenReturn(Optional.of(repo));
        when(formatRegistry.getPlugin("maven2")).thenReturn(formatPlugin);
        when(formatPlugin.getRequestHandler()).thenReturn(formatRequestHandler);
        when(formatRequestHandler.handleHostedGet(eq(repo), eq("missing/path"), eq(request)))
                .thenReturn(notFound);

        router.handleGet("my-repo", request, response);

        verify(response).sendError(eq(404), eq("Asset not found"));
    }

    @Test
    void handleGet_redirectResponse() throws IOException {
        var repo = new RepositoryConfig(
                UUID.randomUUID(), "my-repo", "maven2", RepositoryType.PROXY, true, "default", Map.of());
        var redirect = new FormatResponse.RedirectResponse("https://repo1.maven.org/maven2/some/path");

        when(request.getRequestURI()).thenReturn("/repository/my-repo/some/path");
        when(repositoryConfigService.getRepository("my-repo")).thenReturn(Optional.of(repo));
        when(formatRegistry.getPlugin("maven2")).thenReturn(formatPlugin);
        when(formatPlugin.getRequestHandler()).thenReturn(formatRequestHandler);
        when(formatRequestHandler.handleProxyGet(eq(repo), eq("some/path"), eq(request)))
                .thenReturn(redirect);

        router.handleGet("my-repo", request, response);

        verify(response).sendRedirect("https://repo1.maven.org/maven2/some/path");
    }

    @Test
    void handleGet_createdResponse() throws IOException {
        var repo = new RepositoryConfig(
                UUID.randomUUID(), "my-repo", "maven2", RepositoryType.HOSTED, true, "default", Map.of());
        var created = new FormatResponse.CreatedResponse(
                "/repository/my-repo/new/path", Map.of("X-Custom", "value"));

        when(request.getRequestURI()).thenReturn("/repository/my-repo/new/path");
        when(repositoryConfigService.getRepository("my-repo")).thenReturn(Optional.of(repo));
        when(formatRegistry.getPlugin("maven2")).thenReturn(formatPlugin);
        when(formatPlugin.getRequestHandler()).thenReturn(formatRequestHandler);
        when(formatRequestHandler.handleHostedGet(eq(repo), eq("new/path"), eq(request)))
                .thenReturn(created);

        router.handleGet("my-repo", request, response);

        verify(response).setStatus(201);
        verify(response).setHeader("Location", "/repository/my-repo/new/path");
        verify(response).setHeader("X-Custom", "value");
    }

    @Test
    void handleGet_groupRepo_delegatesToGroupHandler() throws IOException {
        var repo = new RepositoryConfig(
                UUID.randomUUID(), "group-repo", "maven2", RepositoryType.GROUP, true, "default", Map.of());
        var notFound = new FormatResponse.NotFoundResponse("Not found in group");

        when(request.getRequestURI()).thenReturn("/repository/group-repo/some/path");
        when(repositoryConfigService.getRepository("group-repo")).thenReturn(Optional.of(repo));
        when(groupHandler.handleGet(eq(repo), eq("some/path"), eq(request)))
                .thenReturn(notFound);

        router.handleGet("group-repo", request, response);

        verify(groupHandler).handleGet(repo, "some/path", request);
    }

    @Test
    void pathTraversal_dotDotSegment_isDetected() {
        assertTrue(RepositoryRouter.containsPathTraversal("../etc/passwd"));
        assertTrue(RepositoryRouter.containsPathTraversal("foo/../bar"));
        assertTrue(RepositoryRouter.containsPathTraversal("foo/.."));
        assertTrue(RepositoryRouter.containsPathTraversal(".."));
    }

    @Test
    void pathTraversal_normalPaths_areAllowed() {
        assertFalse(RepositoryRouter.containsPathTraversal("com/example/artifact-1.0.jar"));
        assertFalse(RepositoryRouter.containsPathTraversal("org/apache/commons/commons-lang3/3.12.0/commons-lang3-3.12.0.pom"));
        assertFalse(RepositoryRouter.containsPathTraversal("@scope/package/-/package-1.0.0.tgz"));
        assertFalse(RepositoryRouter.containsPathTraversal(""));
        assertFalse(RepositoryRouter.containsPathTraversal(null));
        // "..." is not ".." and is a valid filename
        assertFalse(RepositoryRouter.containsPathTraversal("foo/.../bar"));
    }

    @Test
    void handleGet_pathTraversal_returns400() throws IOException {
        when(request.getRequestURI()).thenReturn("/repository/my-repo/../../../etc/passwd");

        router.handleGet("my-repo", request, response);

        verify(response).sendError(eq(400), anyString());
    }

    @Test
    void handlePut_pathTraversal_returns400() throws IOException {
        setAuthenticated();
        when(request.getRequestURI()).thenReturn("/repository/my-repo/../../etc/shadow");

        router.handlePut("my-repo", request, response);

        verify(response).sendError(eq(400), anyString());
    }

    @Test
    void handleDelete_pathTraversal_returns400() throws IOException {
        setAuthenticated();
        when(request.getRequestURI()).thenReturn("/repository/my-repo/foo/../../../etc/hosts");

        router.handleDelete("my-repo", request, response);

        verify(response).sendError(eq(400), anyString());
    }

    private static class TestServletOutputStream extends ServletOutputStream {
        private final ByteArrayOutputStream delegate;

        TestServletOutputStream(ByteArrayOutputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public void write(int b) throws IOException {
            delegate.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            delegate.write(b, off, len);
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setWriteListener(WriteListener writeListener) {}
    }
}
