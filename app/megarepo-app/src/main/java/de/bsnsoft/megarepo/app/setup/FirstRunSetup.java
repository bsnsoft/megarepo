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
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class FirstRunSetup implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(FirstRunSetup.class);

    private static final String BLOB_STORE_PATH_PREFIX = "data/blobs/";

    private final BlobStoreManager blobStoreManager;
    private final BlobStoreJpaRepository blobStoreJpaRepository;
    private final RepositoryJpaRepository repositoryJpaRepository;
    private final GroupMemberJpaRepository groupMemberJpaRepository;
    private final ServletWebServerApplicationContext webServerContext;

    public FirstRunSetup(
            BlobStoreManager blobStoreManager,
            BlobStoreJpaRepository blobStoreJpaRepository,
            RepositoryJpaRepository repositoryJpaRepository,
            GroupMemberJpaRepository groupMemberJpaRepository,
            ServletWebServerApplicationContext webServerContext) {
        this.blobStoreManager = blobStoreManager;
        this.blobStoreJpaRepository = blobStoreJpaRepository;
        this.repositoryJpaRepository = repositoryJpaRepository;
        this.groupMemberJpaRepository = groupMemberJpaRepository;
        this.webServerContext = webServerContext;
    }

    @Override
    public void run(ApplicationArguments args) {
        initializeBlobStores();
        initializeDefaultRepositories();

        int port = webServerContext.getWebServer().getPort();
        log.info("MegaRepo started on port {}", port);
    }

    private void initializeBlobStores() {
        List<BlobStoreEntity> entities = blobStoreJpaRepository.findAll();
        log.info("Initializing {} blob store(s) from database", entities.size());

        for (BlobStoreEntity entity : entities) {
            try {
                BlobStoreType type = BlobStoreType.valueOf(entity.getType().toUpperCase());
                Map<String, Object> config = new HashMap<>(entity.getConfig());
                blobStoreManager.create(entity.getName(), type, config);
                log.info("Initialized blob store '{}' (type={})", entity.getName(), entity.getType());
            } catch (Exception e) {
                log.error("Failed to initialize blob store '{}': {}", entity.getName(), e.getMessage(), e);
            }
        }
    }

    private void initializeDefaultRepositories() {
        if (repositoryJpaRepository.count() > 0) {
            return;
        }

        log.info("No repositories found — creating default repositories");

        // Maven — note: the registered format key is "maven2" (Sonatype-Nexus
        // convention), NOT "maven". Must match MavenFormatPlugin.getFormat() and
        // repo-presets/default.yml; otherwise FormatRegistry.getPlugin() throws
        // UnsupportedFormatException at request time.
        createHostedRepo("maven-releases", "maven2");
        createHostedRepo("maven-snapshots", "maven2");
        createProxyRepo("maven-central", "maven2", "https://repo1.maven.org/maven2/");
        createGroupRepo("maven-public", "maven2", List.of("maven-central", "maven-releases", "maven-snapshots"));

        // npm
        createHostedRepo("npm-hosted", "npm");
        createProxyRepo("npm-proxy", "npm", "https://registry.npmjs.org/");
        createGroupRepo("npm-public", "npm", List.of("npm-proxy", "npm-hosted"));

        // PyPI
        createHostedRepo("pypi-hosted", "pypi");
        createProxyRepo("pypi-proxy", "pypi", "https://pypi.org/simple/");
        createGroupRepo("pypi-public", "pypi", List.of("pypi-proxy", "pypi-hosted"));

        // NuGet — the proxy remoteUrl is the upstream V3 service index itself
        createHostedRepo("nuget-hosted", "nuget");
        createProxyRepo("nuget-proxy", "nuget", "https://api.nuget.org/v3/index.json");
        createGroupRepo("nuget-public", "nuget", List.of("nuget-proxy", "nuget-hosted"));

        // Raw
        createHostedRepo("raw-hosted", "raw");

        // Docker
        createHostedRepo("docker-hosted", "docker");
        createProxyRepo("docker-hub-proxy", "docker", "https://registry-1.docker.io/");
        createGroupRepo("docker-public", "docker", List.of("docker-hub-proxy", "docker-hosted"));

        log.info("Default repository setup complete");
    }

    private RepositoryEntity createRepo(String name, String format, String type, Map<String, Object> attributes) {
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
        log.info("Created default repository: {} ({}/{})", name, format, type);
        return saved;
    }

    private String ensureBlobStoreExists(String repoName) {
        Optional<BlobStoreEntity> existing = blobStoreJpaRepository.findById(repoName);
        if (existing.isPresent()) {
            return repoName;
        }

        var blobEntity = new BlobStoreEntity();
        blobEntity.setName(repoName);
        blobEntity.setType("File");
        blobEntity.setConfig(Map.of("path", BLOB_STORE_PATH_PREFIX + repoName));

        Instant now = Instant.now();
        blobEntity.setCreatedAt(now);
        blobEntity.setUpdatedAt(now);

        blobStoreJpaRepository.save(blobEntity);
        blobStoreManager.create(repoName, BlobStoreType.FILE, new HashMap<>(blobEntity.getConfig()));
        log.info("Auto-created blob store '{}' for repository", repoName);

        return repoName;
    }

    private void createHostedRepo(String name, String format) {
        createRepo(name, format, "HOSTED", Map.of());
    }

    private void createProxyRepo(String name, String format, String remoteUrl) {
        createRepo(name, format, "PROXY", Map.of("proxy", Map.of("remoteUrl", remoteUrl)));
    }

    private void createGroupRepo(String name, String format, List<String> memberNames) {
        var entity = createRepo(name, format, "GROUP", Map.of("group", Map.of("memberNames", memberNames)));
        wireGroupMembers(entity, memberNames);
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
            }
        }
    }
}
