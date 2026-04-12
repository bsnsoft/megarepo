package de.bsnsoft.megarepo.tasks.purge;

import de.bsnsoft.megarepo.repository.proxy.NegativeCacheService;
import de.bsnsoft.megarepo.tasks.TaskRunner;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class NegativeCachePurgeTask {

    private static final Logger log = LoggerFactory.getLogger(NegativeCachePurgeTask.class);
    private static final String TASK_TYPE = "proxy.negative-cache.purge";

    private final NegativeCacheService negativeCacheService;
    private final TaskRunner taskRunner;

    public NegativeCachePurgeTask(NegativeCacheService negativeCacheService, TaskRunner taskRunner) {
        this.negativeCacheService = negativeCacheService;
        this.taskRunner = taskRunner;
    }

    @PostConstruct
    void register() {
        taskRunner.registerHandler(TASK_TYPE, this::execute);
    }

    public void execute() {
        log.info("Purging expired negative cache entries");
        negativeCacheService.purgeExpired();
        log.info("Negative cache purge complete");
    }
}
