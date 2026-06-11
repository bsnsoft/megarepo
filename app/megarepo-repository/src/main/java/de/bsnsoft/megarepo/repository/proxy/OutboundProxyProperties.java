package de.bsnsoft.megarepo.repository.proxy;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Global outbound (egress) proxy configuration for all upstream fetches.
 *
 * <p>When {@code enabled}, every request made by {@link RemoteHttpClient}'s default
 * client is routed through the configured forward proxy, optionally with proxy
 * authentication (Basic). Hosts matching {@code nonProxyHosts} bypass the proxy.
 *
 * <p>All values are settable via environment variables thanks to Spring Boot's
 * relaxed binding, e.g.:
 * <pre>
 *   MEGAREPO_OUTBOUNDPROXY_ENABLED=true
 *   MEGAREPO_OUTBOUNDPROXY_HOST=proxy.corp.example.com
 *   MEGAREPO_OUTBOUNDPROXY_PORT=3128
 *   MEGAREPO_OUTBOUNDPROXY_USERNAME=megarepo
 *   MEGAREPO_OUTBOUNDPROXY_PASSWORD=secret
 *   MEGAREPO_OUTBOUNDPROXY_NONPROXYHOSTS=localhost,*.internal.example.com
 * </pre>
 *
 * <p>When {@code enabled} is {@code false} (the default), behavior is unchanged:
 * the JDK's default proxy selector applies, so legacy JVM properties
 * ({@code -Dhttp.proxyHost=...} via {@code JAVA_TOOL_OPTIONS}) keep working.
 */
@ConfigurationProperties(prefix = "megarepo.outbound-proxy")
public record OutboundProxyProperties(
        @DefaultValue("false") boolean enabled,
        String host,
        @DefaultValue("3128") int port,
        String username,
        String password,
        @DefaultValue List<String> nonProxyHosts) {

    public OutboundProxyProperties {
        nonProxyHosts = nonProxyHosts == null ? List.of() : List.copyOf(nonProxyHosts);
    }

    /**
     * A disabled configuration (used as default and in tests).
     */
    public static OutboundProxyProperties disabled() {
        return new OutboundProxyProperties(false, null, 3128, null, null, List.of());
    }

    /**
     * Whether proxy authentication credentials are configured.
     */
    public boolean hasAuth() {
        return notBlank(username) && notBlank(password);
    }

    /**
     * Whether the given target host should bypass the proxy.
     *
     * <p>Patterns are matched case-insensitively and support {@code *} as a
     * wildcard, e.g. {@code *.internal.example.com} or {@code 10.0.*}.
     */
    public boolean isNonProxyHost(String targetHost) {
        if (targetHost == null || nonProxyHosts.isEmpty()) {
            return false;
        }
        for (String pattern : nonProxyHosts) {
            if (pattern == null || pattern.isBlank()) {
                continue;
            }
            if (globMatches(pattern.trim(), targetHost)) {
                return true;
            }
        }
        return false;
    }

    private static boolean globMatches(String pattern, String host) {
        // Translate the glob pattern into a regex: '*' matches any sequence,
        // everything else is matched literally (case-insensitive).
        String[] parts = pattern.split("\\*", -1);
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                regex.append(".*");
            }
            if (!parts[i].isEmpty()) {
                regex.append(Pattern.quote(parts[i]));
            }
        }
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE)
                .matcher(host)
                .matches();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
