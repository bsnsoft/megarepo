package de.bsnsoft.megarepo.rest.controller;

import de.bsnsoft.megarepo.core.exception.NotFoundException;
import de.bsnsoft.megarepo.database.entity.ScheduledTaskEntity;
import de.bsnsoft.megarepo.database.repository.ScheduledTaskJpaRepository;
import de.bsnsoft.megarepo.rest.dto.task.CreateTaskRequest;
import de.bsnsoft.megarepo.rest.dto.task.TaskXO;
import de.bsnsoft.megarepo.tasks.TaskService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tasks")
public class TaskController {

    private final ScheduledTaskJpaRepository scheduledTaskJpaRepository;
    private final TaskService taskService;

    public TaskController(ScheduledTaskJpaRepository scheduledTaskJpaRepository, TaskService taskService) {
        this.scheduledTaskJpaRepository = scheduledTaskJpaRepository;
        this.taskService = taskService;
    }

    @GetMapping
    public ResponseEntity<List<TaskXO>> list() {
        var tasks = scheduledTaskJpaRepository.findAll().stream()
                .map(this::toXO)
                .toList();
        return ResponseEntity.ok(tasks);
    }

    @GetMapping("/{id}")
    public ResponseEntity<TaskXO> get(@PathVariable UUID id) {
        var entity = scheduledTaskJpaRepository
                .findById(id)
                .orElseThrow(() -> new NotFoundException("Task not found: " + id));
        return ResponseEntity.ok(toXO(entity));
    }

    @PostMapping
    public ResponseEntity<TaskXO> create(@Valid @RequestBody CreateTaskRequest request) {
        ScheduledTaskEntity entity = new ScheduledTaskEntity();
        entity.setName(request.name());
        entity.setType(request.type());
        entity.setCronExpression(request.cronExpression());
        entity.setEnabled(request.enabled());
        entity.setConfig(new HashMap<>());
        entity.setCurrentState("WAITING");
        entity.setCreatedAt(Instant.now());

        ScheduledTaskEntity saved = scheduledTaskJpaRepository.save(entity);
        return ResponseEntity.created(URI.create("/api/v1/tasks/" + saved.getId()))
                .body(toXO(saved));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        if (!scheduledTaskJpaRepository.existsById(id)) {
            throw new NotFoundException("Task not found: " + id);
        }
        scheduledTaskJpaRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/run")
    public ResponseEntity<Void> run(@PathVariable UUID id) {
        taskService.triggerTask(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/stop")
    public ResponseEntity<Void> stop(@PathVariable UUID id) {
        taskService.stopTask(id);
        return ResponseEntity.noContent().build();
    }

    private TaskXO toXO(ScheduledTaskEntity entity) {
        return new TaskXO(
                entity.getId(),
                entity.getName(),
                entity.getType(),
                entity.getCronExpression(),
                entity.getConfig(),
                entity.isEnabled(),
                entity.getCurrentState(),
                entity.getLastRun(),
                entity.getLastRunResult(),
                entity.getNextRun(),
                entity.getMessage());
    }
}
