package de.bsnsoft.megarepo.app.migration;

import jakarta.validation.constraints.NotBlank;

public record NexusMigrationRequest(
        @NotBlank(message = "Nexus URL is required") String nexusUrl,
        @NotBlank(message = "Username is required") String username,
        @NotBlank(message = "Password is required") String password) {}
