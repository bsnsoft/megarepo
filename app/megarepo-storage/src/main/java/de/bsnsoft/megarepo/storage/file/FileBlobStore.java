package de.bsnsoft.megarepo.storage.file;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import de.bsnsoft.megarepo.core.storage.Blob;
import de.bsnsoft.megarepo.core.storage.BlobProperties;
import de.bsnsoft.megarepo.core.storage.BlobRef;
import de.bsnsoft.megarepo.core.storage.BlobStore;
import de.bsnsoft.megarepo.core.storage.BlobStoreMetrics;
import de.bsnsoft.megarepo.core.storage.BlobStoreType;
import de.bsnsoft.megarepo.storage.MultiDigestInputStream;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

public class FileBlobStore implements BlobStore {

    private final String name;
    private final ObjectMapper objectMapper;

    private FileBlobStoreConfig config;
    private VolumeChapterAllocator allocator;
    private final AtomicLong blobCount = new AtomicLong(0);
    private final AtomicLong totalSize = new AtomicLong(0);

    public FileBlobStore(String name) {
        this.name = name;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.registerModule(new ParameterNamesModule());
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public BlobStoreType getType() {
        return BlobStoreType.FILE;
    }

    @Override
    public void init(Map<String, Object> config) {
        this.config = FileBlobStoreConfig.fromMap(config);
        Path contentDir = this.config.basePath().resolve("content");

        try {
            Files.createDirectories(contentDir);

            // Count existing blobs to initialize allocator
            long existingCount = countExistingBlobs(contentDir);
            this.allocator = new VolumeChapterAllocator(contentDir, existingCount);

            // Load existing metadata
            loadMetadata();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to initialize file blob store", e);
        }
    }

    private long countExistingBlobs(Path contentDir) throws IOException {
        if (!Files.exists(contentDir)) {
            return 0;
        }
        try (var stream = Files.walk(contentDir)) {
            return stream.filter(p -> p.toString().endsWith(".bytes")).count();
        }
    }

    private void loadMetadata() {
        Path metadataPath = config.basePath().resolve("metadata.properties");
        if (Files.exists(metadataPath)) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> metadata = objectMapper.readValue(metadataPath.toFile(), Map.class);
                blobCount.set(((Number) metadata.get("blobCount")).longValue());
                totalSize.set(((Number) metadata.get("totalSize")).longValue());
            } catch (IOException e) {
                // If metadata is corrupted, start fresh
                blobCount.set(0);
                totalSize.set(0);
            }
        }
    }

    private void saveMetadata() {
        Path metadataPath = config.basePath().resolve("metadata.properties");
        try {
            Map<String, Object> metadata = Map.of(
                    "blobCount", blobCount.get(),
                    "totalSize", totalSize.get()
            );
            objectMapper.writeValue(metadataPath.toFile(), metadata);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to save metadata", e);
        }
    }

    @Override
    public BlobRef store(InputStream data, Map<String, String> headers) {
        return doStore(data, headers);
    }

    @Override
    public BlobRef store(InputStream data, long size, Map<String, String> headers) {
        return doStore(data, headers);
    }

    private BlobRef doStore(InputStream data, Map<String, String> headers) {
        String uuid = UUID.randomUUID().toString();
        Path dir = allocator.allocate();

        try {
            Files.createDirectories(dir);

            Path bytesFile = dir.resolve(uuid + ".bytes");
            Path tempFile = dir.resolve(uuid + ".bytes.tmp");
            Path propertiesFile = dir.resolve(uuid + ".properties");

            // Wrap stream to compute digests
            MultiDigestInputStream digestStream = new MultiDigestInputStream(data);

            // Write to temp file, then atomic rename
            Files.copy(digestStream, tempFile);
            Files.move(tempFile, bytesFile, StandardCopyOption.ATOMIC_MOVE);

            long size = digestStream.getBytesRead();
            Map<String, String> checksums = digestStream.getChecksums();

            // Determine content type from headers
            String contentType = headers.getOrDefault("Content-Type", "application/octet-stream");

            // Write properties file
            BlobProperties properties = new BlobProperties(
                    size, contentType, checksums, Instant.now(), headers
            );
            objectMapper.writeValue(propertiesFile.toFile(), properties);

            // Update counters
            blobCount.incrementAndGet();
            totalSize.addAndGet(size);
            saveMetadata();

            // Build blobId as relative path from content dir
            Path contentDir = config.basePath().resolve("content");
            String blobId = contentDir.relativize(dir).resolve(uuid).toString();

            return new BlobRef(name, blobId);

        } catch (IOException e) {
            throw new UncheckedIOException("Failed to store blob", e);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Missing digest algorithm", e);
        }
    }

    @Override
    public Optional<Blob> get(BlobRef ref) {
        Path contentDir = config.basePath().resolve("content");
        Path bytesFile = contentDir.resolve(ref.blobId() + ".bytes").normalize();
        Path propertiesFile = contentDir.resolve(ref.blobId() + ".properties").normalize();

        // Path traversal protection: ensure resolved path stays within content directory
        if (!bytesFile.startsWith(contentDir) || !propertiesFile.startsWith(contentDir)) {
            return Optional.empty();
        }

        if (!Files.exists(bytesFile)) {
            return Optional.empty();
        }

        try {
            BlobProperties properties = objectMapper.readValue(propertiesFile.toFile(), BlobProperties.class);
            InputStream inputStream = new BufferedInputStream(new FileInputStream(bytesFile.toFile()));
            return Optional.of(new Blob(ref, inputStream, properties));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read blob", e);
        }
    }

    @Override
    public boolean delete(BlobRef ref) {
        Path contentDir = config.basePath().resolve("content");
        Path bytesFile = contentDir.resolve(ref.blobId() + ".bytes").normalize();
        Path propertiesFile = contentDir.resolve(ref.blobId() + ".properties").normalize();

        // Path traversal protection
        if (!bytesFile.startsWith(contentDir) || !propertiesFile.startsWith(contentDir)) {
            return false;
        }

        if (!Files.exists(bytesFile)) {
            return false;
        }

        try {
            long fileSize = Files.size(bytesFile);
            Files.deleteIfExists(bytesFile);
            Files.deleteIfExists(propertiesFile);

            blobCount.decrementAndGet();
            totalSize.addAndGet(-fileSize);
            saveMetadata();

            return true;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete blob", e);
        }
    }

    @Override
    public boolean exists(BlobRef ref) {
        Path contentDir = config.basePath().resolve("content");
        Path bytesFile = contentDir.resolve(ref.blobId() + ".bytes").normalize();
        // Path traversal protection
        if (!bytesFile.startsWith(contentDir)) {
            return false;
        }
        return Files.exists(bytesFile);
    }

    @Override
    public BlobStoreMetrics getMetrics() {
        Long availableSpace = null;
        try {
            availableSpace = Files.getFileStore(config.basePath()).getUsableSpace();
        } catch (IOException e) {
            // Ignore - availableSpace will be null
        }
        return new BlobStoreMetrics(blobCount.get(), totalSize.get(), availableSpace);
    }

    @Override
    public void compact() {
        // No-op for now - direct delete instead of soft-delete
    }
}
