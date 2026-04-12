package de.bsnsoft.megarepo.app.health;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Health indicator that checks whether the default blob store directory exists and is writable.
 */
@Component
public class BlobStoreHealthIndicator implements HealthIndicator {

    private final Path blobStorePath;

    public BlobStoreHealthIndicator(@Value("${megarepo.blob-stores.default-path:./data/blobs/default}") String path) {
        this.blobStorePath = Path.of(path);
    }

    @Override
    public Health health() {
        if (!Files.exists(blobStorePath)) {
            return Health.down()
                    .withDetail("path", blobStorePath.toString())
                    .withDetail("reason", "Blob store directory does not exist")
                    .build();
        }

        if (!Files.isDirectory(blobStorePath)) {
            return Health.down()
                    .withDetail("path", blobStorePath.toString())
                    .withDetail("reason", "Blob store path is not a directory")
                    .build();
        }

        if (!Files.isWritable(blobStorePath)) {
            return Health.down()
                    .withDetail("path", blobStorePath.toString())
                    .withDetail("reason", "Blob store directory is not writable")
                    .build();
        }

        return Health.up()
                .withDetail("path", blobStorePath.toString())
                .build();
    }
}
