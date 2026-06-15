-- Runtime-configurable global outbound (egress) proxy, editable via the Web UI
-- under System → HTTP. Until the UI is used (configured = false), the
-- deployment-side megarepo.outbound-proxy.* (Helm/env) configuration stays
-- authoritative, preserving backwards compatibility for env-only installs.
--
-- The password column follows the project's existing secret convention
-- (plaintext column, write-only over the API, masked in the UI) — the same as
-- ldap_servers.auth_password and SSL certificate material. MegaRepo has no
-- encryption-at-rest layer.

CREATE TABLE outbound_proxy_settings (
    id               INTEGER PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    configured       BOOLEAN NOT NULL DEFAULT FALSE,
    enabled          BOOLEAN NOT NULL DEFAULT FALSE,
    host             VARCHAR(500),
    port             INTEGER NOT NULL DEFAULT 3128,
    username         VARCHAR(500),
    password         VARCHAR(500),
    non_proxy_hosts  VARCHAR(2000)
);

INSERT INTO outbound_proxy_settings (id, configured, enabled, port)
VALUES (1, false, false, 3128);
