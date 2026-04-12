package de.bsnsoft.megarepo.rest.dto.cleanup;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;

public record CreateCleanupPolicyRequest(
        @NotBlank @Size(min = 2, max = 100) String name,
        @Size(max = 50) String format,
        @Size(max = 500) String notes,
        @NotNull Map<String, Object> criteria) {}
