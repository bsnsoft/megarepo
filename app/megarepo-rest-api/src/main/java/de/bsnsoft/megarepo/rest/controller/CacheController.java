package de.bsnsoft.megarepo.rest.controller;

import de.bsnsoft.megarepo.core.exception.NotFoundException;
import de.bsnsoft.megarepo.core.exception.ValidationException;
import de.bsnsoft.megarepo.core.repository.RepositoryConfig;
import de.bsnsoft.megarepo.core.repository.RepositoryConfigService;
import de.bsnsoft.megarepo.database.entity.AssetEntity;
import de.bsnsoft.megarepo.repository.proxy.CacheService;
import de.bsnsoft.megarepo.repository.proxy.CacheService.CacheInfo;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.regex.PatternSyntaxException;

@RestController
@RequestMapping("/api/v1/repositories/{name}/cache")
public class CacheController {

    private final RepositoryConfigService repositoryConfigService;
    private final CacheService cacheService;

    public CacheController(RepositoryConfigService repositoryConfigService, CacheService cacheService) {
        this.repositoryConfigService = repositoryConfigService;
        this.cacheService = cacheService;
    }

    @GetMapping
    public ResponseEntity<CacheInfo> getCacheInfo(@PathVariable String name) {
        RepositoryConfig repo = findProxyRepo(name);
        CacheInfo info = cacheService.getCacheInfo(repo);
        return ResponseEntity.ok(info);
    }

    @GetMapping("/assets")
    public ResponseEntity<Page<CachedAssetXO>> getCachedAssets(
            @PathVariable String name,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        RepositoryConfig repo = findProxyRepo(name);
        // Cap page size to prevent excessive memory usage
        if (size < 1) {
            size = 50;
        }
        if (size > 200) {
            size = 200;
        }
        if (page < 0) {
            page = 0;
        }
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "lastModified"));
        Page<AssetEntity> assets = cacheService.getCachedAssets(repo.id(), pageable);
        Page<CachedAssetXO> result = assets.map(CacheController::toXO);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/invalidate")
    public ResponseEntity<InvalidateResponse> invalidateAll(@PathVariable String name) {
        RepositoryConfig repo = findProxyRepo(name);
        int count = cacheService.invalidateAll(repo);
        return ResponseEntity.ok(new InvalidateResponse(count, "All cached artifacts invalidated"));
    }

    @PostMapping("/invalidate/asset")
    public ResponseEntity<InvalidateResponse> invalidateAsset(
            @PathVariable String name, @RequestBody InvalidateAssetRequest request) {
        RepositoryConfig repo = findProxyRepo(name);
        if (request.path() == null || request.path().isBlank()) {
            throw new ValidationException("Path must not be empty");
        }
        boolean deleted = cacheService.invalidateAsset(repo, request.path());
        if (!deleted) {
            throw new NotFoundException("Cached asset not found: " + request.path());
        }
        return ResponseEntity.ok(new InvalidateResponse(1, "Cached asset invalidated: " + request.path()));
    }

    @PostMapping("/invalidate/pattern")
    public ResponseEntity<InvalidateResponse> invalidateByPattern(
            @PathVariable String name, @RequestBody InvalidatePatternRequest request) {
        RepositoryConfig repo = findProxyRepo(name);
        if (request.pattern() == null || request.pattern().isBlank()) {
            throw new ValidationException("Pattern must not be empty");
        }
        try {
            java.util.regex.Pattern.compile(request.pattern());
        } catch (PatternSyntaxException e) {
            throw new ValidationException(
                    "Invalid regex pattern '%s': %s".formatted(request.pattern(), e.getMessage()));
        }
        int count = cacheService.invalidateByPattern(repo, request.pattern());
        return ResponseEntity.ok(
                new InvalidateResponse(count, "Invalidated %d assets matching pattern".formatted(count)));
    }

    private RepositoryConfig findProxyRepo(String name) {
        RepositoryConfig repo = repositoryConfigService
                .getRepository(name)
                .orElseThrow(() -> new NotFoundException("Repository not found: " + name));
        if (!"PROXY".equals(repo.type().name())) {
            throw new ValidationException("Cache management is only supported for proxy repositories");
        }
        return repo;
    }

    private static CachedAssetXO toXO(AssetEntity entity) {
        return new CachedAssetXO(
                entity.getPath(),
                entity.getContentType(),
                entity.getSize(),
                entity.getLastModified(),
                entity.getLastDownloaded(),
                entity.getCreatedAt());
    }

    public record CachedAssetXO(
            String path,
            String contentType,
            Long size,
            Instant lastModified,
            Instant lastDownloaded,
            Instant cachedAt) {}

    public record InvalidateResponse(int count, String message) {}

    public record InvalidateAssetRequest(String path) {}

    public record InvalidatePatternRequest(String pattern) {}
}
