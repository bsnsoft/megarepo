package de.bsnsoft.megarepo.storage.s3;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import de.bsnsoft.megarepo.core.exception.MegaRepoException;
import de.bsnsoft.megarepo.core.storage.Blob;
import de.bsnsoft.megarepo.core.storage.BlobProperties;
import de.bsnsoft.megarepo.core.storage.BlobRef;
import de.bsnsoft.megarepo.core.storage.BlobStore;
import de.bsnsoft.megarepo.core.storage.BlobStoreMetrics;
import de.bsnsoft.megarepo.core.storage.BlobStoreType;
import de.bsnsoft.megarepo.storage.MultiDigestInputStream;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

public class S3BlobStore implements BlobStore {

    private static final String BYTES_SUFFIX = ".bytes";
    private static final String PROPERTIES_SUFFIX = ".properties";

    private final String name;
    private final ObjectMapper objectMapper;

    private S3BlobStoreConfig config;
    private S3Client s3Client;
    private final AtomicLong cachedBlobCount = new AtomicLong(-1);
    private final AtomicLong cachedTotalSize = new AtomicLong(-1);

    public S3BlobStore(String name) {
        this.name = name;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.registerModule(new ParameterNamesModule());
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    // Visible for testing
    S3BlobStore(String name, S3Client s3Client, S3BlobStoreConfig config) {
        this(name);
        this.s3Client = s3Client;
        this.config = config;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public BlobStoreType getType() {
        return BlobStoreType.S3;
    }

    @Override
    public void init(Map<String, Object> config) {
        this.config = S3BlobStoreConfig.fromMap(config);

        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(this.config.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(this.config.accessKeyId(), this.config.secretAccessKey())));

        if (this.config.endpoint() != null && !this.config.endpoint().isBlank()) {
            builder.endpointOverride(URI.create(this.config.endpoint())).forcePathStyle(true);
        }

        this.s3Client = builder.build();
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
        String bytesKey = config.resolveKey(uuid, BYTES_SUFFIX);
        String propsKey = config.resolveKey(uuid, PROPERTIES_SUFFIX);

        try {
            MultiDigestInputStream digestStream = new MultiDigestInputStream(data);

            // Read all bytes to compute digests and get size
            byte[] content = digestStream.readAllBytes();
            long size = digestStream.getBytesRead();
            Map<String, String> checksums = digestStream.getChecksums();

            String contentType = headers.getOrDefault("Content-Type", "application/octet-stream");

            // Upload blob bytes
            PutObjectRequest bytesRequest = PutObjectRequest.builder()
                    .bucket(config.bucket())
                    .key(bytesKey)
                    .contentType(contentType)
                    .build();
            s3Client.putObject(bytesRequest, RequestBody.fromBytes(content));

            // Build and upload properties
            BlobProperties properties = new BlobProperties(size, contentType, checksums, Instant.now(), headers);
            byte[] propsBytes = objectMapper.writeValueAsBytes(properties);

            PutObjectRequest propsRequest = PutObjectRequest.builder()
                    .bucket(config.bucket())
                    .key(propsKey)
                    .contentType("application/json")
                    .build();
            s3Client.putObject(propsRequest, RequestBody.fromBytes(propsBytes));

            // Invalidate metrics cache
            cachedBlobCount.set(-1);
            cachedTotalSize.set(-1);

            return new BlobRef(name, uuid);

        } catch (IOException e) {
            throw new MegaRepoException("Failed to store blob in S3", e);
        } catch (NoSuchAlgorithmException e) {
            throw new MegaRepoException("Missing digest algorithm", e);
        } catch (software.amazon.awssdk.core.exception.SdkException e) {
            throw new MegaRepoException("S3 error while storing blob: " + e.getMessage(), e);
        }
    }

    @Override
    public Optional<Blob> get(BlobRef ref) {
        String bytesKey = config.resolveKey(ref.blobId(), BYTES_SUFFIX);
        String propsKey = config.resolveKey(ref.blobId(), PROPERTIES_SUFFIX);

        try {
            // Read properties first
            GetObjectRequest propsRequest = GetObjectRequest.builder()
                    .bucket(config.bucket())
                    .key(propsKey)
                    .build();
            byte[] propsBytes;
            try (InputStream propsStream = s3Client.getObject(propsRequest)) {
                propsBytes = propsStream.readAllBytes();
            }
            BlobProperties properties = objectMapper.readValue(propsBytes, BlobProperties.class);

            // Stream blob bytes
            GetObjectRequest bytesRequest = GetObjectRequest.builder()
                    .bucket(config.bucket())
                    .key(bytesKey)
                    .build();
            InputStream blobStream = s3Client.getObject(bytesRequest);

            return Optional.of(new Blob(ref, blobStream, properties));

        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (IOException e) {
            throw new MegaRepoException("Failed to read blob from S3", e);
        } catch (software.amazon.awssdk.core.exception.SdkException e) {
            throw new MegaRepoException("S3 error while reading blob: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean delete(BlobRef ref) {
        String bytesKey = config.resolveKey(ref.blobId(), BYTES_SUFFIX);
        String propsKey = config.resolveKey(ref.blobId(), PROPERTIES_SUFFIX);

        try {
            if (!exists(ref)) {
                return false;
            }

            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(config.bucket())
                    .key(bytesKey)
                    .build());
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(config.bucket())
                    .key(propsKey)
                    .build());

            // Invalidate metrics cache
            cachedBlobCount.set(-1);
            cachedTotalSize.set(-1);

            return true;

        } catch (software.amazon.awssdk.core.exception.SdkException e) {
            throw new MegaRepoException("S3 error while deleting blob: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean exists(BlobRef ref) {
        String bytesKey = config.resolveKey(ref.blobId(), BYTES_SUFFIX);

        try {
            s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(config.bucket())
                    .key(bytesKey)
                    .build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (software.amazon.awssdk.core.exception.SdkException e) {
            throw new MegaRepoException("S3 error while checking blob existence: " + e.getMessage(), e);
        }
    }

    @Override
    public BlobStoreMetrics getMetrics() {
        long count = cachedBlobCount.get();
        long totalSize = cachedTotalSize.get();

        if (count < 0 || totalSize < 0) {
            count = 0;
            totalSize = 0;

            try {
                String prefix = config.prefix() != null ? config.prefix() : "";
                ListObjectsV2Request.Builder requestBuilder = ListObjectsV2Request.builder()
                        .bucket(config.bucket())
                        .prefix(prefix);

                var response = s3Client.listObjectsV2(requestBuilder.build());

                for (S3Object object : response.contents()) {
                    if (object.key().endsWith(BYTES_SUFFIX)) {
                        count++;
                        totalSize += object.size();
                    }
                }

                // Handle pagination
                while (response.isTruncated()) {
                    response = s3Client.listObjectsV2(requestBuilder
                            .continuationToken(response.nextContinuationToken())
                            .build());
                    for (S3Object object : response.contents()) {
                        if (object.key().endsWith(BYTES_SUFFIX)) {
                            count++;
                            totalSize += object.size();
                        }
                    }
                }

                cachedBlobCount.set(count);
                cachedTotalSize.set(totalSize);
            } catch (software.amazon.awssdk.core.exception.SdkException e) {
                // Return zeros if S3 is unreachable
                return new BlobStoreMetrics(0, 0, null);
            }
        }

        // S3 has no concept of "available space"
        return new BlobStoreMetrics(count, totalSize, null);
    }

    @Override
    public void compact() {
        // No-op: S3 has no soft-delete mechanism
    }
}
