package de.bsnsoft.megarepo.tasks.cleanup;

import de.bsnsoft.megarepo.core.storage.BlobRef;
import de.bsnsoft.megarepo.database.entity.AssetEntity;
import de.bsnsoft.megarepo.database.entity.CleanupPolicyEntity;
import de.bsnsoft.megarepo.database.repository.AssetJpaRepository;
import de.bsnsoft.megarepo.database.repository.CleanupPolicyJpaRepository;
import de.bsnsoft.megarepo.database.repository.ComponentJpaRepository;
import de.bsnsoft.megarepo.database.repository.RepositoryJpaRepository;
import de.bsnsoft.megarepo.storage.BlobStoreManager;
import de.bsnsoft.megarepo.tasks.TaskRunner;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Component
public class CleanupTask {

    private static final Logger log = LoggerFactory.getLogger(CleanupTask.class);
    private static final String TASK_TYPE = "repository.cleanup";
    private static final int PAGE_SIZE = 500;

    private final CleanupPolicyEvaluator evaluator;
    private final CleanupPolicyJpaRepository cleanupPolicyRepository;
    private final AssetJpaRepository assetRepository;
    private final ComponentJpaRepository componentRepository;
    private final RepositoryJpaRepository repositoryRepository;
    private final BlobStoreManager blobStoreManager;
    private final JdbcTemplate jdbcTemplate;
    private final TaskRunner taskRunner;

    public CleanupTask(
            CleanupPolicyEvaluator evaluator,
            CleanupPolicyJpaRepository cleanupPolicyRepository,
            AssetJpaRepository assetRepository,
            ComponentJpaRepository componentRepository,
            RepositoryJpaRepository repositoryRepository,
            BlobStoreManager blobStoreManager,
            JdbcTemplate jdbcTemplate,
            TaskRunner taskRunner) {
        this.evaluator = evaluator;
        this.cleanupPolicyRepository = cleanupPolicyRepository;
        this.assetRepository = assetRepository;
        this.componentRepository = componentRepository;
        this.repositoryRepository = repositoryRepository;
        this.blobStoreManager = blobStoreManager;
        this.jdbcTemplate = jdbcTemplate;
        this.taskRunner = taskRunner;
    }

    @PostConstruct
    void register() {
        taskRunner.registerHandler(TASK_TYPE, this::execute);
    }

    @Transactional
    public void execute() {
        var policies = cleanupPolicyRepository.findAll();
        if (policies.isEmpty()) {
            log.info("No cleanup policies configured, skipping");
            return;
        }

        int totalDeleted = 0;
        int reposProcessed = 0;

        for (var policy : policies) {
            var repoIds = findRepositoriesForPolicy(policy.getName());

            for (var repoId : repoIds) {
                if (!repositoryRepository.existsById(repoId)) {
                    continue;
                }
                reposProcessed++;
                totalDeleted += processRepository(repoId, policy);
            }
        }

        log.info("Cleanup complete: deleted {} assets from {} repositories", totalDeleted, reposProcessed);
    }

    private int processRepository(UUID repoId, CleanupPolicyEntity policy) {
        int deleted = 0;
        int page = 0;
        var pageable = PageRequest.of(page, PAGE_SIZE);
        var assetPage = assetRepository.findByRepositoryId(repoId, pageable);

        while (!assetPage.isEmpty()) {
            var toDelete = evaluator.evaluateForDeletion(
                    policy, assetPage.getContent(), repoId, componentRepository);

            for (var asset : toDelete) {
                deleteAsset(asset);
                deleted++;
            }

            if (!assetPage.hasNext()) {
                break;
            }
            page++;
            pageable = PageRequest.of(page, PAGE_SIZE);
            assetPage = assetRepository.findByRepositoryId(repoId, pageable);
        }

        return deleted;
    }

    private void deleteAsset(AssetEntity asset) {
        if (asset.getBlobRef() != null) {
            try {
                var blobRef = BlobRef.parse(asset.getBlobRef());
                var blobStore = blobStoreManager.get(blobRef.blobStoreName());
                blobStore.delete(blobRef);
            } catch (Exception e) {
                log.warn(
                        "Failed to delete blob {} for asset {}: {}",
                        asset.getBlobRef(),
                        asset.getPath(),
                        e.getMessage());
            }
        }

        assetRepository.delete(asset);
        log.debug("Deleted asset: {} (repo={})", asset.getPath(), asset.getRepositoryId());
    }

    private List<UUID> findRepositoriesForPolicy(String policyName) {
        return jdbcTemplate.queryForList(
                "SELECT repository_id FROM repository_cleanup_policies WHERE policy_name = ?",
                UUID.class,
                policyName);
    }
}
