package de.bsnsoft.megarepo.storage.s3;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class S3BlobStoreConfigTest {

    @Test
    void fromMapParsesAllFields() {
        Map<String, Object> map = new HashMap<>();
        map.put("bucket", "my-bucket");
        map.put("region", "eu-central-1");
        map.put("accessKeyId", "AKIAIOSFODNN7EXAMPLE");
        map.put("secretAccessKey", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY");
        map.put("endpoint", "http://localhost:9000");
        map.put("prefix", "megarepo");

        S3BlobStoreConfig config = S3BlobStoreConfig.fromMap(map);

        assertEquals("my-bucket", config.bucket());
        assertEquals("eu-central-1", config.region());
        assertEquals("AKIAIOSFODNN7EXAMPLE", config.accessKeyId());
        assertEquals("wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY", config.secretAccessKey());
        assertEquals("http://localhost:9000", config.endpoint());
        assertEquals("megarepo/", config.prefix());
    }

    @Test
    void fromMapHandlesOptionalFieldsAsNull() {
        Map<String, Object> map = new HashMap<>();
        map.put("bucket", "my-bucket");
        map.put("region", "us-east-1");
        map.put("accessKeyId", "AKID");
        map.put("secretAccessKey", "SECRET");

        S3BlobStoreConfig config = S3BlobStoreConfig.fromMap(map);

        assertNull(config.endpoint());
        assertEquals("", config.prefix());
    }

    @Test
    void fromMapNormalizesPrefixWithTrailingSlash() {
        Map<String, Object> map = new HashMap<>();
        map.put("bucket", "b");
        map.put("region", "r");
        map.put("accessKeyId", "k");
        map.put("secretAccessKey", "s");
        map.put("prefix", "repo/data/");

        S3BlobStoreConfig config = S3BlobStoreConfig.fromMap(map);
        assertEquals("repo/data/", config.prefix());
    }

    @Test
    void fromMapNormalizesPrefixWithoutTrailingSlash() {
        Map<String, Object> map = new HashMap<>();
        map.put("bucket", "b");
        map.put("region", "r");
        map.put("accessKeyId", "k");
        map.put("secretAccessKey", "s");
        map.put("prefix", "repo/data");

        S3BlobStoreConfig config = S3BlobStoreConfig.fromMap(map);
        assertEquals("repo/data/", config.prefix());
    }

    @Test
    void fromMapThrowsOnMissingBucket() {
        Map<String, Object> map = new HashMap<>();
        map.put("region", "r");
        map.put("accessKeyId", "k");
        map.put("secretAccessKey", "s");

        assertThrows(IllegalArgumentException.class, () -> S3BlobStoreConfig.fromMap(map));
    }

    @Test
    void fromMapThrowsOnMissingRegion() {
        Map<String, Object> map = new HashMap<>();
        map.put("bucket", "b");
        map.put("accessKeyId", "k");
        map.put("secretAccessKey", "s");

        assertThrows(IllegalArgumentException.class, () -> S3BlobStoreConfig.fromMap(map));
    }

    @Test
    void fromMapThrowsOnMissingAccessKey() {
        Map<String, Object> map = new HashMap<>();
        map.put("bucket", "b");
        map.put("region", "r");
        map.put("secretAccessKey", "s");

        assertThrows(IllegalArgumentException.class, () -> S3BlobStoreConfig.fromMap(map));
    }

    @Test
    void fromMapThrowsOnMissingSecretKey() {
        Map<String, Object> map = new HashMap<>();
        map.put("bucket", "b");
        map.put("region", "r");
        map.put("accessKeyId", "k");

        assertThrows(IllegalArgumentException.class, () -> S3BlobStoreConfig.fromMap(map));
    }

    @Test
    void resolveKeyWithPrefix() {
        S3BlobStoreConfig config = new S3BlobStoreConfig("b", "r", "k", "s", null, "megarepo/");
        assertEquals("megarepo/abc-123.bytes", config.resolveKey("abc-123", ".bytes"));
        assertEquals("megarepo/abc-123.properties", config.resolveKey("abc-123", ".properties"));
    }

    @Test
    void resolveKeyWithoutPrefix() {
        S3BlobStoreConfig config = new S3BlobStoreConfig("b", "r", "k", "s", null, "");
        assertEquals("abc-123.bytes", config.resolveKey("abc-123", ".bytes"));
    }
}
