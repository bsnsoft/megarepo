package de.bsnsoft.megarepo.rest.dto.security;

import jakarta.validation.constraints.Size;

public record AnonymousAccessSettingsXO(
        boolean enabled,
        @Size(max = 200) String userId,
        @Size(max = 200) String realmName) {}
