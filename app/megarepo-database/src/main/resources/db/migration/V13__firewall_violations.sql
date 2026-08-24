-- Repository Firewall, Phase 1 — violation log (AUDIT mode).
--
-- Implements docs/design/repository-firewall.md section 3 (numbered V11 there;
-- see the numbering note in V11__firewall_policies.sql).
--
-- DELIBERATE DEVIATION FROM THE DESIGN — READ BEFORE REUSING THIS NUMBER:
-- the design bundles three tables into one migration: firewall_quarantine,
-- firewall_violation and firewall_exemption. Only firewall_violation is created
-- here.
--
-- Why: Phase 1 ships AUDIT mode only (design section 5). AUDIT records what a
-- policy *would* have done without blocking anything, so it needs somewhere to
-- write violations — this table — and nothing else. firewall_quarantine holds
-- QUARANTINED/RELEASED/BLOCKED state transitions and firewall_exemption holds
-- the approval workflow; both are meaningless until Phase 2 actually enforces
-- policies, and shipping empty tables with a state machine nobody drives yet
-- would freeze their shape before the enforcement code exists to validate it.
--
-- Consequence for Phase 2: version 13 is already used. Create the two remaining
-- tables as V14__firewall_quarantine_exemptions.sql (or whatever the next free
-- number is by then), and put the V8 data migration — design V12, also deferred,
-- because migrating nvd_firewall_settings/whitelist/blocks only makes sense once
-- policies are enforced — after it.

CREATE TABLE firewall_violation (
    id              BIGSERIAL     PRIMARY KEY,
    -- Nullable + ON DELETE SET NULL so the audit trail survives repository
    -- deletion. repository_name is kept alongside for exactly that case, and
    -- because the Phase 2 migration of nvd_firewall_blocks has only the name
    -- (V8 stores `repository VARCHAR(200)`, no FK) and cannot resolve an id for
    -- repositories that no longer exist.
    repository_id   UUID          REFERENCES repositories(id) ON DELETE SET NULL,
    repository_name VARCHAR(200)  NOT NULL,
    purl            VARCHAR(1000) NOT NULL,
    policy_id       UUID          REFERENCES firewall_policy(id) ON DELETE SET NULL,
    -- No CHECK on rule_type, for the reason given in V11: rule types are meant
    -- to be extensible without a migration.
    rule_type       VARCHAR(40)   NOT NULL,
    action          VARCHAR(10)   NOT NULL,
    advisory_ids    TEXT[]        NOT NULL DEFAULT '{}',
    occurred_at     TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    request_context JSONB         NOT NULL DEFAULT '{}',
    CONSTRAINT firewall_violation_action_check CHECK (action IN ('WARN', 'BLOCK'))
);

CREATE INDEX idx_firewall_violation_occurred_at ON firewall_violation (occurred_at DESC);
CREATE INDEX idx_firewall_violation_repository ON firewall_violation (repository_id);
CREATE INDEX idx_firewall_violation_purl ON firewall_violation (purl);
CREATE INDEX idx_firewall_violation_policy ON firewall_violation (policy_id);
