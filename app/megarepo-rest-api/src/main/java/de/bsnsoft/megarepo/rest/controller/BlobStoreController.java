package de.bsnsoft.megarepo.rest.controller;

import de.bsnsoft.megarepo.core.exception.NotFoundException;
import de.bsnsoft.megarepo.core.exception.ValidationException;
import de.bsnsoft.megarepo.core.storage.BlobStoreType;
import de.bsnsoft.megarepo.database.entity.BlobStoreEntity;
import de.bsnsoft.megarepo.database.repository.AssetJpaRepository;
import de.bsnsoft.megarepo.database.repository.BlobStoreJpaRepository;
import de.bsnsoft.megarepo.rest.dto.blobstore.BlobStoreXO;
import de.bsnsoft.megarepo.rest.dto.blobstore.CreateFileBlobStoreRequest;
import de.bsnsoft.megarepo.rest.dto.blobstore.CreateS3BlobStoreRequest;
import de.bsnsoft.megarepo.storage.BlobStoreManager;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.net.URI;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/blobstores")
public class BlobStoreController {

    private static final Logger log = LoggerFactory.getLogger(BlobStoreController.class);

    private final BlobStoreJpaRepository blobStoreJpaRepository;
    private final AssetJpaRepository assetJpaRepository;
    private final BlobStoreManager blobStoreManager;

    public BlobStoreController(
            BlobStoreJpaRepository blobStoreJpaRepository,
            AssetJpaRepository assetJpaRepository,
            BlobStoreManager blobStoreManager) {
        this.blobStoreJpaRepository = blobStoreJpaRepository;
        this.assetJpaRepository = assetJpaRepository;
        this.blobStoreManager = blobStoreManager;
    }

    @GetMapping
    public ResponseEntity<List<BlobStoreXO>> list() {
        var stores = blobStoreJpaRepository.findAll().stream()
                .map(this::toXO)
                .toList();
        return ResponseEntity.ok(stores);
    }

    @PostMapping("/file")
    public ResponseEntity<BlobStoreXO> createFile(@Valid @RequestBody CreateFileBlobStoreRequest request) {
        if (blobStoreJpaRepository.existsById(request.name())) {
            throw new ValidationException("Blob store already exists: " + request.name());
        }

        var entity = new BlobStoreEntity();
        entity.setName(request.name());
        entity.setType("File");
        entity.setConfig(Map.of("path", request.path()));

        Instant now = Instant.now();
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);

        var saved = blobStoreJpaRepository.save(entity);

        // Register in-memory so it is usable immediately (not just after restart)
        blobStoreManager.create(saved.getName(), BlobStoreType.FILE, new HashMap<>(saved.getConfig()));
        log.info("Created file blob store '{}'", saved.getName());

        return ResponseEntity.created(URI.create("/api/v1/blobstores/" + saved.getName()))
                .body(toXO(saved));
    }

    @PostMapping("/s3")
    public ResponseEntity<BlobStoreXO> createS3(@Valid @RequestBody CreateS3BlobStoreRequest request) {
        if (blobStoreJpaRepository.existsById(request.name())) {
            throw new ValidationException("Blob store already exists: " + request.name());
        }

        var entity = new BlobStoreEntity();
        entity.setName(request.name());
        entity.setType("S3");

        Map<String, Object> config = new HashMap<>();
        config.put("bucket", request.bucket());
        config.put("region", request.region());
        config.put("accessKeyId", request.accessKeyId());
        config.put("secretAccessKey", request.secretAccessKey());
        if (request.endpoint() != null && !request.endpoint().isBlank()) {
            config.put("endpoint", request.endpoint());
        }
        if (request.prefix() != null && !request.prefix().isBlank()) {
            config.put("prefix", request.prefix());
        }
        entity.setConfig(config);

        Instant now = Instant.now();
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);

        var saved = blobStoreJpaRepository.save(entity);

        // Register in-memory so it is usable immediately (not just after restart)
        blobStoreManager.create(saved.getName(), BlobStoreType.S3, new HashMap<>(saved.getConfig()));
        log.info("Created S3 blob store '{}'", saved.getName());

        return ResponseEntity.created(URI.create("/api/v1/blobstores/" + saved.getName()))
                .body(toXO(saved));
    }

    @DeleteMapping("/{name}")
    public ResponseEntity<Void> delete(@PathVariable String name) {
        if (!blobStoreJpaRepository.existsById(name)) {
            throw new NotFoundException("Blob store not found: " + name);
        }
        blobStoreJpaRepository.deleteById(name);
        blobStoreManager.delete(name);
        log.info("Deleted blob store '{}'", name);
        return ResponseEntity.noContent().build();
    }

    private BlobStoreXO toXO(BlobStoreEntity entity) {
        long blobCount = assetJpaRepository.countByBlobStoreName(entity.getName());
        long totalSize = assetJpaRepository.sumSizeByBlobStoreName(entity.getName());

        Long availableSpace = null;
        if ("File".equals(entity.getType())) {
            Object pathObj = entity.getConfig().get("path");
            if (pathObj != null) {
                File storeDir = new File(pathObj.toString());
                if (storeDir.exists()) {
                    availableSpace = storeDir.getUsableSpace();
                }
            }
        }

        return new BlobStoreXO(entity.getName(), entity.getType(), blobCount, totalSize, availableSpace,
                entity.getConfig());
    }
}
