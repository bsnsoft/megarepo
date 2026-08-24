-- Repository Firewall, Phase 2 — carry the V8 NVD firewall whitelist over into
-- exemptions (osTicket #155155).
--
-- The customer's requirement is that existing whitelist rows become
-- non-expiring, approved exemptions, so that an upgrade cannot start blocking
-- something an operator had already decided to allow.
--
-- FORWARD-ONLY AND NON-DESTRUCTIVE. nvd_firewall_whitelist is read, never
-- written and never dropped. The V8 firewall keeps enforcing its own whitelist
-- from its own table exactly as before; this migration adds an equivalent
-- statement in the new model, and the two coexist until the V8 tables are
-- dropped in a later release.
--
-- ---------------------------------------------------------------------------
-- Why the value is copied verbatim instead of being converted to a purl
-- ---------------------------------------------------------------------------
--
-- A V8 whitelist value is NvdFirewallService.buildComponentKey's output:
--
--     format ":" namespace ":" name ":" version
--     e.g.  maven2:org.apache.logging.log4j:log4j-core:2.14.1
--
-- The "format" part is the repository's raw format key, and the name is stored
-- as the format handler wrote it — no purl type mapping, no PEP 503
-- normalisation, no npm scope handling. Turning that into a purl needs
-- PurlMapper, which lives in the six format modules and is not reachable from a
-- Flyway migration.
--
-- Converting it in SQL anyway would mean guessing, and both directions of a
-- wrong guess are bad in production: a converted key that no longer matches is a
-- build that breaks on upgrade for a component the operator had explicitly
-- allowed, and one that matches more than before is a hole nobody opened. So the
-- key is stored as it stands, marked key_kind = 'LEGACY_COORDINATE', and the
-- exemption matcher reproduces the V8 comparison for those rows: it builds the
-- same format:namespace:name:version string from the component being evaluated
-- and compares. Identical behaviour, no guessing, and the rows are visible in
-- the Exemptions UI where an operator can replace them with purl-based ones.
--
-- ---------------------------------------------------------------------------
-- Scope: derived, not guessed
-- ---------------------------------------------------------------------------
--
-- The V8 matcher (NvdFirewallService.isComponentWhitelisted) accepted a
-- whitelist entry when it equalled the component key, OR when it equalled that
-- key with the last colon-separated segment removed. A component key always has
-- exactly three colons, so:
--
--     3 colons  ->  matched one version              ->  scope_type = 'VERSION'
--     2 colons  ->  matched every version            ->  scope_type = 'COMPONENT'
--     anything else -> matched nothing under V8, and is carried over as VERSION
--                      so it goes on matching nothing while staying visible.
--
-- That is a reading of the old code, not an assumption about the data.
--
-- ---------------------------------------------------------------------------
-- What is deliberately NOT migrated
-- ---------------------------------------------------------------------------
--
-- 1. entry_type = 'CVE' rows. They say "ignore this advisory id", which is a
--    property of a policy and not of a component — there is no component to
--    scope an exemption to. firewall_exemption.advisory_ids exists so an
--    operator can express "ignore CVE-X *for this component*", which is the
--    narrower and safer statement, but silently widening a global CVE
--    suppression into one is not something a migration may decide. The rows stay
--    in nvd_firewall_whitelist and the Exemptions page surfaces them as legacy
--    entries that need a decision.
--
-- 2. nvd_firewall_blocks -> firewall_violation. The design proposed it; it is
--    left out because a V8 block row has a legacy component key, no purl, no
--    policy and no rule type, and writing it into the purl-keyed violation log
--    under an invented rule type would corrupt the one table Phase 1 built to be
--    trustworthy. The history stays readable in its own table.

INSERT INTO firewall_exemption (
    component_key,
    key_kind,
    scope_type,
    repository_id,
    rule_type,
    state,
    expires_at,
    justification,
    requested_by,
    requested_at,
    approved_by,
    approved_at,
    decision_note,
    created_at,
    updated_at
)
SELECT
    w.value,
    'LEGACY_COORDINATE',
    CASE
        WHEN length(w.value) - length(replace(w.value, ':', '')) = 2 THEN 'COMPONENT'
        ELSE 'VERSION'
    END,
    -- The V8 whitelist was global; it had no repository column at all. NULL here
    -- says the same thing, and narrowing it per repository would be a change of
    -- meaning, not a migration.
    NULL,
    -- Exempt from every rule, because that is what the V8 whitelist did: it
    -- short-circuited the entire check before any threshold was applied.
    NULL,
    'APPROVED',
    -- Non-expiring, as required. An expiry invented here would turn a silent
    -- upgrade into a build break on whatever date was chosen.
    NULL,
    COALESCE(
        NULLIF(btrim(w.reason), ''),
        'Migrated from the NVD firewall whitelist; no reason was recorded there.'),
    COALESCE(NULLIF(btrim(w.added_by), ''), 'nvd-firewall-migration'),
    w.added_at,
    COALESCE(NULLIF(btrim(w.added_by), ''), 'nvd-firewall-migration'),
    w.added_at,
    'Carried over from nvd_firewall_whitelist by migration V18. The key is a '
        || 'legacy coordinate, not a purl — replace it with a purl-based '
        || 'exemption when convenient.',
    w.added_at,
    NOW()
FROM nvd_firewall_whitelist w
WHERE w.entry_type = 'COMPONENT'
  AND NOT EXISTS (
      SELECT 1 FROM firewall_exemption e
      WHERE e.key_kind = 'LEGACY_COORDINATE' AND e.component_key = w.value);
