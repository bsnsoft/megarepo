package de.bsnsoft.megarepo.rest.controller;

import de.bsnsoft.megarepo.core.exception.NotFoundException;
import de.bsnsoft.megarepo.core.exception.ValidationException;
import de.bsnsoft.megarepo.database.entity.AssetEntity;
import de.bsnsoft.megarepo.database.entity.ComponentEntity;
import de.bsnsoft.megarepo.database.entity.GroupMemberEntity;
import de.bsnsoft.megarepo.database.entity.RepositoryEntity;
import de.bsnsoft.megarepo.database.repository.AssetJpaRepository;
import de.bsnsoft.megarepo.database.repository.ComponentJpaRepository;
import de.bsnsoft.megarepo.database.repository.GroupMemberJpaRepository;
import de.bsnsoft.megarepo.database.repository.RepositoryJpaRepository;
import de.bsnsoft.megarepo.repository.ComponentService;
import de.bsnsoft.megarepo.rest.dto.common.PageResponse;
import de.bsnsoft.megarepo.rest.dto.component.AssetXO;
import de.bsnsoft.megarepo.rest.dto.component.ComponentXO;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/components")
public class ComponentController {

    private static final int PAGE_SIZE = 50;

    private final ComponentJpaRepository componentJpaRepository;
    private final AssetJpaRepository assetJpaRepository;
    private final RepositoryJpaRepository repositoryJpaRepository;
    private final GroupMemberJpaRepository groupMemberJpaRepository;
    private final ComponentService componentService;

    public ComponentController(
            ComponentJpaRepository componentJpaRepository,
            AssetJpaRepository assetJpaRepository,
            RepositoryJpaRepository repositoryJpaRepository,
            GroupMemberJpaRepository groupMemberJpaRepository,
            ComponentService componentService) {
        this.componentJpaRepository = componentJpaRepository;
        this.assetJpaRepository = assetJpaRepository;
        this.repositoryJpaRepository = repositoryJpaRepository;
        this.groupMemberJpaRepository = groupMemberJpaRepository;
        this.componentService = componentService;
    }

    @GetMapping
    public ResponseEntity<PageResponse<ComponentXO>> list(
            @RequestParam String repository,
            @RequestParam(required = false) String continuationToken,
            @RequestParam(required = false) String filter) {
        RepositoryEntity repo = repositoryJpaRepository
                .findByName(repository)
                .orElseThrow(() -> new ValidationException("Repository not found: " + repository));

        int page = decodePage(continuationToken);
        boolean hasFilter = filter != null && !filter.isBlank();

        if ("GROUP".equals(repo.getType())) {
            return listGroupComponents(repo, page, hasFilter ? filter.trim() : null);
        }

        var pageResult = hasFilter
                ? componentJpaRepository.findByRepositoryIdAndFilter(
                        repo.getId(), filter.trim(), PageRequest.of(page, PAGE_SIZE, Sort.by("name")))
                : componentJpaRepository.findByRepositoryId(
                        repo.getId(), PageRequest.of(page, PAGE_SIZE, Sort.by("name")));

        var items = pageResult.getContent().stream()
                .map(c -> toXO(c, repo))
                .toList();

        String nextToken = pageResult.hasNext() ? encodePage(page + 1) : null;
        return ResponseEntity.ok(new PageResponse<>(items, nextToken));
    }

    private ResponseEntity<PageResponse<ComponentXO>> listGroupComponents(
            RepositoryEntity groupRepo, int page, String filter) {
        List<GroupMemberEntity> members =
                groupMemberJpaRepository.findByGroupRepoIdOrderBySortOrder(groupRepo.getId());
        List<UUID> memberRepoIds = members.stream()
                .map(GroupMemberEntity::getMemberRepoId)
                .toList();

        if (memberRepoIds.isEmpty()) {
            return ResponseEntity.ok(new PageResponse<>(List.of(), null));
        }

        // Build a lookup map of repo ID -> repo entity for the member repos
        Map<UUID, RepositoryEntity> repoById = repositoryJpaRepository.findAllById(memberRepoIds).stream()
                .collect(Collectors.toMap(RepositoryEntity::getId, Function.identity()));

        var pageResult = filter != null
                ? componentJpaRepository.findByRepositoryIdInAndFilter(
                        memberRepoIds, filter, PageRequest.of(page, PAGE_SIZE, Sort.by("name")))
                : componentJpaRepository.findByRepositoryIdIn(
                        memberRepoIds, PageRequest.of(page, PAGE_SIZE, Sort.by("name")));

        var items = pageResult.getContent().stream()
                .map(c -> {
                    RepositoryEntity memberRepo = repoById.get(c.getRepositoryId());
                    return toXO(c, memberRepo != null ? memberRepo : groupRepo);
                })
                .toList();

        String nextToken = pageResult.hasNext() ? encodePage(page + 1) : null;
        return ResponseEntity.ok(new PageResponse<>(items, nextToken));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ComponentXO> get(@PathVariable UUID id) {
        var component = componentJpaRepository
                .findById(id)
                .orElseThrow(() -> new NotFoundException("Component not found: " + id));

        RepositoryEntity repo = repositoryJpaRepository
                .findById(component.getRepositoryId())
                .orElseThrow(() -> new NotFoundException("Repository not found for component: " + id));

        return ResponseEntity.ok(toXO(component, repo));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        // Deletes the component together with all of its assets and their blobs,
        // keeping storage and database consistent (raw deleteById would orphan the
        // component's assets and leak their blobs).
        boolean deleted = componentService.deleteComponentWithAssets(id);
        if (!deleted) {
            throw new NotFoundException("Component not found: " + id);
        }
        return ResponseEntity.noContent().build();
    }

    private ComponentXO toXO(ComponentEntity entity, RepositoryEntity repo) {
        var assets = assetJpaRepository
                .findByComponentId(entity.getId(), PageRequest.of(0, 100))
                .getContent()
                .stream()
                .map(a -> toAssetXO(a, repo))
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
