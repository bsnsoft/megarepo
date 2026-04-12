package de.bsnsoft.megarepo.tasks;

import de.bsnsoft.megarepo.core.exception.NotFoundException;
import de.bsnsoft.megarepo.database.entity.ScheduledTaskEntity;
import de.bsnsoft.megarepo.database.repository.ScheduledTaskJpaRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class TaskService {

    private final ScheduledTaskJpaRepository taskRepository;
    private final TaskRunner taskRunner;

    public TaskService(ScheduledTaskJpaRepository taskRepository, TaskRunner taskRunner) {
        this.taskRepository = taskRepository;
        this.taskRunner = taskRunner;
    }

    public List<ScheduledTaskEntity> listTasks() {
        return taskRepository.findAll();
    }

    public Optional<ScheduledTaskEntity> getTask(UUID id) {
        return taskRepository.findById(id);
    }

    public void triggerTask(UUID id) {
        var task = taskRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Scheduled task not found: " + id));
        taskRunner.runTask(task.getId());
    }

    public void stopTask(UUID id) {
        var task = taskRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Scheduled task not found: " + id));
        task.setCurrentState("WAITING");
        taskRepository.save(task);
    }
}
