package de.bsnsoft.megarepo.rest.controller;

import de.bsnsoft.megarepo.core.exception.NotFoundException;
import de.bsnsoft.megarepo.core.exception.ValidationException;
import de.bsnsoft.megarepo.core.format.FormatRegistry;
import de.bsnsoft.megarepo.core.validation.UrlSsrfValidator;
import de.bsnsoft.megarepo.database.entity.GroupMemberEntity;
import de.bsnsoft.megarepo.database.entity.RepositoryEntity;
import de.bsnsoft.megarepo.database.repository.AssetJpaRepository;
import de.bsnsoft.megarepo.database.repository.BlobStoreJpaRepository;
import de.bsnsoft.megarepo.database.repository.ComponentJpaRepository;
import de.bsnsoft.megarepo.database.repository.GroupMemberJpaRepository;
import de.bsnsoft.megarepo.database.repository.RepositoryJpaRepository;
import de.bsnsoft.megarepo.rest.dto.repository.CreateRepositoryRequest;
import de.bsnsoft.megarepo.rest.dto.repository.RepositoryXO;
import de.bsnsoft.megarepo.rest.dto.repository.UpdateRepositoryRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/repositories")
public class RepositoryController {

    private final RepositoryJpaRepository repositoryJpaRepository;
    private final BlobStoreJpaRepository blobStoreJpaRepository;
    private final GroupMemberJpaRepository groupMemberJpaRepository;
    private final ComponentJpaRepository componentJpaRepository;
    private final AssetJpaRepository assetJpaRepository;
    private final FormatRegistry formatRegistry;

    public RepositoryController(
            RepositoryJpaRepository repositoryJpaRepository,
            BlobStoreJpaRepository blobStoreJpaRepository,
            GroupMemberJpaRepository groupMemberJpaRepository,
            ComponentJpaRepository componentJpaRepository,
            AssetJpaRepository assetJpaRepository,
            FormatRegistry formatRegistry) {
        this.repositoryJpaRepository = repositoryJpaRepository;
        this.blobStoreJpaRepository = blobStoreJpaRepository;
        this.groupMemberJpaRepository = groupMemberJpaRepository;
        this.componentJpaRepository = componentJpaRepository;
        this.assetJpaRepository = assetJpaRepository;
        this.formatRegistry = formatRegistry;
    }

    @GetMapping
    public ResponseEntity<List<RepositoryXO>> list() {
        var repos = repositoryJpaRepository.findAll().stream()
                .map(this::toXO)
                .toList();
        return ResponseEntity.ok(repos);
    }

    @GetMapping("/{name}")
    public ResponseEntity<RepositoryXO> get(@PathVariable String name) {
        var entity = repositoryJpaRepository
                .findByName(name)
                .orElseThrow(() -> new NotFoundException("Repository not found: " + name));
        return ResponseEntity.ok(toXO(entity));
    }

    @PostMapping
    public ResponseEntity<RepositoryXO> create(@Valid @RequestBody CreateRepositoryRequest request) {
        if (repositoryJpaRepository.findByName(request.name()).isPresent()) {
            throw new ValidationException("Repository already exists: " + request.name());
        }

        if (!formatRegistry.getSupportedFormats().contains(request.format())) {
            throw new ValidationException("Unsupported format: " + request.format());
        }

        if (!blobStoreJpaRepository.existsById(request.blobStoreName())) {
            throw new ValidationException("Blob store not found: " + request.blobStoreName());
        }

        var attributes = normalizeAndValidateAttributes(request.type(), request.attributes());

        var entity = new RepositoryEntity();
        entity.setName(request.name());
        entity.setFormat(request.format());
        entity.setType(request.type());
        entity.setOnline(request.online());
        entity.setBlobStoreName(request.blobStoreName());
        entity.setAttributes(attributes);

        Instant now = Instant.now();
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);

        var saved = repositoryJpaRepository.save(entity);

        // Wire group members if this is a GROUP repo
        if ("GROUP".equalsIgnoreCase(request.type())) {
            wireGroupMembers(saved);
        }

        return ResponseEntity.created(URI.create("/api/v1/repositories/" + saved.getName()))
                .body(toXO(saved));
    }

    @PutMapping("/{name}")
    public ResponseEntity<RepositoryXO> update(
            @PathVariable String name, @Valid @RequestBody UpdateRepositoryRequest request) {
        var entity = repositoryJpaRepository
                .findByName(name)
                .orElseThrow(() -> new NotFoundException("Repository not found: " + name));
        entity.setOnline(request.online());
        entity.setAttributes(normalizeAndValidateAttributes(entity.getType(), request.attributes()));
        entity.setUpdatedAt(Instant.now());
        repositoryJpaRepository.save(entity);
        if ("GROUP".equalsIgnoreCase(entity.getType())) {
            wireGroupMembers(entity);
        }
        return ResponseEntity.ok(toXO(entity));
    }

    @PutMapping("/{name}/members")
    public ResponseEntity<List<String>> updateMembers(
            @PathVariable String name,
            @RequestBody List<String> memberNames) {
        if (memberNames == null) {
            throw new ValidationException("Member names list must not be null");
        }
        if (memberNames.size() > 200) {
            throw new ValidationException("Too many group members (maximum 200)");
        }
        for (String memberName : memberNames) {
            if (memberName == null || memberName.isBlank() || memberName.length() > 100) {
                throw new ValidationException("Invalid member name: must be 1-100 non-blank characters");
            }
        }

        var entity = repositoryJpaRepository
                .findByName(name)
                .orElseThrow(() -> new NotFoundException("Repository not found: " + name));

        if (!"GROUP".equalsIgnoreCase(entity.getType())) {
            throw new ValidationException("Only group repositories have members");
        }

        // Update attributes
        var attrs = new java.util.HashMap<>(entity.getAttributes());
        attrs.put("group", Map.of("memberNames", memberNames));
        entity.setAttributes(attrs);
        entity.setUpdatedAt(Instant.now());
        repositoryJpaRepository.save(entity);

        // Re-wire group members
        wireGroupMembers(entity);

        return ResponseEntity.ok(memberNames);
    }

    @GetMapping("/{name}/members")
    public ResponseEntity<List<String>> getMembers(@PathVariable String name) {
        var entity = repositoryJpaRepository
                .findByName(name)
                .orElseThrow(() -> new NotFoundException("Repository not found: " + name));

        var members = groupMemberJpaRepository.findByGroupRepoIdOrderBySortOrder(entity.getId());
        var names = members.stream()
                .map(m -> repositoryJpaRepository.findById(m.getMemberRepoId())
                        .map(RepositoryEntity::getName).orElse("unknown"))
                .toList();
        return ResponseEntity.ok(names);
    }

    @DeleteMapping("/{name}")
    public ResponseEntity<Void> delete(@PathVariable String name) {
        var entity = repositoryJpaRepository
                .findByName(name)
                .orElseThrow(() -> new NotFoundException("Repository not found: " + name));
        repositoryJpaRepository.delete(entity);
        return ResponseEntity.noContent().build();
    }

    /**
     * Normalizes and validates attributes based on repository type.
     * Accepts both nested ({@code proxy.remoteUrl}) and flat ({@code remoteUrl}) structures,
     * auto-nesting the flat form when the nested form is absent.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> normalizeAndValidateAttributes(String type, Map<String, Object> attributes) {
        var result = new java.util.HashMap<>(attributes);

        if ("PROXY".equalsIgnoreCase(type)) {
            var proxyConfig = (Map<String, Object>) result.get("proxy");
            if (proxyConfig == null || !proxyConfig.containsKey("remoteUrl")) {
                // Try to auto-nest flat remoteUrl
                Object flatUrl = result.remove("remoteUrl");
                if (flatUrl instanceof String url && !url.isBlank()) {
                    var nested = new java.util.HashMap<String, Object>();
                    if (proxyConfig != null) {
                        nested.putAll(proxyConfig);
                    }
                    nested.put("remoteUrl", url);
                    result.put("proxy", nested);
                } else if (proxyConfig != null && proxyConfig.containsKey("remoteUrl")) {
                    // Already valid — do nothing
                } else {
                    throw new ValidationException(
                            "Proxy repository requires 'attributes.proxy.remoteUrl' to be present and non-empty");
                }
            }
            // Final validation: ensure remoteUrl is non-empty
            var finalProxy = (Map<String, Object>) result.get("proxy");
            Object remoteUrl = finalProxy.get("remoteUrl");
            if (!(remoteUrl instanceof String url) || url.isBlank()) {
                throw new ValidationException(
                        "Proxy repository requires 'attributes.proxy.remoteUrl' to be present and non-empty");
            }
            // SSRF protection: reject internal/private URLs
            UrlSsrfValidator.validateUrlNotInternal(url);
        }

        if ("GROUP".equalsIgnoreCase(type)) {
            var groupConfig = (Map<String, Object>) result.get("group");
            if (groupConfig == null || !groupConfig.containsKey("memberNames")) {
                // Try to auto-nest flat memberNames
                Object flatMembers = result.remove("memberNames");
                if (flatMembers instanceof List<?> members) {
                    var nested = new java.util.HashMap<String, Object>();
                    if (groupConfig != null) {
                        nested.putAll(groupConfig);
                    }
                    nested.put("memberNames", members);
                    result.put("group", nested);
                } else if (groupConfig != null && groupConfig.containsKey("memberNames")) {
                    // Already valid — do nothing
                } else {
                    throw new ValidationException(
                            "Group repository requires 'attributes.group.memberNames' to be present");
                }
            }
            // Final validation: ensure memberNames exists
            var finalGroup = (Map<String, Object>) result.get("group");
            if (!(finalGroup.get("memberNames") instanceof List<?>)) {
                throw new ValidationException(
                        "Group repository requires 'attributes.group.memberNames' to be present");
            }
        }

        return result;
    }

    @SuppressWarnings("unchecked")
    private void wireGroupMembers(RepositoryEntity groupEntity) {
        // Clear existing members
        groupMemberJpaRepository.deleteAll(
                groupMemberJpaRepository.findByGroupRepoIdOrderBySortOrder(groupEntity.getId()));

        // Extract member names from attributes
        var attrs = groupEntity.getAttributes();
        var groupConfig = (Map<String, Object>) attrs.getOrDefault("group", Map.of());
        var memberNames = (List<String>) groupConfig.getOrDefault("memberNames", List.of());

        // Create member entries
        for (int i = 0; i < memberNames.size(); i++) {
            String memberName = memberNames.get(i);
            var memberRepo = repositoryJpaRepository.findByName(memberName);
            if (memberRepo.isPresent()) {
                var member = new GroupMemberEntity();
                member.setGroupRepoId(groupEntity.getId());
                member.setMemberRepoId(memberRepo.get().getId());
                member.setSortOrder(i);
                groupMemberJpaRepository.save(member);
            }
        }
    }

    private RepositoryXO toXO(RepositoryEntity entity) {
        String url = "/repository/" + entity.getName();

        // For group repos, include member names in attributes
        var attrs = entity.getAttributes();
        if ("GROUP".equalsIgnoreCase(entity.getType())) {
            var members = groupMemberJpaRepository.findByGroupRepoIdOrderBySortOrder(entity.getId());
            var memberNames = members.stream()
                    .map(m -> repositoryJpaRepository.findById(m.getMemberRepoId())
                            .map(RepositoryEntity::getName).orElse("unknown"))
                    .toList();
            attrs = new java.util.HashMap<>(attrs);
            attrs.put("group", Map.of("memberNames", memberNames));
        }

        long componentCount = componentJpaRepository.countByRepositoryId(entity.getId());
        long assetCount = assetJpaRepository.countByRepositoryId(entity.getId());
        Long totalSizeOrNull = assetJpaRepository.sumSizeByRepositoryId(entity.getId());
        long totalSize = totalSizeOrNull != null ? totalSizeOrNull : 0L;

        return new RepositoryXO(
                entity.getName(),
                entity.getFormat(),
                entity.getType(),
                url,
                entity.isOnline(),
                attrs,
                componentCount,
                assetCount,
                totalSize);
    }
}
