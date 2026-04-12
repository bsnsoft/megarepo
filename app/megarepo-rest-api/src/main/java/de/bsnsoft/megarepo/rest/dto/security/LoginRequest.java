package de.bsnsoft.megarepo.rest.dto.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank @Size(max = 200) String username,
        @NotBlank @Size(max = 1000) String password) {}
