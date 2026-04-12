package de.bsnsoft.megarepo.format.maven.checksum;

import de.bsnsoft.megarepo.core.format.FormatResponse;
import de.bsnsoft.megarepo.core.format.FormatResponse.ContentResponse;
import de.bsnsoft.megarepo.core.format.FormatResponse.NotFoundResponse;
import de.bsnsoft.megarepo.database.entity.AssetEntity;
import de.bsnsoft.megarepo.database.repository.AssetJpaRepository;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Component
public class ChecksumFileHandler {

    private final AssetJpaRepository assetRepository;

    public ChecksumFileHandler(AssetJpaRepository assetRepository) {
        this.assetRepository = assetRepository;
    }

    public FormatResponse handleChecksumRequest(UUID repoId, String path) {
        String checksumExtension = getChecksumExtension(path);
        if (checksumExtension == null) {
            return new NotFoundResponse("Not a checksum path: " + path);
        }

        String originalPath = path.substring(0, path.length() - checksumExtension.length());
        Optional<AssetEntity> assetOpt = assetRepository.findByRepositoryIdAndPath(repoId, originalPath);
        if (assetOpt.isEmpty()) {
            return new NotFoundResponse("Original asset not found: " + originalPath);
        }

        AssetEntity asset = assetOpt.get();
        String checksumValue = switch (checksumExtension) {
            case ".md5" -> asset.getChecksumMd5();
            case ".sha1" -> asset.getChecksumSha1();
            case ".sha256" -> asset.getChecksumSha256();
            case ".sha512" -> asset.getChecksumSha512();
            default -> null;
        };

        if (checksumValue == null) {
            return new NotFoundResponse("Checksum not available for: " + path);
        }

        byte[] bytes = checksumValue.getBytes(StandardCharsets.UTF_8);
        return new ContentResponse(
                new ByteArrayInputStream(bytes),
                "text/plain",
                bytes.length,
                Map.of(),
                Map.of());
    }

    public boolean isChecksumPath(String path) {
        return getChecksumExtension(path) != null;
    }

    private String getChecksumExtension(String path) {
        if (path.endsWith(".md5")) {
            return ".md5";
        }
        if (path.endsWith(".sha1")) {
            return ".sha1";
        }
        if (path.endsWith(".sha256")) {
            return ".sha256";
        }
        if (path.endsWith(".sha512")) {
            return ".sha512";
        }
        return null;
    }
}
