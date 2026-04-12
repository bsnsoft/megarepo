package de.bsnsoft.megarepo.rest.dto.security;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ApiUpdateProfile(
        @NotBlank @Size(max = 200) String firstName,
        @NotBlank @Size(max = 200) String lastName,
        @Email @NotBlank @Size(max = 320) String emailAddress) {}
