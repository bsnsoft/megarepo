package de.bsnsoft.megarepo.tasks;

import de.bsnsoft.megarepo.core.exception.NotFoundException;
import de.bsnsoft.megarepo.database.entity.ScheduledTaskEntity;
import de.bsnsoft.megarepo.database.repository.ScheduledTaskJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.scheduling.support.CronExpression;

@Service
public class TaskRunner {

    private static final Logger log = LoggerFactory.getLogger(TaskRunner.class);

    private final ScheduledTaskJpaRepository taskRepository;
    private final Map<String, Runnable> handlers = new ConcurrentHashMap<>();

    public TaskRunner(ScheduledTaskJpaRepository taskRepository) {
        this.taskRepository = taskRepository;
    }

    public void registerHandler(String taskType, Runnable handler) {
        handlers.put(taskType, handler);
        log.info("Registered task handler for type: {}", taskType);
    }

    @Transactional
    public void runTask(UUID taskId) {
        var task = taskRepository.findById(taskId)
                .orElseThrow(() -> new NotFoundException("Scheduled task not found: " + taskId));

        var handler = handlers.get(task.getType());

        task.setCurrentState("RUNNING");
        task.setMessage(null);
        taskRepository.save(task);

        try {
            if (handler == null) {
                throw new IllegalStateException("No handler registered for task type: " + task.getType());
            }
            handler.run();
            task.setLastRunResult("OK");
            task.setMessage(null);
            log.info("Task '{}' (type={}) completed successfully", task.getName(), task.getType());
        } catch (Exception e) {
            task.setLastRunResult("ERROR");
            task.setMessage(e.getMessage());
            log.error("Task '{}' (type={}) failed: {}", task.getName(), task.getType(), e.getMessage(), e);
        } finally {
            task.setCurrentState("WAITING");
            task.setLastRun(Instant.now());
            task.setNextRun(calculateNextRun(task.getCronExpression()).orElse(null));
            taskRepository.save(task);
        }
    }

    Optional<Instant> calculateNextRun(String cronExpression) {
        if (cronExpression == null || cronExpression.isBlank()) {
            return Optional.empty();
        }
        try {
            var cron = CronExpression.parse(cronExpression);
            var next = cron.next(ZonedDateTime.now(ZoneOffset.UTC));
            return next != null ? Optional.of(next.toInstant()) : Optional.empty();
        } catch (IllegalArgumentException e) {
            log.warn("Invalid cron expression '{}': {}", cronExpression, e.getMessage());
            return Optional.empty();
        }
    }

    boolean hasHandler(String taskType) {
        return handlers.containsKey(taskType);
    }
}
