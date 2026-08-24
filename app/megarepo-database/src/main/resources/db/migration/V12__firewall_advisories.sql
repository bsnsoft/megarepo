-- Repository Firewall, Phase 1 — normalised advisory store.
--
-- Implements docs/design/repository-firewall.md section 3 (numbered V10 there;
-- see the numbering note in V11__firewall_policies.sql).
--
-- This is the merge target for every AdvisorySource (NVD, OSV, GHSA). The V8
-- tables cve_entries / cve_affected_products stay in place as the NVD source's
-- own CPE-shaped storage — NvdAdvisorySource reads them and normalises into
-- `advisory` / `advisory_affected`. Nothing is dropped.

-- One row per advisory id across all sources: CVE-…, GHSA-…, OSV-…, MAL-….
CREATE TABLE advisory (
    id           VARCHAR(100) PRIMARY KEY,
    source       VARCHAR(30)  NOT NULL,
    summary      TEXT,
    severity     VARCHAR(20),
    -- Nullable on purpose: malicious-package advisories (MAL-…) and withdrawn
    -- entries carry no CVSS score at all. V8's cve_entries.cvss_score defaults
    -- to 0, which is indistinguishable from a genuine 0.0 — not repeated here.
    cvss_score   DOUBLE PRECISION,
    cvss_vector  VARCHAR(200),
    published    TIMESTAMPTZ,
    modified     TIMESTAMPTZ,
    withdrawn_at TIMESTAMPTZ,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_advisory_source ON advisory (source);
CREATE INDEX idx_advisory_modified ON advisory (modified);
CREATE INDEX idx_advisory_severity ON advisory (severity);

-- Affected package ranges, purl-shaped rather than CPE-shaped. This is what
-- removes the CPE guessing described in design section 1: purl_namespace is a
-- first-class column, so com.acme:util and org.other:util no longer collide.
CREATE TABLE advisory_affected (
    id             UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    advisory_id    VARCHAR(100)  NOT NULL REFERENCES advisory(id) ON DELETE CASCADE,
    purl_type      VARCHAR(50)   NOT NULL,
    -- Nullable: unscoped npm packages and PyPI projects have no namespace.
    purl_namespace VARCHAR(500),
    purl_name      VARCHAR(500)  NOT NULL,
    -- Raw upstream range expression (OSV events, GHSA vulnerable_version_range,
    -- Maven ranges). Kept verbatim; introduced/fixed/last_affected below are the
    -- resolved boundaries a VersionScheme can compare against.
    version_range  VARCHAR(1000),
    introduced     VARCHAR(200),
    fixed          VARCHAR(200),
    last_affected  VARCHAR(200)
);

-- The lookup index the request path depends on (design section 3).
CREATE INDEX idx_advisory_affected_purl
    ON advisory_affected (purl_type, purl_namespace, purl_name);
CREATE INDEX idx_advisory_affected_advisory ON advisory_affected (advisory_id);

-- One row per AdvisorySource. Unlike V8's nvd_sync_state (single row, CHECK id=1)
-- this is keyed by source, because Phase 1 ingests from several feeds.
CREATE TABLE advisory_sync_state (
    source          VARCHAR(30)  PRIMARY KEY,
    status          VARCHAR(20)  NOT NULL DEFAULT 'IDLE',
    -- Opaque resume token, source-specific (OSV snapshot etag, GHSA cursor,
    -- NVD lastModStartDate). Never parsed by the firewall itself.
    cursor          VARCHAR(500),
    last_success_at TIMESTAMPTZ,
    error_message   TEXT,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
