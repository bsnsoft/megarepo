package de.bsnsoft.megarepo.rest.dto.task;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateTaskRequest(
        @NotBlank(message = "Task name is required") @Size(max = 200) String name,
        @NotBlank(message = "Task type is required") @Size(max = 100) String type,
        @Size(max = 100) String cronExpression,
        boolean enabled) {}
