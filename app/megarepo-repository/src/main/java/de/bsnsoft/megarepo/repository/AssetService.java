package de.bsnsoft.megarepo.repository;

import de.bsnsoft.megarepo.core.event.AssetCreatedEvent;
import de.bsnsoft.megarepo.core.event.AssetDownloadedEvent;
import de.bsnsoft.megarepo.core.storage.Blob;
import de.bsnsoft.megarepo.core.storage.BlobRef;
import de.bsnsoft.megarepo.core.storage.BlobStore;
import de.bsnsoft.megarepo.database.entity.AssetEntity;
import de.bsnsoft.megarepo.database.repository.AssetJpaRepository;
import de.bsnsoft.megarepo.storage.BlobStoreManager;
import de.bsnsoft.megarepo.storage.MultiDigestInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class AssetService {

    private static final Logger log = LoggerFactory.getLogger(AssetService.class);

    private final AssetJpaRepository assetJpaRepository;
    private final BlobStoreManager blobStoreManager;
    private final ApplicationEventPublisher eventPublisher;

    public AssetService(
            AssetJpaRepository assetJpaRepository,
            BlobStoreManager blobStoreManager,
            ApplicationEventPublisher eventPublisher) {
        this.assetJpaRepository = assetJpaRepository;
        this.blobStoreManager = blobStoreManager;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public AssetEntity createAsset(
            UUID repoId,
            UUID componentId,
            String format,
            String path,
            InputStream content,
            String contentType,
            String createdBy,
            String createdByIp,
            String blobStoreName) {
        try {
            var digestStream = new MultiDigestInputStream(content);
            BlobStore blobStore = blobStoreManager.get(blobStoreName);
            BlobRef blobRef = blobStore.store(digestStream, Map.of("Content-Type", contentType));

            Map<String, String> checksums = digestStream.getChecksums();
            long size = digestStream.getBytesRead();

            // Check if the asset already exists and update it
            Optional<AssetEntity> existing = assetJpaRepository.findByRepositoryIdAndPath(repoId, path);
            AssetEntity asset;
            if (existing.isPresent()) {
                asset = existing.get();
                // Delete old blob
                if (asset.getBlobRef() != null) {
                    try {
                        BlobRef oldRef = BlobRef.parse(asset.getBlobRef());
                        blobStoreManager.get(oldRef.blobStoreName()).delete(oldRef);
                    } catch (Exception e) {
                        log.warn("Failed to delete old blob for asset {}: {}", asset.getId(), e.getMessage());
                    }
                }
            } else {
                asset = new AssetEntity();
                asset.setRepositoryId(repoId);
                asset.setPath(path);
                asset.setFormat(format);
                asset.setCreatedAt(Instant.now());
            }

            asset.setComponentId(componentId);
            asset.setBlobRef(blobRef.toExternalForm());
            asset.setContentType(contentType);
            asset.setSize(size);
            asset.setChecksumMd5(checksums.get("md5"));
            asset.setChecksumSha1(checksums.get("sha1"));
            asset.setChecksumSha256(checksums.get("sha256"));
            asset.setChecksumSha512(checksums.get("sha512"));
            asset.setCreatedBy(createdBy);
            asset.setCreatedByIp(createdByIp);
            asset.setLastModified(Instant.now());
            asset.setUpdatedAt(Instant.now());

            AssetEntity saved = assetJpaRepository.save(asset);
            eventPublisher.publishEvent(
                    new AssetCreatedEvent(this, saved.getId(), repoId, path));
            return saved;
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Required digest algorithm not available", e);
        }
    }

    public Optional<AssetEntity> getAsset(UUID repoId, String path) {
        return assetJpaRepository.findByRepositoryIdAndPath(repoId, path);
    }

    public Optional<Blob> getAssetContent(AssetEntity asset) {
        if (asset.getBlobRef() == null) {
            return Optional.empty();
        }
        BlobRef ref = BlobRef.parse(asset.getBlobRef());
        BlobStore blobStore = blobStoreManager.get(ref.blobStoreName());
        return blobStore.get(ref);
    }

    @Transactional
    public boolean deleteAsset(UUID assetId) {
        Optional<AssetEntity> maybeAsset = assetJpaRepository.findById(assetId);
        if (maybeAsset.isEmpty()) {
            return false;
        }
        AssetEntity asset = maybeAsset.get();
        // Delete blob
        if (asset.getBlobRef() != null) {
            try {
                BlobRef ref = BlobRef.parse(asset.getBlobRef());
                blobStoreManager.get(ref.blobStoreName()).delete(ref);
            } catch (Exception e) {
                log.warn("Failed to delete blob for asset {}: {}", assetId, e.getMessage());
            }
        }
        assetJpaRepository.delete(asset);
        return true;
    }

    @Async
    @Transactional
    public void updateLastDownloaded(UUID assetId, UUID repositoryId) {
        assetJpaRepository.findById(assetId).ifPresent(asset -> {
            asset.setLastDownloaded(Instant.now());
            assetJpaRepository.save(asset);
            eventPublisher.publishEvent(new AssetDownloadedEvent(this, assetId, repositoryId));
        });
    }
}
