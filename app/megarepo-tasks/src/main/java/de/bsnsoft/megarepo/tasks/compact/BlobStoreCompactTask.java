package de.bsnsoft.megarepo.tasks.compact;

import de.bsnsoft.megarepo.storage.BlobStoreManager;
import de.bsnsoft.megarepo.tasks.TaskRunner;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class BlobStoreCompactTask {

    private static final Logger log = LoggerFactory.getLogger(BlobStoreCompactTask.class);
    private static final String TASK_TYPE = "blobstore.compact";

    private final BlobStoreManager blobStoreManager;
    private final TaskRunner taskRunner;

    public BlobStoreCompactTask(BlobStoreManager blobStoreManager, TaskRunner taskRunner) {
        this.blobStoreManager = blobStoreManager;
        this.taskRunner = taskRunner;
    }

    @PostConstruct
    void register() {
        taskRunner.registerHandler(TASK_TYPE, this::execute);
    }

    public void execute() {
        log.info("Starting blob store compaction for all stores");
        var stores = blobStoreManager.list();

        for (var storeInfo : stores) {
            try {
                log.info("Compacting blob store: {}", storeInfo.name());
                var store = blobStoreManager.get(storeInfo.name());
                store.compact();
                log.info("Blob store '{}' compaction complete", storeInfo.name());
            } catch (Exception e) {
                log.error("Failed to compact blob store '{}': {}", storeInfo.name(), e.getMessage(), e);
            }
        }

        log.info("Blob store compaction finished for {} stores", stores.size());
    }
}
