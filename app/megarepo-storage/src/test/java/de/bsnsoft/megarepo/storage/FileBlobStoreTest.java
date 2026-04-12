package de.bsnsoft.megarepo.storage;

import de.bsnsoft.megarepo.core.storage.Blob;
import de.bsnsoft.megarepo.core.storage.BlobRef;
import de.bsnsoft.megarepo.core.storage.BlobStoreMetrics;
import de.bsnsoft.megarepo.storage.file.FileBlobStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileBlobStoreTest {

    @TempDir
    Path tempDir;

    private FileBlobStore store;

    @BeforeEach
    void setUp() {
        store = new FileBlobStore("test-store");
        store.init(Map.of("path", tempDir.toString()));
    }

    @Test
    void testStoreThenGet() throws IOException, NoSuchAlgorithmException {
        byte[] data = "Hello, World!".getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = Map.of("Content-Type", "text/plain");

        BlobRef ref = store.store(new ByteArrayInputStream(data), headers);
        assertNotNull(ref);
        assertEquals("test-store", ref.blobStoreName());

        Optional<Blob> result = store.get(ref);
        assertTrue(result.isPresent());

        try (Blob blob = result.get()) {
            byte[] content = blob.inputStream().readAllBytes();
            assertArrayEquals(data, content);

            // Verify checksums
            HexFormat hex = HexFormat.of();
            assertEquals(hex.formatHex(MessageDigest.getInstance("SHA-256").digest(data)),
                    blob.properties().checksums().get("sha256"));
            assertEquals(data.length, blob.properties().size());
            assertEquals("text/plain", blob.properties().contentType());
        }
    }

    @Test
    void testStoreThenExists() {
        byte[] data = "test data".getBytes(StandardCharsets.UTF_8);
        BlobRef ref = store.store(new ByteArrayInputStream(data), Map.of());

        assertTrue(store.exists(ref));
    }

    @Test
    void testDeleteThenExists() {
        byte[] data = "test data".getBytes(StandardCharsets.UTF_8);
        BlobRef ref = store.store(new ByteArrayInputStream(data), Map.of());

        assertTrue(store.exists(ref));
        assertTrue(store.delete(ref));
        assertFalse(store.exists(ref));
    }

    @Test
    void testGetNonExistent() {
        BlobRef ref = new BlobRef("test-store", "vol-00/chap-000/nonexistent");
        Optional<Blob> result = store.get(ref);
        assertFalse(result.isPresent());
    }

    @Test
    void testDeleteNonExistent() {
        BlobRef ref = new BlobRef("test-store", "vol-00/chap-000/nonexistent");
        assertFalse(store.delete(ref));
    }

    @Test
    void testMetricsAfterStoreAndDelete() {
        BlobStoreMetrics initialMetrics = store.getMetrics();
        assertEquals(0, initialMetrics.blobCount());
        assertEquals(0, initialMetrics.totalSizeBytes());
        assertNotNull(initialMetrics.availableSpaceBytes());

        byte[] data1 = "first blob".getBytes(StandardCharsets.UTF_8);
        byte[] data2 = "second blob".getBytes(StandardCharsets.UTF_8);

        BlobRef ref1 = store.store(new ByteArrayInputStream(data1), Map.of());
        BlobStoreMetrics afterFirst = store.getMetrics();
        assertEquals(1, afterFirst.blobCount());
        assertEquals(data1.length, afterFirst.totalSizeBytes());

        BlobRef ref2 = store.store(new ByteArrayInputStream(data2), Map.of());
        BlobStoreMetrics afterSecond = store.getMetrics();
        assertEquals(2, afterSecond.blobCount());
        assertEquals(data1.length + data2.length, afterSecond.totalSizeBytes());

        store.delete(ref1);
        BlobStoreMetrics afterDelete = store.getMetrics();
        assertEquals(1, afterDelete.blobCount());
        assertEquals(data2.length, afterDelete.totalSizeBytes());
    }

    @Test
    void testStoreWithSize() throws IOException {
        byte[] data = "sized data".getBytes(StandardCharsets.UTF_8);
        BlobRef ref = store.store(new ByteArrayInputStream(data), data.length, Map.of());

        assertNotNull(ref);
        Optional<Blob> result = store.get(ref);
        assertTrue(result.isPresent());
        try (Blob blob = result.get()) {
            assertArrayEquals(data, blob.inputStream().readAllBytes());
        }
    }

    @Test
    void testDefaultContentType() throws IOException {
        byte[] data = "no content type".getBytes(StandardCharsets.UTF_8);
        BlobRef ref = store.store(new ByteArrayInputStream(data), Map.of());

        try (Blob blob = store.get(ref).orElseThrow()) {
            assertEquals("application/octet-stream", blob.properties().contentType());
        }
    }
}
