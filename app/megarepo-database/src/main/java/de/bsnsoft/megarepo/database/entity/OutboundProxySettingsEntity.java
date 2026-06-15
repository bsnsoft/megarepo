package de.bsnsoft.megarepo.database.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Singleton runtime configuration for the global outbound (egress) proxy,
 * editable via the Web UI under <em>System → HTTP</em>.
 *
 * <p>When {@link #configured} is {@code true}, these values take precedence over
 * the deployment-side defaults ({@code megarepo.outbound-proxy.*} via Helm/env).
 * When {@code false} (the seeded default), the UI has never been touched and the
 * deployment-side configuration applies unchanged — preserving backwards
 * compatibility for installs that configure the proxy purely through env vars.
 *
 * <p>Like {@code ldap_servers.auth_password} and SSL certificate material, the
 * proxy {@link #password} is stored as a plaintext column and handled write-only
 * over the REST API (never returned in responses, masked in the UI). MegaRepo
 * has no encryption-at-rest layer; this column follows the existing secret
 * convention of the project.
 */
@Entity
@Table(name = "outbound_proxy_settings")
public class OutboundProxySettingsEntity {

    @Id
    @Column(name = "id")
    private Integer id = 1;

    /**
     * Whether the UI has taken over proxy configuration. When {@code false}, the
     * deployment-side {@code megarepo.outbound-proxy.*} values are authoritative.
     */
    @Column(name = "configured", nullable = false)
    private boolean configured = false;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = false;

    @Column(name = "host", length = 500)
    private String host;

    @Column(name = "port", nullable = false)
    private int port = 3128;

    @Column(name = "username", length = 500)
    private String username;

    @Column(name = "password", length = 500)
    private String password;

    /**
     * Comma-separated list of host patterns that bypass the proxy
     * ({@code *} wildcard supported), e.g. {@code localhost,*.internal.example.com}.
     */
    @Column(name = "non_proxy_hosts", length = 2000)
    private String nonProxyHosts;

    public OutboundProxySettingsEntity() {}

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public boolean isConfigured() {
        return configured;
    }

    public void setConfigured(boolean configured) {
        this.configured = configured;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getNonProxyHosts() {
        return nonProxyHosts;
    }

    public void setNonProxyHosts(String nonProxyHosts) {
        this.nonProxyHosts = nonProxyHosts;
    }
}
