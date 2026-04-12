package de.bsnsoft.megarepo.rest.dto.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CreateRoleRequest(
        @NotBlank @Size(max = 100) String id,
        @NotBlank @Size(max = 200) String name,
        @Size(max = 500) String description,
        @NotNull @Size(max = 200) List<String> privileges,
        @NotNull @Size(max = 50) List<String> roles) {}
