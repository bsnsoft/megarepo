-- Repository Firewall, Phase 1 — policy model.
--
-- Implements docs/design/repository-firewall.md section 3. The design numbers
-- this migration V9, but V9 (maven format-string fix) and V10 (outbound proxy
-- settings) were already taken by unrelated work before the firewall branch
-- was cut. The firewall block therefore starts at V11:
--
--     design V9  (firewall_policies)   -> V11   (this file)
--     design V10 (firewall_advisories) -> V12
--     design V11 (quarantine/violation/exemption) -> V13, violations only
--     design V12 (migrate V8 data)     -> V14, deferred to Phase 2
--
-- Forward-only. Nothing from V8 (nvd_firewall_settings, nvd_firewall_whitelist,
-- nvd_firewall_blocks, cve_entries, cve_affected_products) is dropped, renamed
-- or altered here — the NVD firewall keeps running unchanged until Phase 2
-- migrates its data over.

CREATE TABLE firewall_policy (
    id          UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(200)  NOT NULL UNIQUE,
    description VARCHAR(1000),
    is_default  BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    created_by  VARCHAR(200)
);

-- At most one policy may be the global default. A partial unique index states
-- that invariant in the schema instead of leaving it to application code.
CREATE UNIQUE INDEX idx_firewall_policy_single_default
    ON firewall_policy (is_default) WHERE is_default;

-- Rule parameters live in `config` JSONB rather than one column per rule type,
-- so adding a rule type is a code change and not a migration (design section 3).
-- For the same reason `rule_type` deliberately carries NO CHECK constraint —
-- a CHECK would reintroduce exactly the migration coupling the JSONB design
-- avoids. The closed sets (`action`, and mode/fail_mode below) do get CHECKs.
CREATE TABLE firewall_policy_rule (
    id        UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    policy_id UUID         NOT NULL REFERENCES firewall_policy(id) ON DELETE CASCADE,
    rule_type VARCHAR(40)  NOT NULL,
    action    VARCHAR(10)  NOT NULL DEFAULT 'WARN',
    config    JSONB        NOT NULL DEFAULT '{}',
    enabled   BOOLEAN      NOT NULL DEFAULT TRUE,
    CONSTRAINT firewall_policy_rule_action_check CHECK (action IN ('WARN', 'BLOCK'))
);

CREATE INDEX idx_firewall_policy_rule_policy ON firewall_policy_rule (policy_id);

-- Per-repository firewall configuration. Absence of a row means "not configured"
-- and is resolved by the application to the global default; Phase 2 backfills an
-- explicit row per repository so upgrade behaviour stays identical to today.
--
-- Phase 1 ships AUDIT only: QUARANTINE is accepted by the CHECK so Phase 2 does
-- not need a schema change, but nothing enforces it yet.
CREATE TABLE firewall_repository_config (
    repository_id UUID        PRIMARY KEY REFERENCES repositories(id) ON DELETE CASCADE,
    mode          VARCHAR(20) NOT NULL DEFAULT 'AUDIT',
    policy_id     UUID        REFERENCES firewall_policy(id) ON DELETE SET NULL,
    fail_mode     VARCHAR(20) NOT NULL DEFAULT 'FAIL_OPEN',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT firewall_repository_config_mode_check
        CHECK (mode IN ('OFF', 'AUDIT', 'QUARANTINE')),
    CONSTRAINT firewall_repository_config_fail_mode_check
        CHECK (fail_mode IN ('FAIL_OPEN', 'FAIL_CLOSED'))
);

CREATE INDEX idx_firewall_repository_config_policy ON firewall_repository_config (policy_id);
