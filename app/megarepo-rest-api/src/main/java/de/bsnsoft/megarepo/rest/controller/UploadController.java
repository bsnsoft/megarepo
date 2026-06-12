package de.bsnsoft.megarepo.rest.controller;

import de.bsnsoft.megarepo.core.exception.NotFoundException;
import de.bsnsoft.megarepo.core.exception.ValidationException;
import de.bsnsoft.megarepo.core.format.ComponentUpload;
import de.bsnsoft.megarepo.core.format.ComponentUpload.UploadFile;
import de.bsnsoft.megarepo.core.format.ComponentUploadHandler;
import de.bsnsoft.megarepo.core.format.FormatPlugin;
import de.bsnsoft.megarepo.core.format.FormatRegistry;
import de.bsnsoft.megarepo.core.format.FormatResponse;
import de.bsnsoft.megarepo.core.format.FormatResponse.CreatedResponse;
import de.bsnsoft.megarepo.core.format.UnsupportedFormatException;
import de.bsnsoft.megarepo.core.repository.RepositoryConfig;
import de.bsnsoft.megarepo.core.repository.RepositoryConfigService;
import de.bsnsoft.megarepo.core.repository.RepositoryType;
import de.bsnsoft.megarepo.repository.ActivityBroadcaster;
import de.bsnsoft.megarepo.repository.ActivityEvent;
import de.bsnsoft.megarepo.repository.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Manual artifact upload into hosted repositories.
 *
 * <p>Accepts {@code multipart/form-data}; text fields and files are passed to
 * the format plugin's {@link ComponentUploadHandler}, which reuses the same
 * storage pipeline as the format's native publish mechanism. Proxy and group
 * repositories are read-only and rejected; formats without a handler (e.g.
 * Docker) are rejected with a helpful message.
 */
@RestController
@RequestMapping("/api/v1/components")
public class UploadController {

    private final RepositoryConfigService repositoryConfigService;
    private final FormatRegistry formatRegistry;
    private final AuditService auditService;
    private final ActivityBroadcaster activityBroadcaster;

    public UploadController(
            RepositoryConfigService repositoryConfigService,
            FormatRegistry formatRegistry,
            AuditService auditService,
            ActivityBroadcaster activityBroadcaster) {
        this.repositoryConfigService = repositoryConfigService;
        this.formatRegistry = formatRegistry;
        this.auditService = auditService;
        this.activityBroadcaster = activityBroadcaster;
    }

    public record UploadResultXO(String repository, String path) {}

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<UploadResultXO> upload(
            @RequestParam("repository") String repositoryName, MultipartHttpServletRequest request) {

        RepositoryConfig repo = repositoryConfigService
                .getRepository(repositoryName)
                .orElseThrow(() -> new NotFoundException("Repository not found: " + repositoryName));

        if (repo.type() != RepositoryType.HOSTED) {
            throw new ValidationException(
                    "Uploads are only supported for hosted repositories — '%s' is a %s repository"
                            .formatted(repositoryName, repo.type().name().toLowerCase()));
        }
        if (!repo.online()) {
            throw new ValidationException("Repository '%s' is offline".formatted(repositoryName));
        }

        FormatPlugin plugin;
        try {
            plugin = formatRegistry.getPlugin(repo.format());
        } catch (UnsupportedFormatException e) {
            throw new ValidationException("Unsupported repository format: " + repo.format());
        }

        ComponentUploadHandler handler = plugin
                .getComponentUploadHandler()
                .orElseThrow(() -> new ValidationException(
                        "Manual upload is not supported for %s repositories — use the format's native publish mechanism"
                                .formatted(plugin.getDisplayName())));

        ComponentUpload upload = buildUpload(request);

        FormatResponse result = handler.handleUpload(repo, upload);

        return switch (result) {
            case CreatedResponse created -> {
                long totalSize = upload.files().stream().mapToLong(UploadFile::size).sum();
                auditService.logUpload(
                        upload.username(), repo.name(), created.path(), repo.format(), totalSize, upload.clientIp());
                activityBroadcaster.broadcast(new ActivityEvent(
                        Instant.now(), upload.username(), "UPLOAD", repo.name(), created.path(), repo.format(),
                        totalSize, 0L, null));
                yield ResponseEntity.status(HttpStatus.CREATED)
                        .body(new UploadResultXO(repo.name(), created.path()));
            }
            case FormatResponse.ErrorResponse error -> {
                if (error.statusCode() >= 400 && error.statusCode() < 500) {
                    throw new ValidationException(error.message());
                }
                throw new IllegalStateException("Upload failed: " + error.message());
            }
            default -> throw new IllegalStateException("Unexpected upload result: " + result.getClass().getSimpleName());
        };
    }

    private ComponentUpload buildUpload(MultipartHttpServletRequest request) {
        Map<String, String> fields = new LinkedHashMap<>();
        request.getParameterMap().forEach((name, values) -> {
            if (!"repository".equals(name) && values.length > 0) {
                validateNoTraversal(name, values[0]);
                fields.put(name, values[0]);
            }
        });

        List<UploadFile> files = new ArrayList<>();
        for (Map.Entry<String, List<MultipartFile>> entry : request.getMultiFileMap().entrySet()) {
            for (MultipartFile multipartFile : entry.getValue()) {
                if (multipartFile.isEmpty() && (multipartFile.getOriginalFilename() == null
                        || multipartFile.getOriginalFilename().isBlank())) {
                    continue;
                }
                String filename = sanitizeFilename(multipartFile.getOriginalFilename());
                files.add(new UploadFile(
                        entry.getKey(),
                        filename,
                        multipartFile.getContentType(),
                        multipartFile::getInputStream,
                        multipartFile.getSize()));
            }
        }

        if (files.isEmpty()) {
            throw new ValidationException("No file uploaded");
        }

        return new ComponentUpload(fields, files, currentUser(), clientIp(request));
    }

    /** Strips any client-supplied directory part and rejects traversal tricks. */
    private static String sanitizeFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            throw new ValidationException("Uploaded file has no filename");
        }
        String name = filename;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        if (name.isBlank() || name.equals(".") || name.equals("..")) {
            throw new ValidationException("Invalid filename: " + filename);
        }
        return name;
    }

    /** Rejects path traversal in text fields used to build target paths. */
    private static void validateNoTraversal(String field, String value) {
        if (value == null) {
            return;
        }
        for (String segment : value.split("/")) {
            if ("..".equals(segment)) {
                throw new ValidationException("Invalid value for field '" + field + "': path traversal not allowed");
            }
        }
        if (value.indexOf('\0') >= 0) {
            throw new ValidationException("Invalid value for field '" + field + "'");
        }
    }

    private static String currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : "anonymous";
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
