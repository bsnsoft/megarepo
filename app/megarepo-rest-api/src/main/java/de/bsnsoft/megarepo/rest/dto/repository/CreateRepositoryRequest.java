package de.bsnsoft.megarepo.rest.dto.repository;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Map;

public record CreateRepositoryRequest(
        @NotBlank @Size(min = 2, max = 100) @Pattern(
                regexp = "^[a-zA-Z0-9][a-zA-Z0-9._-]*$",
                message = "Repository name must start with alphanumeric and contain only alphanumeric, dots, hyphens, and underscores")
                String name,
        @NotBlank String format,
        @NotBlank @Pattern(regexp = "^(HOSTED|PROXY|GROUP)$", message = "Type must be HOSTED, PROXY, or GROUP")
                String type,
        boolean online,
        @NotBlank String blobStoreName,
        @NotNull Map<String, Object> attributes) {}
