package de.bsnsoft.megarepo.security.ldap;

import java.util.Set;

public record LdapUserInfo(String username, String displayName, String email, Set<String> groups) {}
