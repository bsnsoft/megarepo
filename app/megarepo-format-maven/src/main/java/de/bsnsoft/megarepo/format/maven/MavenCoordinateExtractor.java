package de.bsnsoft.megarepo.format.maven;

import de.bsnsoft.megarepo.core.format.ComponentCoordinateExtractor;
import de.bsnsoft.megarepo.core.format.ComponentCoordinates;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Component
public class MavenCoordinateExtractor implements ComponentCoordinateExtractor {

    @Override
    public Optional<ComponentCoordinates> extractFromPath(String path) {
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }

        String normalized = path.startsWith("/") ? path.substring(1) : path;
        if (normalized.isEmpty()) {
            return Optional.empty();
        }

        // Remove trailing slash if present
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }

        String[] segments = normalized.split("/");

        // Maven layout: {groupId-as-dirs}/{artifactId}/{version}/{filename}
        // Minimum segments: groupId(1) + artifactId(1) + version(1) + filename(1) = 4
        if (segments.length < 4) {
            return Optional.empty();
        }

        String filename = segments[segments.length - 1];

        // Skip metadata files
        if ("maven-metadata.xml".equals(filename)
                || filename.startsWith("maven-metadata.xml.")) {
            return Optional.empty();
        }

        // Skip checksum files - they are computed, not real artifacts
        if (isChecksumFile(filename)) {
            return Optional.empty();
        }

        String version = segments[segments.length - 2];
        String artifactId = segments[segments.length - 3];

        // Everything before artifactId is groupId (join with .)
        var groupIdBuilder = new StringBuilder();
        for (int i = 0; i < segments.length - 3; i++) {
            if (i > 0) {
                groupIdBuilder.append(".");
            }
            groupIdBuilder.append(segments[i]);
        }
        String groupId = groupIdBuilder.toString();

        // Parse filename to extract classifier and extension
        Map<String, String> formatAttributes = parseFilename(filename, artifactId, version);

        return Optional.of(new ComponentCoordinates(groupId, artifactId, version, formatAttributes));
    }

    @Override
    public Optional<ComponentCoordinates> extractFromContent(
            InputStream content, String path, Map<String, String> attributes) {
        return extractFromPath(path);
    }

    private Map<String, String> parseFilename(String filename, String artifactId, String version) {
        Map<String, String> attributes = new HashMap<>();

        // Expected: {artifactId}-{version}[-{classifier}].{extension}
        // For snapshots with timestamps: {artifactId}-{timestamp}-{buildNumber}[-{classifier}].{extension}
        String prefix = artifactId + "-" + version;

        String remainder;
        if (filename.startsWith(prefix)) {
            remainder = filename.substring(prefix.length());
        } else if (filename.startsWith(artifactId + "-") && version.endsWith("-SNAPSHOT")) {
            // Handle timestamped snapshot: artifactId-YYYYMMDD.HHMMSS-buildNumber[-classifier].ext
            // Strip artifactId- prefix and try to find the extension/classifier after timestamp
            String afterArtifact = filename.substring(artifactId.length() + 1);
            // Find the extension by looking for the last dot that's not part of timestamp
            int dotIdx = findExtensionDot(afterArtifact);
            if (dotIdx >= 0) {
                String extension = afterArtifact.substring(dotIdx + 1);
                String beforeExt = afterArtifact.substring(0, dotIdx);
                // Check for classifier: after timestamp-buildNumber, there might be -classifier
                String classifier = extractSnapshotClassifier(beforeExt, version);
                attributes.put("extension", extension);
                attributes.put("classifier", classifier != null ? classifier : "");
            } else {
                attributes.put("extension", "");
                attributes.put("classifier", "");
            }
            return attributes;
        } else {
            // Cannot parse filename according to Maven conventions
            // Fall back: extension = everything after last dot
            int lastDot = filename.lastIndexOf('.');
            if (lastDot >= 0) {
                attributes.put("extension", filename.substring(lastDot + 1));
            } else {
                attributes.put("extension", "");
            }
            attributes.put("classifier", "");
            return attributes;
        }

        // remainder is either empty, starts with "-" (classifier), or starts with "." (extension)
        if (remainder.isEmpty()) {
            attributes.put("extension", "");
            attributes.put("classifier", "");
        } else if (remainder.startsWith("-")) {
            // Has classifier: -{classifier}.{extension}
            String classifierAndExt = remainder.substring(1);
            int dotIdx = classifierAndExt.lastIndexOf('.');
            if (dotIdx >= 0) {
                attributes.put("classifier", classifierAndExt.substring(0, dotIdx));
                attributes.put("extension", classifierAndExt.substring(dotIdx + 1));
            } else {
                attributes.put("classifier", classifierAndExt);
                attributes.put("extension", "");
            }
        } else if (remainder.startsWith(".")) {
            // No classifier, just extension
            attributes.put("extension", remainder.substring(1));
            attributes.put("classifier", "");
        } else {
            // Unexpected format
            attributes.put("extension", "");
            attributes.put("classifier", "");
        }

        return attributes;
    }

    private boolean isChecksumFile(String filename) {
        return filename.endsWith(".md5")
                || filename.endsWith(".sha1")
                || filename.endsWith(".sha256")
                || filename.endsWith(".sha512");
    }

    private int findExtensionDot(String str) {
        // For snapshot timestamps like "20220101.120000-1.jar" or "20220101.120000-1-sources.jar"
        // We want the last dot that separates the extension
        return str.lastIndexOf('.');
    }

    private String extractSnapshotClassifier(String beforeExt, String version) {
        // beforeExt is something like "20220101.120000-1" or "20220101.120000-1-sources"
        // The timestamp format is YYYYMMDD.HHMMSS-buildNumber
        // After that, there may be -classifier

        // Find pattern: digits.digits-digits (timestamp-buildnumber)
        // Then anything after that starting with - is classifier
        int dashCount = 0;
        int lastTimestampDash = -1;
        for (int i = 0; i < beforeExt.length(); i++) {
            if (beforeExt.charAt(i) == '-') {
                dashCount++;
                if (dashCount == 1) {
                    lastTimestampDash = i;
                } else if (dashCount == 2) {
                    // This dash starts the classifier
                    return beforeExt.substring(i + 1);
                }
            }
        }
        return null;
    }
}
