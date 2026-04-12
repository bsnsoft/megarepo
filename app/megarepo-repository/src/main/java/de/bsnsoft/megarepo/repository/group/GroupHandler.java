package de.bsnsoft.megarepo.repository.group;

import de.bsnsoft.megarepo.core.format.FormatPlugin;
import de.bsnsoft.megarepo.core.format.FormatRegistry;
import de.bsnsoft.megarepo.core.format.FormatRequestHandler;
import de.bsnsoft.megarepo.core.format.FormatResponse;
import de.bsnsoft.megarepo.core.format.FormatResponse.ContentResponse;
import de.bsnsoft.megarepo.core.format.FormatResponse.ErrorResponse;
import de.bsnsoft.megarepo.core.format.FormatResponse.NotFoundResponse;
import de.bsnsoft.megarepo.core.repository.RepositoryConfig;
import de.bsnsoft.megarepo.core.repository.RepositoryType;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class GroupHandler {

    private static final Logger log = LoggerFactory.getLogger(GroupHandler.class);

    private final GroupMemberResolver groupMemberResolver;
    private final FormatRegistry formatRegistry;

    public GroupHandler(GroupMemberResolver groupMemberResolver, FormatRegistry formatRegistry) {
        this.groupMemberResolver = groupMemberResolver;
        this.formatRegistry = formatRegistry;
    }

    private static final String[] CHECKSUM_EXTENSIONS = {".md5", ".sha1", ".sha256", ".sha512"};

    /**
     * Handles GET requests for group repositories.
     * <p>
     * For artifact paths: iterates members in order, returns first non-404 response.
     * For metadata paths: fetches from all members and merges the results.
     * For checksum files of metadata: computes checksum from the merged metadata content.
     */
    public FormatResponse handleGet(RepositoryConfig groupRepo, String path, HttpServletRequest request) {
        List<RepositoryConfig> members = groupMemberResolver.resolveMembers(groupRepo);
        if (members.isEmpty()) {
            return new NotFoundResponse("Group '%s' has no online members".formatted(groupRepo.name()));
        }

        FormatPlugin plugin = formatRegistry.getPlugin(groupRepo.format());
        FormatRequestHandler handler = plugin.getRequestHandler();

        if (handler.isMetadataPath(path)) {
            return handleMetadataGet(groupRepo, path, request, members, handler);
        }

        // Check if this is a checksum request for a metadata file (e.g. maven-metadata.xml.sha1)
        String metadataChecksumExt = getMetadataChecksumExtension(path, handler);
        if (metadataChecksumExt != null) {
            return handleMetadataChecksumGet(groupRepo, path, metadataChecksumExt, request, members, handler);
        }

        return handleArtifactGet(groupRepo, path, request, members, handler);
    }

    /**
     * Handles PUT requests for group repositories by delegating to the writable member.
     */
    public FormatResponse handlePut(RepositoryConfig groupRepo, String path, HttpServletRequest request) {
        Optional<RepositoryConfig> writableMember = groupMemberResolver.getWritableMember(groupRepo);
        if (writableMember.isEmpty()) {
            return new ErrorResponse(405, "Group repository '%s' has no writable member".formatted(groupRepo.name()));
        }

        RepositoryConfig member = writableMember.get();
        FormatPlugin plugin = formatRegistry.getPlugin(member.format());
        FormatRequestHandler handler = plugin.getRequestHandler();
        return handler.handleHostedPut(member, path, request);
    }

    private FormatResponse handleArtifactGet(
            RepositoryConfig groupRepo,
            String path,
            HttpServletRequest request,
            List<RepositoryConfig> members,
            FormatRequestHandler handler) {
        for (RepositoryConfig member : members) {
            FormatResponse memberResponse = dispatchGet(handler, member, path, request);
            if (!(memberResponse instanceof NotFoundResponse)) {
                log.debug(
                        "Group '{}': artifact '{}' found in member '{}'",
                        groupRepo.name(),
                        path,
                        member.name());
                return memberResponse;
            }
        }
        return new NotFoundResponse("Artifact '%s' not found in any member of group '%s'".formatted(path, groupRepo.name()));
    }

    private FormatResponse handleMetadataGet(
            RepositoryConfig groupRepo,
            String path,
            HttpServletRequest request,
            List<RepositoryConfig> members,
            FormatRequestHandler handler) {
        List<FormatResponse> memberResponses = new ArrayList<>();
        for (RepositoryConfig member : members) {
            FormatResponse memberResponse = dispatchGet(handler, member, path, request);
            memberResponses.add(memberResponse);
        }

        Optional<FormatResponse> merged = handler.mergeMetadata(groupRepo, path, memberResponses);
        if (merged.isPresent()) {
            return merged.get();
        }

        // Fallback: return first non-404 response
        return memberResponses.stream()
                .filter(r -> !(r instanceof NotFoundResponse))
                .findFirst()
                .orElse(new NotFoundResponse(
                        "Metadata '%s' not found in any member of group '%s'".formatted(path, groupRepo.name())));
    }

    /**
     * Handles checksum requests (e.g. maven-metadata.xml.sha1) for group metadata.
     * Fetches the merged metadata from all members, then computes the checksum from the actual merged content
     * instead of returning a stale checksum from the database.
     */
    private FormatResponse handleMetadataChecksumGet(
            RepositoryConfig groupRepo,
            String checksumPath,
            String checksumExtension,
            HttpServletRequest request,
            List<RepositoryConfig> members,
            FormatRequestHandler handler) {
        // Strip the checksum extension to get the metadata path
        String metadataPath = checksumPath.substring(0, checksumPath.length() - checksumExtension.length());

        // Get the merged metadata content
        FormatResponse metadataResponse = handleMetadataGet(groupRepo, metadataPath, request, members, handler);
        if (!(metadataResponse instanceof ContentResponse content)) {
            return new NotFoundResponse("Metadata not found for checksum: " + checksumPath);
        }

        try {
            byte[] metadataBytes = content.content().readAllBytes();
            String algorithm = switch (checksumExtension) {
                case ".md5" -> "MD5";
                case ".sha1" -> "SHA-1";
                case ".sha256" -> "SHA-256";
                case ".sha512" -> "SHA-512";
                default -> null;
            };
            if (algorithm == null) {
                return new NotFoundResponse("Unsupported checksum type: " + checksumExtension);
            }

            String checksum = computeChecksum(metadataBytes, algorithm);
            byte[] checksumBytes = checksum.getBytes(StandardCharsets.UTF_8);
            return new ContentResponse(
                    new ByteArrayInputStream(checksumBytes), "text/plain", checksumBytes.length, Map.of(), Map.of());
        } catch (Exception e) {
            log.error("Failed to compute checksum for group metadata: {}", checksumPath, e);
            return new ErrorResponse(500, "Failed to compute checksum");
        }
    }

    /**
     * Returns the checksum extension if the path is a checksum file for a metadata path, or null otherwise.
     */
    private String getMetadataChecksumExtension(String path, FormatRequestHandler handler) {
        for (String ext : CHECKSUM_EXTENSIONS) {
            if (path.endsWith(ext)) {
                String basePath = path.substring(0, path.length() - ext.length());
                if (handler.isMetadataPath(basePath)) {
                    return ext;
                }
            }
        }
        return null;
    }

    private String computeChecksum(byte[] data, String algorithm) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance(algorithm);
        byte[] hash = digest.digest(data);
        return HexFormat.of().formatHex(hash);
    }

    private FormatResponse dispatchGet(
            FormatRequestHandler handler, RepositoryConfig member, String path, HttpServletRequest request) {
        return switch (member.type()) {
            case HOSTED -> handler.handleHostedGet(member, path, request);
            case PROXY -> handler.handleProxyGet(member, path, request);
            case GROUP -> handler.handleGroupGet(member, path, request);
        };
    }
}
