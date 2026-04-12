package de.bsnsoft.megarepo.tasks;

import de.bsnsoft.megarepo.core.exception.NotFoundException;
import de.bsnsoft.megarepo.database.entity.ScheduledTaskEntity;
import de.bsnsoft.megarepo.database.repository.ScheduledTaskJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskServiceTest {

    @Mock
    private ScheduledTaskJpaRepository taskRepository;

    @Mock
    private TaskRunner taskRunner;

    private TaskService taskService;

    @BeforeEach
    void setUp() {
        taskService = new TaskService(taskRepository, taskRunner);
    }

    @Test
    void listTasks_returnsAll() {
        var task1 = createTask(UUID.randomUUID(), "cleanup");
        var task2 = createTask(UUID.randomUUID(), "compact");
        when(taskRepository.findAll()).thenReturn(List.of(task1, task2));

        var result = taskService.listTasks();

        assertEquals(2, result.size());
    }

    @Test
    void getTask_returnsById() {
        var taskId = UUID.randomUUID();
        var task = createTask(taskId, "cleanup");
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

        var result = taskService.getTask(taskId);

        assertTrue(result.isPresent());
        assertEquals(taskId, result.get().getId());
    }

    @Test
    void triggerTask_runsImmediately() {
        var taskId = UUID.randomUUID();
        var task = createTask(taskId, "cleanup");
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

        taskService.triggerTask(taskId);

        verify(taskRunner).runTask(taskId);
    }

    @Test
    void triggerTask_notFound_throwsException() {
        var taskId = UUID.randomUUID();
        when(taskRepository.findById(taskId)).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> taskService.triggerTask(taskId));
    }

    @Test
    void stopTask_setsStateToWaiting() {
        var taskId = UUID.randomUUID();
        var task = createTask(taskId, "cleanup");
        task.setCurrentState("RUNNING");
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

        taskService.stopTask(taskId);

        assertEquals("WAITING", task.getCurrentState());
        verify(taskRepository).save(task);
    }

    private ScheduledTaskEntity createTask(UUID id, String type) {
        var task = new ScheduledTaskEntity();
        task.setId(id);
        task.setName("Test " + type);
        task.setType(type);
        task.setEnabled(true);
        task.setCurrentState("WAITING");
        task.setCreatedAt(Instant.now());
        return task;
    }
}
