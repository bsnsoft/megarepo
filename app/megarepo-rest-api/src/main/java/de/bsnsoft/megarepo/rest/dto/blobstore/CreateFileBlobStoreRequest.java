package de.bsnsoft.megarepo.rest.dto.blobstore;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateFileBlobStoreRequest(
        @NotBlank @Size(min = 2, max = 100) @Pattern(
                regexp = "^[a-zA-Z0-9][a-zA-Z0-9._-]*$",
                message = "Blob store name must start with alphanumeric and contain only alphanumeric, dots, hyphens, and underscores")
                String name,
        @NotBlank @Size(max = 500) String path) {}
