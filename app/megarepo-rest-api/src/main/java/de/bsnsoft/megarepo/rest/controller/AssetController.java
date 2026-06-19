package de.bsnsoft.megarepo.rest.controller;

import de.bsnsoft.megarepo.core.exception.NotFoundException;
import de.bsnsoft.megarepo.core.exception.ValidationException;
import de.bsnsoft.megarepo.database.entity.AssetEntity;
import de.bsnsoft.megarepo.database.entity.RepositoryEntity;
import de.bsnsoft.megarepo.database.repository.AssetJpaRepository;
import de.bsnsoft.megarepo.database.repository.RepositoryJpaRepository;
import de.bsnsoft.megarepo.repository.AssetService;
import de.bsnsoft.megarepo.rest.dto.common.PageResponse;
import de.bsnsoft.megarepo.rest.dto.component.AssetXO;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Base64;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/assets")
public class AssetController {

    private static final int PAGE_SIZE = 50;

    private final AssetJpaRepository assetJpaRepository;
    private final RepositoryJpaRepository repositoryJpaRepository;
    private final AssetService assetService;

    public AssetController(
            AssetJpaRepository assetJpaRepository,
            RepositoryJpaRepository repositoryJpaRepository,
            AssetService assetService) {
        this.assetJpaRepository = assetJpaRepository;
        this.repositoryJpaRepository = repositoryJpaRepository;
        this.assetService = assetService;
    }

    @GetMapping
    public ResponseEntity<PageResponse<AssetXO>> list(
            @RequestParam String repository,
            @RequestParam(required = false) String continuationToken) {
        RepositoryEntity repo = repositoryJpaRepository
                .findByName(repository)
                .orElseThrow(() -> new ValidationException("Repository not found: " + repository));

        int page = decodePage(continuationToken);
        var pageResult =
                assetJpaRepository.findByRepositoryId(repo.getId(), PageRequest.of(page, PAGE_SIZE, Sort.by("path")));

        var items = pageResult.getContent().stream()
                .map(a -> toXO(a, repo))
                .toList();

        String nextToken = pageResult.hasNext() ? encodePage(page + 1) : null;
        return ResponseEntity.ok(new PageResponse<>(items, nextToken));
    }

    @GetMapping("/{id}")
    public ResponseEntity<AssetXO> get(@PathVariable UUID id) {
        var asset = assetJpaRepository
                .findById(id)
                .orElseThrow(() -> new NotFoundException("Asset not found: " + id));

        RepositoryEntity repo = repositoryJpaRepository
                .findById(asset.getRepositoryId())
                .orElseThrow(() -> new NotFoundException("Repository not found for asset: " + id));

        return ResponseEntity.ok(toXO(asset, repo));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        // deleteAsset removes the backing blob from storage as well as the DB row,
        // keeping storage and database consistent (raw deleteById would leak the blob).
        boolean deleted = assetService.deleteAsset(id);
        if (!deleted) {
            throw new NotFoundException("Asset not found: " + id);
        }
        return ResponseEntity.noContent().build();
    }

    private AssetXO toXO(AssetEntity asset, RepositoryEntity repo) {
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

    private int decodePage(String continuationToken) {
        if (continuationToken == null || continuationToken.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(new String(Base64.getDecoder().decode(continuationToken)));
        } catch (Exception e) {
            return 0;
        }
    }

    private String encodePage(int page) {
        return Base64.getEncoder().encodeToString(String.valueOf(page).getBytes());
    }
}
