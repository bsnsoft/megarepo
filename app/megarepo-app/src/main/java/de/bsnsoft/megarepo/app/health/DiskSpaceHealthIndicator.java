package de.bsnsoft.megarepo.app.health;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Health indicator that checks disk space on the data directory volume.
 * Reports WARNING if usable space is below 1 GB, DOWN if the check fails.
 */
@Component("megaRepoDiskSpace")
public class DiskSpaceHealthIndicator implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(DiskSpaceHealthIndicator.class);

    private static final long WARNING_THRESHOLD_BYTES = 1L * 1024 * 1024 * 1024; // 1 GB

    private final Path dataDirectory;

    public DiskSpaceHealthIndicator(@Value("${megarepo.data-directory:./data}") String dataDirectory) {
        this.dataDirectory = Path.of(dataDirectory);
    }

    @Override
    public Health health() {
        try {
            FileStore store = Files.getFileStore(dataDirectory);
            long usableSpace = store.getUsableSpace();
            long totalSpace = store.getTotalSpace();

            Health.Builder builder = (usableSpace < WARNING_THRESHOLD_BYTES)
                    ? Health.status("WARNING")
                    : Health.up();

            return builder
                    .withDetail("path", dataDirectory.toString())
                    .withDetail("totalBytes", totalSpace)
                    .withDetail("usableBytes", usableSpace)
                    .withDetail("totalHuman", humanReadable(totalSpace))
                    .withDetail("usableHuman", humanReadable(usableSpace))
                    .withDetail("thresholdBytes", WARNING_THRESHOLD_BYTES)
                    .build();
        } catch (IOException e) {
            log.error("Failed to check disk space for {}", dataDirectory, e);
            return Health.down()
                    .withDetail("path", dataDirectory.toString())
                    .withDetail("error", e.getMessage())
                    .build();
        }
    }

    private static String humanReadable(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int unit = (int) (Math.log(bytes) / Math.log(1024));
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        return String.format("%.1f %s", bytes / Math.pow(1024, unit), units[Math.min(unit, units.length - 1)]);
    }
}
