package de.bsnsoft.megarepo.rest.dto.security;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record LdapServerXO(
        @NotBlank @Size(max = 200) String name,
        int sortOrder,
        @NotBlank @Size(max = 10) String protocol,
        @NotBlank @Size(max = 500) String hostname,
        @Min(1) @Max(65535) int port,
        @NotBlank @Size(max = 1000) String searchBase,
        @NotBlank @Size(max = 50) String authScheme,
        @Size(max = 500) String authUsername,
        @Size(max = 500) String authPassword,
        @NotNull @Min(1) @Max(300) int connectionTimeout,
        @NotNull @Min(0) @Max(300) int retryDelay,
        @NotNull @Min(0) @Max(10) int maxRetries,
        @Size(max = 1000) String userBaseDn,
        boolean userSubtree,
        @NotBlank @Size(max = 200) String userObjectClass,
        @NotBlank @Size(max = 200) String userIdAttribute,
        @NotBlank @Size(max = 200) String userNameAttribute,
        @NotBlank @Size(max = 200) String userEmailAttribute,
        boolean ldapGroupsAsRoles,
        @NotBlank @Size(max = 50) String groupType,
        @Size(max = 1000) String groupBaseDn,
        boolean groupSubtree,
        @Size(max = 200) String groupObjectClass,
        @Size(max = 200) String groupIdAttribute,
        @Size(max = 200) String groupMemberAttribute,
        @Size(max = 200) String groupMemberFormat,
        @Size(max = 200) String userMemberOfAttribute,
        boolean enabled) {}
