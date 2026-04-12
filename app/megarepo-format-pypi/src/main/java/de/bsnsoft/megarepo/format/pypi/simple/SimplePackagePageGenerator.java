package de.bsnsoft.megarepo.format.pypi.simple;

import de.bsnsoft.megarepo.core.format.FormatResponse;
import de.bsnsoft.megarepo.core.format.FormatResponse.ContentResponse;
import de.bsnsoft.megarepo.core.format.FormatResponse.NotFoundResponse;
import de.bsnsoft.megarepo.core.repository.RepositoryConfig;
import de.bsnsoft.megarepo.database.entity.AssetEntity;
import de.bsnsoft.megarepo.database.entity.ComponentEntity;
import de.bsnsoft.megarepo.database.repository.AssetJpaRepository;
import de.bsnsoft.megarepo.database.repository.ComponentJpaRepository;
import de.bsnsoft.megarepo.format.pypi.naming.PythonNameNormalizer;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Generates a PEP 503 package detail page listing all versions/files for a given package.
 */
@Component
public class SimplePackagePageGenerator {

    private final ComponentJpaRepository componentRepository;
    private final AssetJpaRepository assetRepository;
    private final PythonNameNormalizer nameNormalizer;

    public SimplePackagePageGenerator(
            ComponentJpaRepository componentRepository,
            AssetJpaRepository assetRepository,
            PythonNameNormalizer nameNormalizer) {
        this.componentRepository = componentRepository;
        this.assetRepository = assetRepository;
        this.nameNormalizer = nameNormalizer;
    }

    public FormatResponse generate(RepositoryConfig repo, String packageName) {
        String normalizedName = nameNormalizer.normalize(packageName);

        // Find all components for this repository that match the normalized name
        var allComponents = componentRepository.findByRepositoryId(repo.id(), Pageable.unpaged());
        List<ComponentEntity> matchingComponents = allComponents.stream()
                .filter(c -> nameNormalizer.normalize(c.getName()).equals(normalizedName))
                .toList();

        if (matchingComponents.isEmpty()) {
            return new NotFoundResponse("Package not found: " + packageName);
        }

        // Collect all assets for the matching components
        List<AssetEntity> assets = new ArrayList<>();
        for (ComponentEntity component : matchingComponents) {
            var componentAssets = assetRepository.findByComponentId(component.getId(), Pageable.unpaged());
            assets.addAll(componentAssets.getContent());
        }

        var html = new StringBuilder();
        html.append("<!DOCTYPE html>\n<html><body>\n");
        html.append("  <h1>Links for ").append(normalizedName).append("</h1>\n");

        for (AssetEntity asset : assets) {
            String path = asset.getPath();
            String filename = extractFilename(path);
            String sha256 = asset.getChecksumSha256();

            html.append("  <a href=\"../../");
            html.append(path);
            if (sha256 != null && !sha256.isEmpty()) {
                html.append("#sha256=").append(sha256);
            }
            html.append("\">");
            html.append(filename);
            html.append("</a>\n");
        }

        html.append("</body></html>\n");

        byte[] bytes = html.toString().getBytes(StandardCharsets.UTF_8);
        return new ContentResponse(
                new ByteArrayInputStream(bytes),
                "text/html;charset=utf-8",
                bytes.length,
                Map.of(),
                Map.of());
    }

    private String extractFilename(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        int lastSlash = path.lastIndexOf('/');
        return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
    }
}
