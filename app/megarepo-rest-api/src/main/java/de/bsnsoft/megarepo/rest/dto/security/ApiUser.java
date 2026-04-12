package de.bsnsoft.megarepo.rest.dto.security;

import java.util.List;

public record ApiUser(
        String userId,
        String firstName,
        String lastName,
        String emailAddress,
        String source,
        String status,
        boolean readOnly,
        List<String> roles) {}
