package de.bsnsoft.megarepo.format.npm.registry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.bsnsoft.megarepo.core.format.FormatResponse;
import de.bsnsoft.megarepo.core.format.FormatResponse.ContentResponse;
import de.bsnsoft.megarepo.core.format.FormatResponse.NotFoundResponse;
import de.bsnsoft.megarepo.core.repository.RepositoryConfig;
import de.bsnsoft.megarepo.database.entity.AssetEntity;
import de.bsnsoft.megarepo.database.entity.ComponentEntity;
import de.bsnsoft.megarepo.database.repository.AssetJpaRepository;
import de.bsnsoft.megarepo.database.repository.ComponentJpaRepository;
import de.bsnsoft.megarepo.format.npm.scope.ScopedPackageResolver;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class NpmPackageMetadataBuilder {

    private final ComponentJpaRepository componentRepository;
    private final AssetJpaRepository assetRepository;
    private final ScopedPackageResolver scopeResolver;
    private final ObjectMapper objectMapper;

    public NpmPackageMetadataBuilder(
            ComponentJpaRepository componentRepository,
            AssetJpaRepository assetRepository,
            ScopedPackageResolver scopeResolver) {
        this.componentRepository = componentRepository;
        this.assetRepository = assetRepository;
        this.scopeResolver = scopeResolver;
        this.objectMapper = new ObjectMapper();
    }

    public FormatResponse buildMetadata(RepositoryConfig repo, String packageName, String baseUrl) {
        String namespace = null;
        String name = packageName;

        if (scopeResolver.isScoped(packageName)) {
            namespace = scopeResolver.getScope(packageName);
            name = scopeResolver.getPackageName(packageName);
        }

        List<ComponentEntity> components =
                componentRepository.findByRepositoryIdAndNamespaceAndName(repo.id(), namespace, name);

        if (components.isEmpty()) {
            return new NotFoundResponse("Package not found: " + packageName);
        }

        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("name", packageName);

            ObjectNode versions = objectMapper.createObjectNode();
            ObjectNode times = objectMapper.createObjectNode();
            ObjectNode distTags = objectMapper.createObjectNode();

            String latestVersion = null;
            String latestTimestamp = null;

            // Sort by version to find latest
            List<ComponentEntity> sorted = components.stream()
                    .sorted(Comparator.comparing(ComponentEntity::getCreatedAt))
                    .toList();

            for (ComponentEntity component : sorted) {
                String version = component.getVersion();
                String fullPackageName = packageName;

                ObjectNode versionNode = objectMapper.createObjectNode();
                versionNode.put("name", fullPackageName);
                versionNode.put("version", version);

                // Add description from component attributes if available
                Object description = component.getAttributes().get("description");
                if (description instanceof String desc) {
                    versionNode.put("description", desc);
                }

                // Build tarball URL
                String tarballPath = buildTarballPath(namespace, name, version);
                String tarballUrl = baseUrl + "/repository/" + repo.name() + "/" + tarballPath;

                ObjectNode dist = objectMapper.createObjectNode();
                dist.put("tarball", tarballUrl);

                // Find the asset for this tarball to get shasum
                Optional<AssetEntity> assetOpt =
                        assetRepository.findByRepositoryIdAndPath(repo.id(), tarballPath);
                if (assetOpt.isPresent()) {
                    AssetEntity asset = assetOpt.get();
                    if (asset.getChecksumSha1() != null) {
                        dist.put("shasum", asset.getChecksumSha1());
                    }
                    if (asset.getChecksumSha512() != null) {
                        byte[] hashBytes = HexFormat.of().parseHex(asset.getChecksumSha512());
                        String base64Hash = Base64.getEncoder().encodeToString(hashBytes);
                        dist.put("integrity", "sha512-" + base64Hash);
                    }
                }

                versionNode.set("dist", dist);
                versions.set(version, versionNode);

                String timestamp = component.getCreatedAt().toString();
                times.put(version, timestamp);

                latestVersion = version;
                latestTimestamp = timestamp;
            }

            if (latestVersion != null) {
                distTags.put("latest", latestVersion);
            }

            root.set("versions", versions);
            root.set("dist-tags", distTags);
            root.set("time", times);

            byte[] jsonBytes = objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsBytes(root);

            return new ContentResponse(
                    new ByteArrayInputStream(jsonBytes),
                    "application/json",
                    jsonBytes.length,
                    Map.of(),
                    Map.of());

        } catch (Exception e) {
            return new FormatResponse.ErrorResponse(500, "Failed to build package metadata: " + e.getMessage());
        }
    }

    private String buildTarballPath(String namespace, String name, String version) {
        if (namespace != null) {
            return namespace + "/" + name + "/-/" + name + "-" + version + ".tgz";
        }
        return "-/" + name + "-" + version + ".tgz";
    }
}
