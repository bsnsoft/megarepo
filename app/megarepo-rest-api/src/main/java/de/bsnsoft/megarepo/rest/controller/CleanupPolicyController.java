package de.bsnsoft.megarepo.rest.controller;

import de.bsnsoft.megarepo.core.exception.NotFoundException;
import de.bsnsoft.megarepo.core.exception.ValidationException;
import de.bsnsoft.megarepo.database.entity.AssetEntity;
import de.bsnsoft.megarepo.database.entity.CleanupPolicyEntity;
import de.bsnsoft.megarepo.database.entity.RepositoryEntity;
import de.bsnsoft.megarepo.database.repository.AssetJpaRepository;
import de.bsnsoft.megarepo.database.repository.CleanupPolicyJpaRepository;
import de.bsnsoft.megarepo.database.repository.ComponentJpaRepository;
import de.bsnsoft.megarepo.database.repository.RepositoryJpaRepository;
import de.bsnsoft.megarepo.rest.dto.cleanup.CleanupPolicyXO;
import de.bsnsoft.megarepo.rest.dto.cleanup.CleanupPreviewResponse;
import de.bsnsoft.megarepo.rest.dto.cleanup.CreateCleanupPolicyRequest;
import de.bsnsoft.megarepo.rest.dto.component.AssetXO;
import de.bsnsoft.megarepo.tasks.cleanup.CleanupPolicyEvaluator;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/v1/cleanup-policies")
public class CleanupPolicyController {

    private static final int PREVIEW_PAGE_SIZE = 500;
    private static final int PREVIEW_MAX_RESULTS = 1000;

    private final CleanupPolicyJpaRepository cleanupPolicyRepository;
    private final RepositoryJpaRepository repositoryJpaRepository;
    private final AssetJpaRepository assetJpaRepository;
    private final ComponentJpaRepository componentJpaRepository;
    private final CleanupPolicyEvaluator evaluator;

    public CleanupPolicyController(
            CleanupPolicyJpaRepository cleanupPolicyRepository,
            RepositoryJpaRepository repositoryJpaRepository,
            AssetJpaRepository assetJpaRepository,
            ComponentJpaRepository componentJpaRepository,
            CleanupPolicyEvaluator evaluator) {
        this.cleanupPolicyRepository = cleanupPolicyRepository;
        this.repositoryJpaRepository = repositoryJpaRepository;
        this.assetJpaRepository = assetJpaRepository;
        this.componentJpaRepository = componentJpaRepository;
        this.evaluator = evaluator;
    }

    @GetMapping
    public ResponseEntity<List<CleanupPolicyXO>> list() {
        var policies = cleanupPolicyRepository.findAll().stream()
                .map(this::toXO)
                .toList();
        return ResponseEntity.ok(policies);
    }

    @GetMapping("/{name}")
    public ResponseEntity<CleanupPolicyXO> get(@PathVariable String name) {
        var entity = cleanupPolicyRepository
                .findById(name)
                .orElseThrow(() -> new NotFoundException("Cleanup policy not found: " + name));
        return ResponseEntity.ok(toXO(entity));
    }

    @PostMapping
    public ResponseEntity<CleanupPolicyXO> create(@Valid @RequestBody CreateCleanupPolicyRequest request) {
        if (cleanupPolicyRepository.existsById(request.name())) {
            throw new ValidationException("Cleanup policy already exists: " + request.name());
        }

        var entity = new CleanupPolicyEntity();
        entity.setName(request.name());
        entity.setFormat(request.format());
        entity.setNotes(request.notes());
        entity.setCriteria(request.criteria());
        entity.setCreatedAt(Instant.now());

        var saved = cleanupPolicyRepository.save(entity);
        return ResponseEntity.created(URI.create("/api/v1/cleanup-policies/" + saved.getName()))
                .body(toXO(saved));
    }

    @PutMapping("/{name}")
    public ResponseEntity<CleanupPolicyXO> update(
            @PathVariable String name, @Valid @RequestBody CreateCleanupPolicyRequest request) {
        var entity = cleanupPolicyRepository
                .findById(name)
                .orElseThrow(() -> new NotFoundException("Cleanup policy not found: " + name));

        entity.setFormat(request.format());
        entity.setNotes(request.notes());
        entity.setCriteria(request.criteria());

        var saved = cleanupPolicyRepository.save(entity);
        return ResponseEntity.ok(toXO(saved));
    }

    @DeleteMapping("/{name}")
    public ResponseEntity<Void> delete(@PathVariable String name) {
        if (!cleanupPolicyRepository.existsById(name)) {
            throw new NotFoundException("Cleanup policy not found: " + name);
        }
        cleanupPolicyRepository.deleteById(name);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{name}/preview")
    public ResponseEntity<CleanupPreviewResponse> preview(
            @PathVariable String name, @RequestParam String repository) {
        var policy = cleanupPolicyRepository
                .findById(name)
                .orElseThrow(() -> new NotFoundException("Cleanup policy not found: " + name));

        var repo = repositoryJpaRepository
                .findByName(repository)
                .orElseThrow(() -> new NotFoundException("Repository not found: " + repository));

        List<AssetXO> assetsToDelete = new ArrayList<>();
        long totalSize = 0;
        int page = 0;

        while (assetsToDelete.size() < PREVIEW_MAX_RESULTS) {
            var assetPage = assetJpaRepository.findByRepositoryId(repo.getId(), PageRequest.of(page, PREVIEW_PAGE_SIZE));
            if (assetPage.isEmpty()) {
                break;
            }

            List<AssetEntity> candidates = assetPage.getContent();

            // Apply retainNVersions filtering if present
            List<AssetEntity> markedForDeletion =
                    evaluator.evaluateForDeletion(policy, candidates, repo.getId(), componentJpaRepository);

            for (var asset : markedForDeletion) {
                if (assetsToDelete.size() >= PREVIEW_MAX_RESULTS) {
                    break;
                }
                assetsToDelete.add(toAssetXO(asset, repo));
                totalSize += asset.getSize() != null ? asset.getSize() : 0L;
            }

            if (!assetPage.hasNext()) {
                break;
            }
            page++;
        }

        return ResponseEntity.ok(new CleanupPreviewResponse(assetsToDelete, totalSize, assetsToDelete.size()));
    }

    private CleanupPolicyXO toXO(CleanupPolicyEntity entity) {
        return new CleanupPolicyXO(entity.getName(), entity.getFormat(), entity.getNotes(), entity.getCriteria());
    }

    private AssetXO toAssetXO(AssetEntity asset, RepositoryEntity repo) {
        String downloadUrl = "/repository/" + repo.getName() + "/" + asset.getPath();
        return new AssetXO(
                asset.getId(),
                downloadUrl,
                asset.getPath(),
                repo.getName(),
                asset.getFormat(),
                asset.getChecksumMd5(),
                asset.getChecksumSha1(),
                asset.getChecksumSha256(),
                asset.getChecksumSha512(),
                asset.getContentType(),
                asset.getLastModified(),
                asset.getLastDownloaded(),
                asset.getSize() != null ? asset.getSize() : 0L);
    }
}
