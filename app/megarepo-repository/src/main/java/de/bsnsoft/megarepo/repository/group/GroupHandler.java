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
     * A group's answer to a GET, together with the member repository that
     * produced it.
     *
     * <p>The second half is what makes the repository firewall work through a
     * group (osTicket #155155). A group owns no assets and no components: the
     * artifact behind {@code /repository/my-group/…} physically lives in one of
     * the members, and so do its {@code assets} row, its component, and the
     * {@code firewall_repository_config} that says whether it may be handed out.
     * A caller that only receives the {@link FormatResponse} cannot look any of
     * that up, which is precisely how group downloads bypassed enforcement:
     * {@code RepositoryRouter} evaluated the <em>group's</em> id, found no asset
     * and no configuration, and served.
     *
     * @param response what to send to the client
     * @param servedBy the member that resolved the artifact, or {@code null} when
     *     no single member did — a 404, or merged metadata, which is assembled
     *     from every member at once and belongs to none of them. Metadata carries
     *     no component, so the firewall has nothing to say about it either way.
     */
    public record GroupResponse(FormatResponse response, RepositoryConfig servedBy) {

        /** An answer that belongs to the group itself rather than to one member. */
        static GroupResponse ofGroup(FormatResponse response) {
            return new GroupResponse(response, null);
        }
    }

    /**
     * Handles GET requests for group repositories.
     * <p>
     * For artifact paths: iterates members in order, returns first non-404 response.
     * For metadata paths: fetches from all members and merges the results.
     * For checksum files of metadata: computes checksum from the merged metadata content.
     */
    public GroupResponse handleGet(RepositoryConfig groupRepo, String path, HttpServletRequest request) {
        List<RepositoryConfig> members = groupMemberResolver.resolveMembers(groupRepo);
        if (members.isEmpty()) {
            return GroupResponse.ofGroup(
                    new NotFoundResponse("Group '%s' has no online members".formatted(groupRepo.name())));
        }

        FormatPlugin plugin = formatRegistry.getPlugin(groupRepo.format());
        FormatRequestHandler handler = plugin.getRequestHandler();

        if (handler.isMetadataPath(path)) {
            return GroupResponse.ofGroup(handleMetadataGet(groupRepo, path, request, members, handler));
        }

        // Check if this is a checksum request for a metadata file (e.g. maven-metadata.xml.sha1)
        String metadataChecksumExt = getMetadataChecksumExtension(path, handler);
        if (metadataChecksumExt != null) {
            return GroupResponse.ofGroup(handleMetadataChecksumGet(
                    groupRepo, path, metadataChecksumExt, request, members, handler));
        }

        return handleArtifactGet(groupRepo, path, request, members, handler);
    }

    /**
     * Handles PUT requests for group repositories by delegating to the writable member.
     */
    public FormatResponse handlePut(RepositoryConfig groupRepo, String path, HttpServletRequest request) {
        return handlePutVia(groupRepo, path, request).response();
    }

    /**
     * The same, saying which member stored it.
     *
     * <p>The firewall needs to know: an upload published through a group lands in
     * exactly one member, and that member's mode, policy and fail mode are what
     * govern it — the same rule the GET path already follows. It is also the
     * repository a refused upload has to be retracted from, and retracting from
     * the group would delete nothing (osTicket #155155).
     */
    public GroupResponse handlePutVia(
            RepositoryConfig groupRepo, String path, HttpServletRequest request) {

        Optional<RepositoryConfig> writableMember = groupMemberResolver.getWritableMember(groupRepo);
        if (writableMember.isEmpty()) {
            return GroupResponse.ofGroup(new ErrorResponse(
                    405, "Group repository '%s' has no writable member".formatted(groupRepo.name())));
        }

        RepositoryConfig member = writableMember.get();
        FormatPlugin plugin = formatRegistry.getPlugin(member.format());
        FormatRequestHandler handler = plugin.getRequestHandler();
        return new GroupResponse(handler.handleHostedPut(member, path, request), member);
    }

    /**
     * First member that has the artifact wins — and it wins <em>outright</em>.
     *
     * <p>The loop stops at the first non-404, and the firewall verdict is taken
     * on that member afterwards. So a member whose policy denies the artifact
     * denies the download; the search does not carry on to a member that would
     * have served it. Anything else would make the group a bypass: an operator
     * could quarantine a proxy and still get the same component through the group
     * that contains it, which is the one outcome this whole feature exists to
     * prevent.
     */
    private GroupResponse handleArtifactGet(
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
                return new GroupResponse(memberResponse, member);
            }
        }
        return GroupResponse.ofGroup(new NotFoundResponse(
                "Artifact '%s' not found in any member of group '%s'".formatted(path, groupRepo.name())));
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
