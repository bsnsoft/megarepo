package de.bsnsoft.megarepo.repository;

import de.bsnsoft.megarepo.core.format.FormatPlugin;
import de.bsnsoft.megarepo.core.format.FormatRegistry;
import de.bsnsoft.megarepo.core.format.FormatRequestHandler;
import de.bsnsoft.megarepo.core.format.FormatResponse;
import de.bsnsoft.megarepo.core.repository.RepositoryConfig;
import de.bsnsoft.megarepo.core.repository.RepositoryConfigService;
import de.bsnsoft.megarepo.core.repository.RepositoryType;
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
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    private RepositoryRouter router;

    @BeforeEach
    void setUp() {
        Mockito.lenient().when(nvdFirewallService.checkDownload(Mockito.any(), Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
                .thenReturn(NvdFirewallService.CheckResult.allowed());
        router = new RepositoryRouter(repositoryConfigService, formatRegistry, groupHandler, auditService, activityBroadcaster, nvdFirewallService);
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
