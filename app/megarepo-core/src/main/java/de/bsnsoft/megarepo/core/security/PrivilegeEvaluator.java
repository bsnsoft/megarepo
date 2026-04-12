package de.bsnsoft.megarepo.core.security;

public interface PrivilegeEvaluator {

    boolean hasPermission(UserPrincipal user, String action, String format, String repositoryName);
}
