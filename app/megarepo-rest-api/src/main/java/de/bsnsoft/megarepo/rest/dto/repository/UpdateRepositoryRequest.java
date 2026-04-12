package de.bsnsoft.megarepo.rest.dto.repository;

import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * Request body for updating a repository. Only mutable fields are included —
 * name, format, type, and blob store cannot be changed after creation.
 */
public record UpdateRepositoryRequest(boolean online, @NotNull Map<String, Object> attributes) {}
