package de.bsnsoft.megarepo.tasks;

import de.bsnsoft.megarepo.core.exception.NotFoundException;
import de.bsnsoft.megarepo.database.entity.ScheduledTaskEntity;
import de.bsnsoft.megarepo.database.repository.ScheduledTaskJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskRunnerTest {

    @Mock
    private ScheduledTaskJpaRepository taskRepository;

    private TaskRunner taskRunner;

    @BeforeEach
    void setUp() {
        taskRunner = new TaskRunner(taskRepository);
    }

    @Test
    void runTask_updatesStateToRunningThenWaiting() {
        var taskId = UUID.randomUUID();
        var task = createTask(taskId, "test.type", "0 0 * * * *");
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

        // Capture the state at each save call (same object is mutated)
        List<String> stateSnapshots = new ArrayList<>();
        when(taskRepository.save(any(ScheduledTaskEntity.class))).thenAnswer(invocation -> {
            var saved = invocation.getArgument(0, ScheduledTaskEntity.class);
            stateSnapshots.add(saved.getCurrentState());
            return saved;
        });

        taskRunner.registerHandler("test.type", () -> {});
        taskRunner.runTask(taskId);

        assertEquals(2, stateSnapshots.size());
        assertEquals("RUNNING", stateSnapshots.get(0));
        assertEquals("WAITING", stateSnapshots.get(1));
    }

    @Test
    void runTask_recordsLastRunTimestamp() {
        var taskId = UUID.randomUUID();
        var task = createTask(taskId, "test.type", null);
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(taskRepository.save(any())).thenReturn(task);

        taskRunner.registerHandler("test.type", () -> {});
        taskRunner.runTask(taskId);

        var captor = ArgumentCaptor.forClass(ScheduledTaskEntity.class);
        verify(taskRepository, atLeast(1)).save(captor.capture());

        var lastSave = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertNotNull(lastSave.getLastRun());
        assertEquals("OK", lastSave.getLastRunResult());
    }

    @Test
    void runTask_unknownTaskType_recordsError() {
        var taskId = UUID.randomUUID();
        var task = createTask(taskId, "unknown.type", null);
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(taskRepository.save(any())).thenReturn(task);

        taskRunner.runTask(taskId);

        var captor = ArgumentCaptor.forClass(ScheduledTaskEntity.class);
        verify(taskRepository, atLeast(1)).save(captor.capture());

        var lastSave = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertEquals("ERROR", lastSave.getLastRunResult());
        assertEquals("WAITING", lastSave.getCurrentState());
        assertNotNull(lastSave.getMessage());
    }

    @Test
    void runTask_taskNotFound_throwsException() {
        var taskId = UUID.randomUUID();
        when(taskRepository.findById(taskId)).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> taskRunner.runTask(taskId));
    }

    private ScheduledTaskEntity createTask(UUID id, String type, String cron) {
        var task = new ScheduledTaskEntity();
        task.setId(id);
        task.setName("Test Task");
        task.setType(type);
        task.setCronExpression(cron);
        task.setEnabled(true);
        task.setCurrentState("WAITING");
        task.setCreatedAt(Instant.now());
        return task;
    }
}
