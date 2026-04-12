package de.bsnsoft.megarepo.rest.controller;

import de.bsnsoft.megarepo.database.entity.ComponentEntity;
import de.bsnsoft.megarepo.database.entity.RepositoryEntity;
import de.bsnsoft.megarepo.database.repository.AssetJpaRepository;
import de.bsnsoft.megarepo.database.repository.ComponentJpaRepository;
import de.bsnsoft.megarepo.database.repository.RepositoryJpaRepository;
import de.bsnsoft.megarepo.rest.dto.common.PageResponse;
import de.bsnsoft.megarepo.rest.dto.component.AssetXO;
import de.bsnsoft.megarepo.rest.dto.component.ComponentXO;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/search")
public class SearchController {

    private static final int PAGE_SIZE = 50;

    private final ComponentJpaRepository componentJpaRepository;
    private final AssetJpaRepository assetJpaRepository;
    private final RepositoryJpaRepository repositoryJpaRepository;

    public SearchController(
            ComponentJpaRepository componentJpaRepository,
            AssetJpaRepository assetJpaRepository,
            RepositoryJpaRepository repositoryJpaRepository) {
        this.componentJpaRepository = componentJpaRepository;
        this.assetJpaRepository = assetJpaRepository;
        this.repositoryJpaRepository = repositoryJpaRepository;
    }

    @GetMapping
    public ResponseEntity<PageResponse<ComponentXO>> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String repository,
            @RequestParam(required = false) String format) {

        List<RepositoryEntity> repos;
        if (repository != null && !repository.isBlank()) {
            repos = repositoryJpaRepository
                    .findByName(repository)
                    .map(List::of)
                    .orElse(List.of());
        } else if (format != null && !format.isBlank()) {
            repos = repositoryJpaRepository.findByFormat(format);
        } else {
            repos = repositoryJpaRepository.findAll();
        }

        Map<UUID, RepositoryEntity> repoMap = repos.stream()
                .collect(Collectors.toMap(RepositoryEntity::getId, Function.identity()));

        List<ComponentXO> results = repos.stream()
                .flatMap(repo -> componentJpaRepository
                        .findByRepositoryId(repo.getId(), PageRequest.of(0, PAGE_SIZE))
                        .getContent()
                        .stream())
                .filter(c -> q == null || q.isBlank() || c.getName().toLowerCase().contains(q.toLowerCase()))
                .limit(PAGE_SIZE)
                .map(c -> toXO(c, repoMap.get(c.getRepositoryId())))
                .toList();

        return ResponseEntity.ok(new PageResponse<>(results, null));
    }

    private ComponentXO toXO(ComponentEntity entity, RepositoryEntity repo) {
        var assets = assetJpaRepository
                .findByComponentId(entity.getId(), PageRequest.of(0, 100))
                .getContent()
                .stream()
                .map(a -> new AssetXO(
                        a.getId(),
                        "/repository/" + repo.getName() + "/" + a.getPath(),
                        a.getPath(),
                        repo.getName(),
                        a.getFormat(),
                        a.getChecksumMd5(),
                        a.getChecksumSha1(),
                        a.getChecksumSha256(),
                        a.getChecksumSha512(),
                        a.getContentType(),
                        a.getLastModified(),
                        a.getLastDownloaded(),
                        a.getSize() != null ? a.getSize() : 0L))
                .toList();

        return new ComponentXO(
                entity.getId(),
                repo.getName(),
                entity.getFormat(),
                entity.getNamespace(),
                entity.getName(),
                entity.getVersion(),
                assets);
    }
}
