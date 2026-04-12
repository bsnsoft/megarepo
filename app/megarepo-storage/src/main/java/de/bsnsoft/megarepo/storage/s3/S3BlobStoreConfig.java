package de.bsnsoft.megarepo.storage.s3;

import java.util.Map;

public record S3BlobStoreConfig(
        String bucket,
        String region,
        String accessKeyId,
        String secretAccessKey,
        String endpoint,
        String prefix) {

    public static S3BlobStoreConfig fromMap(Map<String, Object> config) {
        String bucket = requireString(config, "bucket");
        String region = requireString(config, "region");
        String accessKeyId = requireString(config, "accessKeyId");
        String secretAccessKey = requireString(config, "secretAccessKey");
        String endpoint = optionalString(config, "endpoint");
        String prefix = normalizePrefix(optionalString(config, "prefix"));
        return new S3BlobStoreConfig(bucket, region, accessKeyId, secretAccessKey, endpoint, prefix);
    }

    String resolveKey(String blobId, String suffix) {
        String normalizedPrefix = prefix != null ? prefix : "";
        return normalizedPrefix + blobId + suffix;
    }

    private static String requireString(Map<String, Object> config, String key) {
        Object value = config.get(key);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException("Missing required S3 config key: " + key);
        }
        return value.toString();
    }

    private static String optionalString(Map<String, Object> config, String key) {
        Object value = config.get(key);
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        return value.toString();
    }

    private static String normalizePrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return "";
        }
        return prefix.endsWith("/") ? prefix : prefix + "/";
    }
}
