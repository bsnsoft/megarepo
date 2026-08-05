-- Repository Firewall, Phase 2 — the enforcement master switch and the policy
-- the switch enforces (osTicket #155155).
--
-- NOTHING IN THIS MIGRATION CHANGES THE BEHAVIOUR OF AN EXISTING INSTALLATION.
-- The customer's constraint is "no existing setup may break on upgrade", so:
--
--   * the master switch row is inserted disabled (enabled = false), and while it
--     is off nothing blocks, no matter what mode a repository is in;
--   * no firewall_repository_config row is touched — in particular nothing is
--     switched to QUARANTINE here, and no default mode is changed;
--   * the seeded policy is inert on its own. A policy only ever applies to a
--     repository that is explicitly in QUARANTINE *and* only while the master
--     switch is on.
--
-- Upgrading therefore leaves every download exactly as it was; enforcement
-- starts the moment an operator flips the switch, and not before.

-- ---------------------------------------------------------------------------
-- 1. The master switch
-- ---------------------------------------------------------------------------
--
-- Singleton row, same shape and rationale as outbound_proxy_settings (V10):
-- `configured` distinguishes "an operator has decided" from "nobody has touched
-- this yet". While configured = false the deployment-side property
-- megarepo.firewall.enforcement.enabled stays authoritative, so an env-only
-- install keeps working the way its Helm values say. The first write from the
-- UI/API sets configured = true and takes over.
--
-- enforcing_since is the grandfathering watermark and the reason this is a table
-- rather than a boolean in a properties file. The customer's second constraint
-- is that components that are *already in the repository* are audited but never
-- blocked, "otherwise existing builds break". "Already in the repository" needs
-- a point in time to be measured against, and the only honest one is the moment
-- enforcement was first switched on: everything that was cached, proxied or
-- uploaded before that instant keeps being served, everything pulled in
-- afterwards is subject to the policy. It is stamped once, when the firewall
-- first observes itself to be enabled, and deliberately NOT reset when the
-- switch is turned off again — otherwise a brief disable would silently
-- grandfather everything that was pulled in while enforcement was off.
CREATE TABLE firewall_enforcement_settings (
    id              INTEGER     PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    configured      BOOLEAN     NOT NULL DEFAULT FALSE,
    enabled         BOOLEAN     NOT NULL DEFAULT FALSE,
    enforcing_since TIMESTAMPTZ,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_by      VARCHAR(200)
);

INSERT INTO firewall_enforcement_settings (id, configured, enabled, enforcing_since)
VALUES (1, false, false, NULL);

-- ---------------------------------------------------------------------------
-- 2. The default policy
-- ---------------------------------------------------------------------------
--
-- V11 created firewall_policy/firewall_policy_rule but seeded no rows, because
-- Phase 1 had no policy engine. Phase 2 has one, and a repository in QUARANTINE
-- with no policy assigned has to resolve to *something* — an empty policy would
-- make the switch a no-op that looks like it is working.
--
-- Two rules only. They are the two the customer signed off on for this
-- increment: a CVSS threshold (the rule the V8 NVD firewall already had) and
-- known-malicious packages (OSV MAL- entries). LICENSE, MIN_AGE,
-- UNKNOWN_COMPONENT, TYPOSQUAT and NAMESPACE_CONFUSION exist as rule types but
-- have no implementation, so seeding them would create rows the engine silently
-- ignores.
--
-- The threshold is 9.0 — CRITICAL — rather than the V8 default of 7.0. A first
-- enforcement pass should deny what nobody wants to consume and leave the
-- judgement calls to the operator; 7.0 covers a large share of a typical
-- dependency tree and would turn "switch it on" into "break the build".
--
-- Both inserts are conditional so that an installation which already created its
-- own default policy (Phase 1 shipped the tables, the UI could have written to
-- them) is not given a second one — firewall_policy has a partial unique index
-- on is_default and a plain INSERT would fail the migration.
INSERT INTO firewall_policy (name, description, is_default, created_by)
SELECT
    'Default',
    'Blocks components with a critical advisory (CVSS >= 9.0) and components '
        || 'flagged as malicious. Applies to repositories in QUARANTINE mode '
        || 'while firewall enforcement is switched on.',
    TRUE,
    'system'
WHERE NOT EXISTS (SELECT 1 FROM firewall_policy WHERE is_default);

-- min_confidence is not written here: the engine defaults a BLOCK rule to EXACT
-- (purl-matched advisories only), which is what keeps a CPE product-name
-- collision from denying an unrelated artifact. An operator who wants the
-- weaker CPE-derived matches to count can add "minConfidence": "HEURISTIC".
INSERT INTO firewall_policy_rule (policy_id, rule_type, action, config, enabled)
SELECT p.id, 'CVSS_THRESHOLD', 'BLOCK', '{"minScore": 9.0}'::jsonb, TRUE
FROM firewall_policy p
WHERE p.is_default
  AND NOT EXISTS (
      SELECT 1 FROM firewall_policy_rule r
      WHERE r.policy_id = p.id AND r.rule_type = 'CVSS_THRESHOLD');

INSERT INTO firewall_policy_rule (policy_id, rule_type, action, config, enabled)
SELECT p.id, 'KNOWN_MALICIOUS', 'BLOCK', '{}'::jsonb, TRUE
FROM firewall_policy p
WHERE p.is_default
  AND NOT EXISTS (
      SELECT 1 FROM firewall_policy_rule r
      WHERE r.policy_id = p.id AND r.rule_type = 'KNOWN_MALICIOUS');
