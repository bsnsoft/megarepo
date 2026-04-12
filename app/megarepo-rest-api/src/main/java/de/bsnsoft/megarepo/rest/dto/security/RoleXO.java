package de.bsnsoft.megarepo.rest.dto.security;

import java.util.List;

public record RoleXO(
        String id,
        String name,
        String description,
        String source,
        boolean readOnly,
        List<String> privileges,
        List<String> roles) {}
