package de.bsnsoft.megarepo.rest.dto.security;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public record ApiCreateUser(
        @NotBlank @Size(max = 200) @Pattern(
                regexp = "^[a-zA-Z0-9][a-zA-Z0-9._@-]*$",
                message = "User ID must start with alphanumeric and contain only alphanumeric, dots, hyphens, underscores, and @")
                String userId,
        @NotBlank @Size(max = 200) String firstName,
        @NotBlank @Size(max = 200) String lastName,
        @Email @NotBlank @Size(max = 320) String emailAddress,
        @NotBlank @Size(min = 8, max = 1000, message = "Password must be between 8 and 1000 characters") String password,
        @NotBlank String status,
        @NotNull List<String> roles) {}
