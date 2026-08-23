package de.bsnsoft.megarepo.repository;

import de.bsnsoft.megarepo.core.format.FormatPlugin;
import de.bsnsoft.megarepo.core.format.FormatRegistry;
import de.bsnsoft.megarepo.core.format.FormatRequestHandler;
import de.bsnsoft.megarepo.core.format.FormatResponse;
import de.bsnsoft.megarepo.core.format.FormatResponse.ContentResponse;
import de.bsnsoft.megarepo.core.format.FormatResponse.CreatedResponse;
import de.bsnsoft.megarepo.core.format.FormatResponse.ErrorResponse;
import de.bsnsoft.megarepo.core.format.FormatResponse.ErrorResponseWithHeaders;
import de.bsnsoft.megarepo.core.format.FormatResponse.NotFoundResponse;
import de.bsnsoft.megarepo.core.format.FormatResponse.RedirectResponse;
import de.bsnsoft.megarepo.core.repository.RepositoryConfig;
import de.bsnsoft.megarepo.core.repository.RepositoryConfigService;
import de.bsnsoft.megarepo.core.repository.RepositoryType;
import de.bsnsoft.megarepo.repository.group.GroupHandler;
import de.bsnsoft.megarepo.repository.nvd.NvdFirewallService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.OutputStream;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.springframework.web.bind.annotation.RequestMethod.DELETE;
import static org.springframework.web.bind.annotation.RequestMethod.GET;
import static org.springframework.web.bind.annotation.RequestMethod.HEAD;
import static org.springframework.web.bind.annotation.RequestMethod.PATCH;
import static org.springframework.web.bind.annotation.RequestMethod.POST;
import static org.springframework.web.bind.annotation.RequestMethod.PUT;

@RestController
public class RepositoryRouter {

    private static final Logger log = LoggerFactory.getLogger(RepositoryRouter.class);

    private final RepositoryConfigService repositoryConfigService;
    private final FormatRegistry formatRegistry;
    private final GroupHandler groupHandler;
    private final AuditService auditService;
    private final ActivityBroadcaster activityBroadcaster;
    private final NvdFirewallService nvdFirewallService;

    public RepositoryRouter(
            RepositoryConfigService repositoryConfigService,
            FormatRegistry formatRegistry,
            GroupHandler groupHandler,
            AuditService auditService,
            ActivityBroadcaster activityBroadcaster,
            NvdFirewallService nvdFirewallService) {
        this.repositoryConfigService = repositoryConfigService;
        this.formatRegistry = formatRegistry;
        this.groupHandler = groupHandler;
        this.auditService = auditService;
        this.activityBroadcaster = activityBroadcaster;
        this.nvdFirewallService = nvdFirewallService;
    }

    @RequestMapping(
            value = "/repository/{repoName}/**",
            method = {GET, HEAD})
    public void handleGet(
            @PathVariable String repoName,
            HttpServletRequest request,
            HttpServletResponse response)
            throws IOException {
        long startTime = System.currentTimeMillis();
        String path = extractPath(request, repoName);
        if (path == null) {
            sendError(response, HttpServletResponse.SC_BAD_REQUEST, "Invalid path: path traversal not allowed");
            return;
        }
        Optional<RepositoryConfig> maybeRepo = repositoryConfigService.getRepository(repoName);
        if (maybeRepo.isEmpty()) {
            sendError(response, HttpServletResponse.SC_NOT_FOUND, "Repository not found: " + repoName);
            return;
        }
        RepositoryConfig repo = maybeRepo.get();
        if (!repo.online()) {
            sendError(
                    response,
                    HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "Repository '%s' is offline".formatted(repoName));
            return;
        }

        FormatResponse formatResponse;
        if (repo.type() == RepositoryType.GROUP) {
            formatResponse = groupHandler.handleGet(repo, path, request);
        } else {
            FormatPlugin plugin = formatRegistry.getPlugin(repo.format());
            FormatRequestHandler handler = plugin.getRequestHandler();
            formatResponse =
                    switch (repo.type()) {
                        case HOSTED -> handler.handleHostedGet(repo, path, request);
                        case PROXY -> handler.handleProxyGet(repo, path, request);
                        case GROUP -> throw new IllegalStateException("Unreachable");
                    };
        }

        if (formatResponse instanceof ContentResponse content) {
            String currentUser = currentUser(request);
            var nvdResult = nvdFirewallService.checkDownload(repo.id(), path, repoName, currentUser);
            if (nvdResult.blocked()) {
                try (var ignored = content.content()) {} catch (Exception e) { /* close unused stream */ }
                writeNvdBlockResponse(response, nvdResult);
                return;
            }
        }

        writeResponse(formatResponse, request, response);

        if (formatResponse instanceof ContentResponse content) {
            long durationMs = System.currentTimeMillis() - startTime;
            String user = currentUser(request);
            auditService.logDownload(user, repoName, path, repo.format(), content.contentLength(), clientIp(request), durationMs);
            activityBroadcaster.broadcast(new ActivityEvent(
                    Instant.now(), user, "DOWNLOAD", repoName, path, repo.format(), content.contentLength(), durationMs, null));
        }
    }

    @RequestMapping(
            value = {"/repository/{repoName}/**", "/repository/{repoName}"},
            method = PUT)
    public void handlePut(
            @PathVariable String repoName,
            HttpServletRequest request,
            HttpServletResponse response)
            throws IOException {
        if (!isAuthenticated()) {
            sendError(response, HttpServletResponse.SC_UNAUTHORIZED, "Authentication required for write operations");
            return;
        }
        String path = extractPath(request, repoName);
        if (path == null) {
            sendError(response, HttpServletResponse.SC_BAD_REQUEST, "Invalid path: path traversal not allowed");
            return;
        }
        Optional<RepositoryConfig> maybeRepo = repositoryConfigService.getRepository(repoName);
        if (maybeRepo.isEmpty()) {
            sendError(response, HttpServletResponse.SC_NOT_FOUND, "Repository not found: " + repoName);
            return;
        }
        RepositoryConfig repo = maybeRepo.get();
        if (!repo.online()) {
            sendError(
                    response,
                    HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "Repository '%s' is offline".formatted(repoName));
            return;
        }
        if (repo.type() == RepositoryType.PROXY) {
            sendError(
                    response,
                    HttpServletResponse.SC_BAD_REQUEST,
                    "PUT is not supported for proxy repositories");
            return;
        }

        FormatResponse formatResponse;
        if (repo.type() == RepositoryType.GROUP) {
            formatResponse = groupHandler.handlePut(repo, path, request);
        } else {
            FormatPlugin plugin = formatRegistry.getPlugin(repo.format());
            FormatRequestHandler handler = plugin.getRequestHandler();
            formatResponse = handler.handleHostedPut(repo, path, request);
        }
        writeResponse(formatResponse, request, response);

        if (formatResponse instanceof CreatedResponse) {
            long size = Math.max(request.getContentLengthLong(), 0);
            String user = currentUser(request);
            auditService.logUpload(user, repoName, path, repo.format(), size, clientIp(request));
            activityBroadcaster.broadcast(new ActivityEvent(
                    Instant.now(), user, "UPLOAD", repoName, path, repo.format(), size, 0L, null));
        }
    }

    @RequestMapping(value = "/repository/{repoName}/**", method = DELETE)
    public void handleDelete(
            @PathVariable String repoName,
            HttpServletRequest request,
            HttpServletResponse response)
            throws IOException {
        if (!isAuthenticated()) {
            sendError(response, HttpServletResponse.SC_UNAUTHORIZED, "Authentication required for write operations");
            return;
        }
        String path = extractPath(request, repoName);
        if (path == null) {
            sendError(response, HttpServletResponse.SC_BAD_REQUEST, "Invalid path: path traversal not allowed");
            return;
        }
        Optional<RepositoryConfig> maybeRepo = repositoryConfigService.getRepository(repoName);
        if (maybeRepo.isEmpty()) {
            sendError(response, HttpServletResponse.SC_NOT_FOUND, "Repository not found: " + repoName);
            return;
        }
        RepositoryConfig repo = maybeRepo.get();
        if (!repo.online()) {
            sendError(
                    response,
                    HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "Repository '%s' is offline".formatted(repoName));
            return;
        }
        if (repo.type() != RepositoryType.HOSTED) {
            sendError(
                    response,
                    HttpServletResponse.SC_BAD_REQUEST,
                    "DELETE is only supported for hosted repositories");
            return;
        }

        FormatPlugin plugin = formatRegistry.getPlugin(repo.format());
        FormatRequestHandler handler = plugin.getRequestHandler();
        FormatResponse formatResponse = handler.handleHostedDelete(repo, path, request);
        writeResponse(formatResponse, request, response);

        if (!(formatResponse instanceof ErrorResponse) && !(formatResponse instanceof NotFoundResponse)) {
            String user = currentUser(request);
            auditService.logDelete(user, repoName, path, repo.format(), clientIp(request));
            activityBroadcaster.broadcast(new ActivityEvent(
                    Instant.now(), user, "DELETE", repoName, path, repo.format(), null, 0L, null));
        }
    }

    @RequestMapping(
            value = {"/repository/{repoName}/**", "/repository/{repoName}"},
            method = POST)
    public void handlePost(
            @PathVariable String repoName,
            HttpServletRequest request,
            HttpServletResponse response)
            throws IOException {
        // POST is used by PyPI twine (multipart upload) and Docker Registry V2 API
        // (blob upload initiation). Route through the same path as PUT — the format
        // handler differentiates.
        handlePut(repoName, request, response);
    }

    @RequestMapping(
            value = {"/repository/{repoName}/**", "/repository/{repoName}"},
            method = PATCH)
    public void handlePatch(
            @PathVariable String repoName,
            HttpServletRequest request,
            HttpServletResponse response)
            throws IOException {
        // PATCH is used by Docker Registry V2 API for chunked blob uploads.
        // Route through the same path as PUT — the format handler differentiates.
        handlePut(repoName, request, response);
    }

    /**
     * Extracts the artifact path from the request URI.
     * Returns null if the path contains directory traversal sequences.
     * Decodes %2F in the path for npm scoped packages (@scope%2Fname),
     * since getRequestURI() returns the original encoded form.
     */
    private String extractPath(HttpServletRequest request, String repoName) {
        String requestUri = request.getRequestURI();
        String prefix = "/repository/" + repoName + "/";
        int index = requestUri.indexOf(prefix);
        if (index == -1) {
            return "";
        }
        String path = requestUri.substring(index + prefix.length());
        // Decode %2F/%2f to / for npm scoped packages (e.g. @scope%2Fpackage -> @scope/package)
        path = path.replace("%2f", "/").replace("%2F", "/");
        // Remove trailing slash if present
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        // Path traversal protection: reject paths containing ".." segments
        if (containsPathTraversal(path)) {
            return null;
        }
        return path;
    }

    /**
     * Checks if a path contains directory traversal sequences.
     * Rejects ".." as a segment (e.g., "../foo", "foo/../bar", "foo/..").
     * Also rejects URL-encoded variants (%2e%2e, %2E%2E) and null bytes as defense-in-depth.
     */
    static boolean containsPathTraversal(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        // Reject null bytes which could be used to terminate strings in native code
        if (path.indexOf('\0') >= 0) {
            return true;
        }
        // Check both the raw path and a URL-decoded version for ".." segments
        // to catch double-encoding attacks (%252e%252e -> %2e%2e -> ..)
        if (containsDotDotSegment(path)) {
            return true;
        }
        try {
            String decoded = java.net.URLDecoder.decode(path, java.nio.charset.StandardCharsets.UTF_8);
            if (!decoded.equals(path) && containsDotDotSegment(decoded)) {
                return true;
            }
        } catch (IllegalArgumentException e) {
            // Malformed URL encoding — reject as suspicious
            return true;
        }
        return false;
    }

    private static boolean containsDotDotSegment(String path) {
        for (String segment : path.split("/")) {
            if ("..".equals(segment)) {
                return true;
            }
        }
        return false;
    }

    private void writeResponse(
            FormatResponse formatResponse, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        switch (formatResponse) {
            case ContentResponse content -> writeContentResponse(content, request, response);
            case NotFoundResponse notFound -> sendError(
                    response, HttpServletResponse.SC_NOT_FOUND, notFound.message());
            case ErrorResponse error -> sendError(response, error.statusCode(), error.message());
            case ErrorResponseWithHeaders errorWithHeaders -> writeErrorWithHeaders(errorWithHeaders, response);
            case CreatedResponse created -> writeCreatedResponse(created, response);
            case RedirectResponse redirect -> {
                String location = redirect.location();
                // Prevent unsafe redirects: block javascript:, data:, and protocol-relative URLs
                if (location == null || location.startsWith("javascript:") || location.startsWith("data:")
                        || location.startsWith("//")) {
                    sendError(response, HttpServletResponse.SC_BAD_REQUEST, "Invalid redirect location");
                } else {
                    response.sendRedirect(location);
                }
            }
        }
    }

    /**
     * Reports whether a response body is worth compressing on the wire — i.e. whether it is
     * one of the text-ish media types the servlet container will gzip. Mirrors the default
     * {@code server.compression.mime-types} set.
     */
    static boolean isCompressibleContentType(String contentType) {
        if (contentType == null) {
            return false;
        }
        String type = contentType.toLowerCase(java.util.Locale.ROOT);
        int separator = type.indexOf(';');
        if (separator >= 0) {
            type = type.substring(0, separator);
        }
        type = type.trim();
        return type.startsWith("text/")
                || type.equals("application/json")
                || type.endsWith("+json")
                || type.equals("application/xml")
                || type.endsWith("+xml")
                || type.equals("application/javascript");
    }

    private void writeContentResponse(
            ContentResponse content, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);

        if (content.contentType() != null) {
            response.setContentType(content.contentType());
        }
        if (content.contentLength() >= 0) {
            response.setContentLengthLong(content.contentLength());
        }

        // Set checksum headers
        Map<String, String> checksums = content.checksums();
        if (checksums != null) {
            if (checksums.containsKey("sha1")) {
                // Tomcat refuses to gzip a response that carries a *strong* ETag, because
                // compressing would change the byte-for-byte representation the strong
                // validator promises. Text metadata (npm packuments, PyPI indexes, POMs)
                // is exactly what benefits most from compression, so those get a weak
                // validator instead — still usable for If-None-Match revalidation, but no
                // longer a barrier to transfer encoding. Binary artifacts keep the strong
                // ETag; they are already compressed and are not in Tomcat's
                // compressible-mime-type list anyway. (GitHub #1)
                String etag = "\"%s\"".formatted(checksums.get("sha1"));
                response.setHeader("ETag", isCompressibleContentType(content.contentType()) ? "W/" + etag : etag);
                response.setHeader("X-Checksum-Sha1", checksums.get("sha1"));
            }
            if (checksums.containsKey("md5")) {
                response.setHeader("X-Checksum-Md5", checksums.get("md5"));
            }
            if (checksums.containsKey("sha256")) {
                response.setHeader("X-Checksum-Sha256", checksums.get("sha256"));
            }
        }

        // Set additional headers
        Map<String, String> headers = content.headers();
        if (headers != null) {
            headers.forEach(response::setHeader);
        }

        // For HEAD requests, don't write the body
        if ("HEAD".equalsIgnoreCase(request.getMethod())) {
            return;
        }

        // Stream the content
        try (var inputStream = content.content()) {
            if (inputStream != null) {
                OutputStream out = response.getOutputStream();
                inputStream.transferTo(out);
                out.flush();
            }
        }
    }

    private void writeCreatedResponse(CreatedResponse created, HttpServletResponse response) {
        response.setStatus(HttpServletResponse.SC_CREATED);
        if (created.path() != null) {
            response.setHeader("Location", created.path());
        }
        Map<String, String> headers = created.headers();
        if (headers != null) {
            headers.forEach(response::setHeader);
        }
    }

    private void writeErrorWithHeaders(ErrorResponseWithHeaders error, HttpServletResponse response)
            throws IOException {
        error.headers().forEach(response::setHeader);
        if (error.body() != null) {
            response.setStatus(error.statusCode());
            response.setContentType("application/json");
            response.getWriter().write(error.body());
            response.getWriter().flush();
        } else {
            response.sendError(error.statusCode(), error.message());
        }
    }

    private void sendError(HttpServletResponse response, int status, String message) throws IOException {
        response.sendError(status, message);
    }

    private void writeNvdBlockResponse(
            HttpServletResponse response, de.bsnsoft.megarepo.repository.nvd.NvdFirewallService.CheckResult result)
            throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        response.setHeader("X-NVD-Firewall", "blocked");
        StringBuilder json = new StringBuilder();
        json.append("{\"error\":\"NVD_FIREWALL_BLOCKED\",")
                .append("\"message\":\"Download blocked — component has known vulnerabilities above the configured CVSS threshold.\",")
                .append("\"maxCvssScore\":").append(result.maxScore()).append(",")
                .append("\"vulnerabilities\":[");
        var cves = result.vulnerabilities();
        for (int i = 0; i < cves.size(); i++) {
            var c = cves.get(i);
            if (i > 0) json.append(",");
            json.append("{\"cveId\":").append(jsonString(c.cveId())).append(",")
                    .append("\"cvssScore\":").append(c.cvssScore()).append(",")
                    .append("\"severity\":").append(jsonString(c.severity())).append(",")
                    .append("\"description\":").append(jsonString(c.description())).append("}");
        }
        json.append("]}");
        response.getWriter().write(json.toString());
        response.getWriter().flush();
    }

    private static String jsonString(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append("\"");
        return sb.toString();
    }

    private static boolean isAuthenticated() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken);
    }

    public static String currentUser(HttpServletRequest request) {
        var principal = request.getUserPrincipal();
        if (principal != null) {
            return principal.getName();
        }
        return "anonymous";
    }

    public static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            // Take the first IP in the chain (original client)
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
