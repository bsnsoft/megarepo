package de.bsnsoft.megarepo.core.security;

import java.util.Set;

public record UserPrincipal(String userId, Set<String> roles, String source) {
}
