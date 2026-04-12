package de.bsnsoft.megarepo.app.setup;

import de.bsnsoft.megarepo.core.storage.BlobStoreType;
import de.bsnsoft.megarepo.database.entity.BlobStoreEntity;
import de.bsnsoft.megarepo.database.entity.GroupMemberEntity;
import de.bsnsoft.megarepo.database.entity.RepositoryEntity;
import de.bsnsoft.megarepo.database.repository.BlobStoreJpaRepository;
import de.bsnsoft.megarepo.database.repository.GroupMemberJpaRepository;
import de.bsnsoft.megarepo.database.repository.RepositoryJpaRepository;
import de.bsnsoft.megarepo.storage.BlobStoreManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads repository definitions from YAML preset files and creates them in the database.
 * Idempotent: repositories that already exist (by name) are skipped.
 */
@Component
public class RepositoryPresetLoader {

    private static final Logger log = LoggerFactory.getLogger(RepositoryPresetLoader.class);
    private static final String BLOB_STORE_PATH_PREFIX = "data/blobs/";

    private final RepositoryJpaRepository repositoryJpaRepository;
    private final GroupMemberJpaRepository groupMemberJpaRepository;
    private final BlobStoreJpaRepository blobStoreJpaRepository;
    private final BlobStoreManager blobStoreManager;
    private final ResourceLoader resourceLoader;

    public RepositoryPresetLoader(
            RepositoryJpaRepository repositoryJpaRepository,
            GroupMemberJpaRepository groupMemberJpaRepository,
            BlobStoreJpaRepository blobStoreJpaRepository,
            BlobStoreManager blobStoreManager) {
        this.repositoryJpaRepository = repositoryJpaRepository;
        this.groupMemberJpaRepository = groupMemberJpaRepository;
        this.blobStoreJpaRepository = blobStoreJpaRepository;
        this.blobStoreManager = blobStoreManager;
        this.resourceLoader = new DefaultResourceLoader();
    }

    /**
     * Load a named preset from the classpath (repo-presets/{name}.yml).
     *
     * @return result describing what was created and skipped
     */
    public PresetLoadResult loadPreset(String presetName) {
        String resourcePath = "classpath:repo-presets/" + presetName + ".yml";
        return loadFromResource(resourcePath);
    }

    /**
     * Load repositories from a Spring resource path (classpath: or file:).
     */
    public PresetLoadResult loadFromResource(String resourcePath) {
        Resource resource = resourceLoader.getResource(resourcePath);
        try (InputStream is = resource.getInputStream()) {
            return loadFromYaml(is);
        } catch (IOException e) {
            throw new PresetLoadException("Failed to load preset from: " + resourcePath, e);
        }
    }

    /**
     * Load repositories from an external file path.
     */
    public PresetLoadResult loadFromFile(Path filePath) {
        try (InputStream is = Files.newInputStream(filePath)) {
            return loadFromYaml(is);
        } catch (IOException e) {
            throw new PresetLoadException("Failed to load preset from file: " + filePath, e);
        }
    }

    /**
     * Load repositories from a YAML string (used by the REST import endpoint).
     */
    public PresetLoadResult loadFromYamlString(String yamlContent) {
        try (InputStream is = new java.io.ByteArrayInputStream(yamlContent.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            return loadFromYaml(is);
        } catch (IOException e) {
            throw new PresetLoadException("Failed to parse YAML content", e);
        }
    }

    @SuppressWarnings("unchecked")
    private PresetLoadResult loadFromYaml(InputStream inputStream) {
        var yaml = new Yaml();
        Map<String, Object> root = yaml.load(inputStream);

        if (root == null || !root.containsKey("repositories")) {
            throw new PresetLoadException("Invalid preset YAML: missing 'repositories' key");
        }

        List<Map<String, Object>> repoDefs = (List<Map<String, Object>>) root.get("repositories");
        if (repoDefs == null || repoDefs.isEmpty()) {
            return new PresetLoadResult(List.of(), List.of());
        }

        // Separate into non-group and group repos for dependency ordering
        List<Map<String, Object>> nonGroupDefs = new ArrayList<>();
        List<Map<String, Object>> groupDefs = new ArrayList<>();

        for (Map<String, Object> def : repoDefs) {
            String type = ((String) def.get("type")).toUpperCase();
            if ("GROUP".equals(type)) {
                groupDefs.add(def);
            } else {
                nonGroupDefs.add(def);
            }
        }

        List<String> created = new ArrayList<>();
        List<String> skipped = new ArrayList<>();

        // Create HOSTED and PROXY repos first
        for (Map<String, Object> def : nonGroupDefs) {
            processRepoDef(def, created, skipped);
        }

        // Then create GROUP repos (members must exist)
        for (Map<String, Object> def : groupDefs) {
            processRepoDef(def, created, skipped);
        }

        log.info("Preset load complete: {} created, {} skipped", created.size(), skipped.size());
        return new PresetLoadResult(created, skipped);
    }

    @SuppressWarnings("unchecked")
    private void processRepoDef(Map<String, Object> def, List<String> created, List<String> skipped) {
        String name = (String) def.get("name");
        String format = (String) def.get("format");
        String type = ((String) def.get("type")).toUpperCase();
        String remoteUrl = (String) def.get("remoteUrl");
        List<String> members = (List<String>) def.get("members");

        if (name == null || format == null || type == null) {
            log.warn("Skipping invalid repo definition (missing name/format/type): {}", def);
            return;
        }

        // Idempotent: skip if already exists
        if (repositoryJpaRepository.findByName(name).isPresent()) {
            log.debug("Repository '{}' already exists, skipping", name);
            skipped.add(name);
            return;
        }

        // Build attributes
        Map<String, Object> attributes = switch (type) {
            case "PROXY" -> {
                if (remoteUrl == null || remoteUrl.isBlank()) {
                    log.warn("Proxy repo '{}' has no remoteUrl, skipping", name);
                    skipped.add(name);
                    yield null;
                }
                yield Map.of("proxy", Map.of("remoteUrl", remoteUrl));
            }
            case "GROUP" -> {
                if (members == null || members.isEmpty()) {
                    log.warn("Group repo '{}' has no members, skipping", name);
                    skipped.add(name);
                    yield null;
                }
                yield Map.of("group", Map.of("memberNames", members));
            }
            default -> Map.of();
        };

        if (attributes == null) {
            return;
        }

        // Auto-create a dedicated blob store for this repository
        String blobStoreName = ensureBlobStoreExists(name);

        var entity = new RepositoryEntity();
        entity.setName(name);
        entity.setFormat(format);
        entity.setType(type);
        entity.setOnline(true);
        entity.setBlobStoreName(blobStoreName);
        entity.setAttributes(attributes);

        Instant now = Instant.now();
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);

        var saved = repositoryJpaRepository.save(entity);
        log.info("Created repository: {} ({}/{})", name, format, type);

        // Wire group members
        if ("GROUP".equals(type) && members != null) {
            wireGroupMembers(saved, members);
        }

        created.add(name);
    }

    private void wireGroupMembers(RepositoryEntity groupEntity, List<String> memberNames) {
        for (int i = 0; i < memberNames.size(); i++) {
            String memberName = memberNames.get(i);
            var memberRepo = repositoryJpaRepository.findByName(memberName);
            if (memberRepo.isPresent()) {
                var member = new GroupMemberEntity();
                member.setGroupRepoId(groupEntity.getId());
                member.setMemberRepoId(memberRepo.get().getId());
                member.setSortOrder(i);
                groupMemberJpaRepository.save(member);
            } else {
                log.warn("Group '{}' references unknown member '{}', skipping member", groupEntity.getName(), memberName);
            }
        }
    }

    /**
     * Ensures a dedicated blob store exists for the given repository name.
     * If it doesn't exist, creates a File blob store with path data/blobs/{name}.
     *
     * @return the blob store name (same as the repo name)
     */
    private String ensureBlobStoreExists(String repoName) {
        if (blobStoreJpaRepository.findById(repoName).isPresent()) {
            log.debug("Blob store '{}' already exists, reusing", repoName);
            return repoName;
        }

        var entity = new BlobStoreEntity();
        entity.setName(repoName);
        entity.setType("File");
        entity.setConfig(Map.of("path", BLOB_STORE_PATH_PREFIX + repoName));

        Instant now = Instant.now();
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);

        blobStoreJpaRepository.save(entity);
        blobStoreManager.create(repoName, BlobStoreType.FILE, new HashMap<>(entity.getConfig()));
        log.info("Auto-created blob store '{}' for repository", repoName);

        return repoName;
    }

    /**
     * Result of loading a preset file.
     */
    public record PresetLoadResult(List<String> created, List<String> skipped) {
    }

    /**
     * Exception thrown when preset loading fails.
     */
    public static class PresetLoadException extends RuntimeException {
        public PresetLoadException(String message) {
            super(message);
        }

        public PresetLoadException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
