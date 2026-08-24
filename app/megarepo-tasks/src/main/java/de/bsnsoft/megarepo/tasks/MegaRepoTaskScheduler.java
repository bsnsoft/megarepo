package de.bsnsoft.megarepo.tasks;

import de.bsnsoft.megarepo.database.entity.ScheduledTaskEntity;
import de.bsnsoft.megarepo.database.repository.ScheduledTaskJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Drives the {@code scheduled_tasks} table: every pass initialises the next run of tasks that
 * do not have one yet, and executes the tasks that have become due.
 *
 * <p>A task row only carries a {@code next_run} once it has been scheduled. Rows created by a
 * Flyway seed migration start out with {@code next_run = NULL}, so without the initialisation
 * step below they would stay invisible to the scheduler forever — they were only ever picked up
 * after somebody triggered them manually once. Initialising here (rather than in a one-off
 * migration) also covers task rows added by future migrations and tasks that are enabled later.
 */
@Component
public class MegaRepoTaskScheduler {

    private static final Logger log = LoggerFactory.getLogger(MegaRepoTaskScheduler.class);

    private final ScheduledTaskJpaRepository taskRepository;
    private final TaskRunner taskRunner;

    /** Tasks whose cron expression could not be parsed; used to warn once instead of every pass. */
    private final Set<UUID> unschedulable = ConcurrentHashMap.newKeySet();

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
            if (task.getNextRun() == null) {
                // Never scheduled yet (seeded row, or freshly enabled). Give it its first slot
                // from the cron expression instead of firing it right away: that keeps tasks
                // spread over their configured times rather than stampeding on startup.
                scheduleFirstRun(task);
                continue;
            }
            if (!task.getNextRun().isAfter(now)) {
                log.info("Task '{}' is due, submitting for execution", task.getName());
                try {
                    taskRunner.runTask(task.getId());
                } catch (Exception e) {
                    log.error("Failed to run scheduled task '{}': {}", task.getName(), e.getMessage(), e);
                }
            }
        }
    }

    /**
     * Gives an enabled task without a {@code next_run} its first slot. Tasks without a cron
     * expression stay unscheduled on purpose — they are manual-trigger only.
     */
    private void scheduleFirstRun(ScheduledTaskEntity task) {
        var cron = task.getCronExpression();
        if (cron == null || cron.isBlank()) {
            return;
        }
        if (!CronExpression.isValidExpression(cron)) {
            if (unschedulable.add(task.getId())) {
                log.warn("Task '{}' has an invalid cron expression '{}' and will not run automatically",
                        task.getName(), cron);
            }
            return;
        }
        var next = taskRunner.calculateNextRun(cron).orElse(null);
        if (next == null) {
            return;
        }
        task.setNextRun(next);
        taskRepository.save(task);
        unschedulable.remove(task.getId());
        log.info("Task '{}' had no next run yet, scheduled for {}", task.getName(), next);
    }
}
