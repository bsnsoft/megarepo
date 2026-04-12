package de.bsnsoft.megarepo.tasks;

import de.bsnsoft.megarepo.database.repository.ScheduledTaskJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class MegaRepoTaskScheduler {

    private static final Logger log = LoggerFactory.getLogger(MegaRepoTaskScheduler.class);

    private final ScheduledTaskJpaRepository taskRepository;
    private final TaskRunner taskRunner;

    public MegaRepoTaskScheduler(ScheduledTaskJpaRepository taskRepository, TaskRunner taskRunner) {
        this.taskRepository = taskRepository;
        this.taskRunner = taskRunner;
    }

    @Scheduled(fixedDelay = 60_000)
    public void checkAndRunDueTasks() {
        var now = Instant.now();
        var tasks = taskRepository.findAll();

        for (var task : tasks) {
            if (!task.isEnabled()) {
                continue;
            }
            if ("RUNNING".equals(task.getCurrentState())) {
                continue;
            }
            if (task.getNextRun() != null && !task.getNextRun().isAfter(now)) {
                log.info("Task '{}' is due, submitting for execution", task.getName());
                try {
                    taskRunner.runTask(task.getId());
                } catch (Exception e) {
                    log.error("Failed to run scheduled task '{}': {}", task.getName(), e.getMessage(), e);
                }
            }
        }
    }
}
