package de.bsnsoft.megarepo.rest.dto.system;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * Runtime configuration for the global outbound (egress) proxy, surfaced in the
 * Web UI under <em>System → HTTP</em>.
 *
 * <p>Secret handling mirrors the LDAP server DTO: the {@code password} is
 * <b>write-only</b>. It is never populated on read responses; {@code passwordSet}
 * tells the UI whether a password is stored. On update, a {@code null}/blank
 * {@code password} means "keep the stored one".
 *
 * <p>{@code source} is read-only metadata telling the UI whether the effective
 * configuration currently comes from the database ({@code "database"}) or from
 * the deployment-side fallback ({@code "environment"}).
 */
public record OutboundProxySettingsXO(
        boolean enabled,
        @Size(max = 500) String host,
        @Min(1) @Max(65535) int port,
        @Size(max = 500) String username,
        @Size(max = 500) String password,
        boolean passwordSet,
        @Size(max = 2000) String nonProxyHosts,
        boolean configured,
        String source) {
}
