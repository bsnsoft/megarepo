package de.bsnsoft.megarepo.rest.dto.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public record NvdWhitelistEntryXO(
        Long id,
        @NotBlank @Pattern(regexp = "^(COMPONENT|CVE)$", message = "entryType must be COMPONENT or CVE")
        String entryType,
        @NotBlank @Size(max = 500) String value,
        @Size(max = 500) String reason,
        Instant addedAt,
        String addedBy) {}
