package de.bsnsoft.megarepo.tasks;

import de.bsnsoft.megarepo.database.entity.ScheduledTaskEntity;
import de.bsnsoft.megarepo.database.repository.ScheduledTaskJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MegaRepoTaskSchedulerTest {

    @Mock
    private ScheduledTaskJpaRepository taskRepository;

    private TaskRunner taskRunner;
    private MegaRepoTaskScheduler scheduler;

    @BeforeEach
    void setUp() {
        // Real runner (its cron arithmetic is under test), spied so runs can be intercepted.
        taskRunner = spy(new TaskRunner(taskRepository));
        scheduler = new MegaRepoTaskScheduler(taskRepository, taskRunner);
    }

    /**
     * The bug: Flyway-seeded task rows carry next_run = NULL, so the scheduler skipped them
     * forever and they never ran automatically (osTicket #155155).
     */
    @Test
    void seededTaskWithoutNextRun_getsItsFirstRunScheduled() {
        var task = createTask("NVD firewall sync", "security.nvd.sync", "0 0 3 * * ?");
        task.setNextRun(null);
        when(taskRepository.findAll()).thenReturn(List.of(task));

        scheduler.checkAndRunDueTasks();

        assertNotNull(task.getNextRun(), "seeded task must be given a next run");
        assertTrue(task.getNextRun().isAfter(Instant.now()), "next run must lie in the future");
        verify(taskRepository).save(task);
    }

    @Test
    void seededTaskWithoutNextRun_isNotRunImmediately() {
        var task = createTask("Cleanup repositories", "repository.cleanup", "0 0 1 * * ?");
        task.setNextRun(null);
        when(taskRepository.findAll()).thenReturn(List.of(task));

        scheduler.checkAndRunDueTasks();

        // Scheduling, not stampeding: several long-dormant tasks must not all fire at once
        // on the first pass after an upgrade.
        verify(taskRunner, never()).runTask(any());
    }

    @Test
    void scheduledTaskRunsOnceItsSlotHasPassed() {
        var task = createTask("Purge negative cache", "proxy.negative-cache.purge", "0 */15 * * * ?");
        task.setNextRun(Instant.now().minus(1, ChronoUnit.MINUTES));
        when(taskRepository.findAll()).thenReturn(List.of(task));
        doNothing().when(taskRunner).runTask(any());

        scheduler.checkAndRunDueTasks();

        verify(taskRunner).runTask(task.getId());
    }

    @Test
    void taskWithFutureNextRun_isNotRun() {
        var task = createTask("Compact blob store", "blobstore.compact", "0 0 2 * * ?");
        task.setNextRun(Instant.now().plus(1, ChronoUnit.HOURS));
        when(taskRepository.findAll()).thenReturn(List.of(task));

        scheduler.checkAndRunDueTasks();

        verify(taskRunner, never()).runTask(any());
        verify(taskRepository, never()).save(any());
    }

    /**
     * Manual-only tasks have no cron expression. A missing next_run must not be read as
     * "due right now", otherwise such a task would fire on every single scheduler pass.
     */
    @Test
    void taskWithoutCronExpression_isNeitherScheduledNorRun() {
        var task = createTask("Manual only", "manual.type", null);
        task.setNextRun(null);
        when(taskRepository.findAll()).thenReturn(List.of(task));

        scheduler.checkAndRunDueTasks();

        assertNull(task.getNextRun());
        verify(taskRunner, never()).runTask(any());
        verify(taskRepository, never()).save(any());
    }

    @Test
    void disabledTaskWithoutNextRun_isNotScheduled() {
        var task = createTask("Disabled", "repository.cleanup", "0 0 1 * * ?");
        task.setNextRun(null);
        task.setEnabled(false);
        when(taskRepository.findAll()).thenReturn(List.of(task));

        scheduler.checkAndRunDueTasks();

        assertNull(task.getNextRun());
        verify(taskRepository, never()).save(any());
        verify(taskRunner, never()).runTask(any());
    }

    @Test
    void runningTaskWithoutNextRun_isLeftAlone() {
        var task = createTask("Running", "repository.cleanup", "0 0 1 * * ?");
        task.setNextRun(null);
        task.setCurrentState("RUNNING");
        when(taskRepository.findAll()).thenReturn(List.of(task));

        scheduler.checkAndRunDueTasks();

        assertNull(task.getNextRun());
        verify(taskRepository, never()).save(any());
        verify(taskRunner, never()).runTask(any());
    }

    @Test
    void taskWithBrokenCronExpression_isSkippedWithoutFailingThePass() {
        var broken = createTask("Broken", "repository.cleanup", "definitely not a cron");
        broken.setNextRun(null);
        var due = createTask("Due", "proxy.negative-cache.purge", "0 */15 * * * ?");
        due.setNextRun(Instant.now().minus(1, ChronoUnit.MINUTES));
        when(taskRepository.findAll()).thenReturn(List.of(broken, due));
        doNothing().when(taskRunner).runTask(any());

        scheduler.checkAndRunDueTasks();
        scheduler.checkAndRunDueTasks();

        assertNull(broken.getNextRun());
        // The broken row must not stop the rest of the pass.
        verify(taskRunner, never()).runTask(broken.getId());
        verify(taskRunner, times(2)).runTask(due.getId());
    }

    /** All cron expressions seeded by the Flyway migrations must be parseable by Spring. */
    @Test
    void seededCronExpressionsAreSchedulable() {
        for (var cron : List.of("0 0 1 * * ?", "0 0 2 * * ?", "0 */15 * * * ?", "0 0 3 * * ?")) {
            var task = createTask("Seeded", "some.type", cron);
            task.setNextRun(null);
            when(taskRepository.findAll()).thenReturn(List.of(task));

            scheduler.checkAndRunDueTasks();

            assertNotNull(task.getNextRun(), "cron '" + cron + "' must yield a next run");
        }
    }

    private ScheduledTaskEntity createTask(String name, String type, String cron) {
        var task = new ScheduledTaskEntity();
        task.setId(UUID.randomUUID());
        task.setName(name);
        task.setType(type);
        task.setCronExpression(cron);
        task.setEnabled(true);
        task.setCurrentState("WAITING");
        task.setCreatedAt(Instant.now());
        return task;
    }
}
