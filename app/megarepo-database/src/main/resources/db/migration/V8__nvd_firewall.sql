-- NVD Firewall: admin-configurable vulnerability blocking based on a local CVE mirror.

CREATE TABLE nvd_firewall_settings (
    id              INTEGER PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    enabled         BOOLEAN NOT NULL DEFAULT FALSE,
    api_key         VARCHAR(200),
    cvss_threshold  DOUBLE PRECISION NOT NULL DEFAULT 7.0
);

INSERT INTO nvd_firewall_settings (id, enabled, cvss_threshold) VALUES (1, false, 7.0);

-- CVE master table. One row per CVE-ID.
CREATE TABLE cve_entries (
    cve_id          VARCHAR(30) PRIMARY KEY,
    published       TIMESTAMPTZ NOT NULL,
    last_modified   TIMESTAMPTZ NOT NULL,
    cvss_score      DOUBLE PRECISION NOT NULL DEFAULT 0,
    cvss_version    VARCHAR(10),
    severity        VARCHAR(20),
    description     TEXT
);

CREATE INDEX idx_cve_last_modified ON cve_entries (last_modified);
CREATE INDEX idx_cve_score ON cve_entries (cvss_score);

-- CPE match criteria: one CVE can affect many products/versions/ranges.
CREATE TABLE cve_affected_products (
    id                        BIGSERIAL PRIMARY KEY,
    cve_id                    VARCHAR(30) NOT NULL,
    vendor                    VARCHAR(200),
    product                   VARCHAR(200) NOT NULL,
    version_exact             VARCHAR(200),
    version_start_including   VARCHAR(200),
    version_start_excluding   VARCHAR(200),
    version_end_including     VARCHAR(200),
    version_end_excluding     VARCHAR(200),
    FOREIGN KEY (cve_id) REFERENCES cve_entries(cve_id) ON DELETE CASCADE
);

CREATE INDEX idx_cve_product ON cve_affected_products (product);
CREATE INDEX idx_cve_product_cve ON cve_affected_products (cve_id);

-- Singleton sync state tracking the NVD mirror.
CREATE TABLE nvd_sync_state (
    id               INTEGER PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    status           VARCHAR(20) NOT NULL DEFAULT 'IDLE',
    mode             VARCHAR(20),
    started_at       TIMESTAMPTZ,
    last_sync_at     TIMESTAMPTZ,
    last_success_at  TIMESTAMPTZ,
    total_cves       INTEGER NOT NULL DEFAULT 0,
    synced_cves      INTEGER NOT NULL DEFAULT 0,
    total_results    INTEGER,
    error_message    TEXT
);

INSERT INTO nvd_sync_state (id, status, total_cves, synced_cves) VALUES (1, 'IDLE', 0, 0);

-- Blocked download log: every enforcement event for visibility and audit.
CREATE TABLE nvd_firewall_blocks (
    id              BIGSERIAL PRIMARY KEY,
    timestamp       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    user_id         VARCHAR(200),
    repository      VARCHAR(200) NOT NULL,
    path            VARCHAR(2048) NOT NULL,
    component_key   VARCHAR(500) NOT NULL,
    max_cvss_score  DOUBLE PRECISION NOT NULL,
    cve_details     JSONB NOT NULL DEFAULT '[]'
);

CREATE INDEX idx_nvd_blocks_timestamp ON nvd_firewall_blocks (timestamp DESC);

-- Whitelist: allow specific components or CVE-IDs through despite score.
CREATE TABLE nvd_firewall_whitelist (
    id          BIGSERIAL PRIMARY KEY,
    entry_type  VARCHAR(20) NOT NULL,
    value       VARCHAR(500) NOT NULL,
    reason      VARCHAR(500),
    added_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    added_by    VARCHAR(200),
    CONSTRAINT nvd_whitelist_unique UNIQUE (entry_type, value),
    CONSTRAINT nvd_whitelist_type_check CHECK (entry_type IN ('COMPONENT', 'CVE'))
);

CREATE INDEX idx_nvd_whitelist_type_value ON nvd_firewall_whitelist (entry_type, value);

-- Daily delta sync of the NVD mirror at 03:00.
INSERT INTO scheduled_tasks (name, type, cron_expression, config)
VALUES ('NVD firewall sync', 'security.nvd.sync', '0 0 3 * * ?', '{}');
