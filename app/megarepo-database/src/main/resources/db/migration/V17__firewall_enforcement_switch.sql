-- Repository Firewall — the global enforcement switch.
--
-- ONE row, ONE flag, and it is the single source of truth for the question
-- "may this instance block a download at all?". Per-repository intent lives in
-- firewall_repository_config.mode (V11) and is unchanged here; a repository set
-- to QUARANTINE blocks nothing until this flag is true.
--
-- WHY A TABLE AND NOT A PROPERTY
--
-- The customer operates their own MegaRepo and has to be able to arm the
-- firewall themselves, from the Web UI, after reading the violations the AUDIT
-- mode collected. A Spring property (megarepo.firewall.*) cannot be written at
-- runtime, so it would force the decision into a container restart — and, worse,
-- would leave two places claiming to hold the switch once a UI existed. This
-- table is that one place. The read path (Phase 2 enforcement) must resolve the
-- switch from here, through FirewallEnforcementSettingsJpaRepository, and cache
-- it rather than query per download.
--
-- The pattern is outbound_proxy_settings (V10): singleton row pinned to id = 1
-- by a CHECK, seeded by the migration so no code path has to invent it.
--
-- SEEDED OFF, ALWAYS
--
-- Upgrading to this version must not change the behaviour of any running
-- installation. An instance that has been observing in AUDIT mode for weeks,
-- with repositories already switched to QUARANTINE in anticipation, keeps
-- serving every download after this migration runs. Enforcement begins exactly
-- when an administrator turns it on, and never as a side effect of a deploy.

CREATE TABLE firewall_enforcement_settings (
    id         INTEGER      PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    enabled    BOOLEAN      NOT NULL DEFAULT FALSE,
    -- Who armed or disarmed it, and when. Kept on the row itself rather than
    -- only in audit_log: this is the one fact an operator looking at a blocking
    -- instance needs immediately, and it must survive log rotation/retention.
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_by VARCHAR(200)
);

INSERT INTO firewall_enforcement_settings (id, enabled) VALUES (1, FALSE);
