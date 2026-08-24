-- Repository Firewall, Phase 2 — quarantine, exemptions and component facts
-- (osTicket #155155).
--
-- This is the contract migration: it fixes the shape every Phase 2 work package
-- writes against, so that the packages themselves add code and not columns.
--
-- NUMBERING. V13's header reserved "V14 or whatever the next free number is by
-- then" for these tables; V14 went to advisory_affected_name_index, V15 to the
-- advisory sync schedule and V16 to the enforcement master switch. Phase 2's
-- schema therefore starts here, at V17, and V18/V19 continue it.
--
-- NOTHING HERE CHANGES THE BEHAVIOUR OF AN EXISTING INSTALLATION. Three empty
-- tables are created. No repository mode is touched, no policy is altered, the
-- enforcement master switch seeded off by V16 stays off, and the V8 NVD firewall
-- keeps running unchanged. A component can only reach firewall_quarantine
-- through a rule that Phase 2 code evaluates while enforcement is on.

-- ---------------------------------------------------------------------------
-- 1. Exemptions
-- ---------------------------------------------------------------------------
--
-- The table the V8 whitelist should have been. The four things it adds are the
-- four the customer named as missing: an expiry, a justification, a requester
-- and an approver — plus an explicit scope, because the V8 matcher silently
-- prefix-matched a whitelisted version into "every version of this component"
-- and nobody could see that from the row.
--
-- Created before firewall_quarantine because that table references it.
--
-- THE WORD IS "exemption". Not "waiver", not "whitelist" — the customer settled
-- that in item 7 of the request, and it holds for the table, the entity, the
-- REST path and the UI label.
CREATE TABLE firewall_exemption (
    id            UUID          PRIMARY KEY DEFAULT gen_random_uuid(),

    -- What is exempted.
    --
    -- component_key rather than `purl`, which is what firewall_violation calls
    -- the same idea, because this column has to hold two things a purl cannot:
    -- a content-digest identity (sha256:… for raw/Docker content, which has no
    -- coordinates) and a migrated V8 whitelist value. key_kind says which
    -- scheme applies; see V18 and FirewallComponentKeyKind.
    component_key VARCHAR(1000) NOT NULL,
    key_kind      VARCHAR(20)   NOT NULL DEFAULT 'PURL',
    scope_type    VARCHAR(20)   NOT NULL DEFAULT 'VERSION',

    -- NULL = every repository. The repository half of the scope is a column
    -- rather than more scope_type constants so the two dimensions the customer
    -- asked for (this version / all versions × this repository / all
    -- repositories) stay two independent switches.
    repository_id UUID          REFERENCES repositories(id) ON DELETE CASCADE,

    -- Optional narrowing, both NULL/empty by default so the customer-specified
    -- scope is what an operator gets without thinking about them:
    --   rule_type    NULL = exempt from every rule; else only from this one.
    --   advisory_ids empty = every advisory; else only these ids.
    -- No CHECK on rule_type, for the reason given in V11: rule types are a code
    -- change, never a migration.
    rule_type     VARCHAR(40),
    advisory_ids  TEXT[]        NOT NULL DEFAULT '{}',

    state         VARCHAR(20)   NOT NULL DEFAULT 'REQUESTED',

    -- NULL = never expires. Only the migrated V8 rows and a deliberate operator
    -- decision should carry NULL: an exemption without an end date is how the
    -- V8 whitelist accumulated entries nobody could justify any more.
    expires_at    TIMESTAMPTZ,

    -- Set once by the expiry sweep when it sends the "this lapses soon" notice,
    -- so a lapse is announced exactly once and not on every sweep.
    expiry_notified_at TIMESTAMPTZ,

    justification VARCHAR(2000) NOT NULL,
    requested_by  VARCHAR(200)  NOT NULL,
    requested_at  TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    approved_by   VARCHAR(200),
    approved_at   TIMESTAMPTZ,
    decision_note VARCHAR(1000),

    created_at    TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    CONSTRAINT firewall_exemption_key_kind_check
        CHECK (key_kind IN ('PURL', 'LEGACY_COORDINATE')),
    CONSTRAINT firewall_exemption_scope_check
        CHECK (scope_type IN ('VERSION', 'COMPONENT')),
    CONSTRAINT firewall_exemption_state_check
        CHECK (state IN ('REQUESTED', 'APPROVED', 'REJECTED', 'EXPIRED', 'REVOKED')),
    -- An approved exemption without an approver is an exemption nobody signed.
    CONSTRAINT firewall_exemption_approved_has_approver
        CHECK (state <> 'APPROVED' OR approved_by IS NOT NULL)
);

-- The request-path lookup: "is there a live exemption for this component key?"
-- Partial, on the only state that can let a download through, so the index stays
-- small however many rejected and expired rows pile up behind it.
CREATE INDEX idx_firewall_exemption_lookup
    ON firewall_exemption (component_key, repository_id)
    WHERE state = 'APPROVED';

-- The expiry sweep and the "lapses soon" notice.
CREATE INDEX idx_firewall_exemption_expiry
    ON firewall_exemption (expires_at)
    WHERE state = 'APPROVED' AND expires_at IS NOT NULL;

-- The approval queue.
CREATE INDEX idx_firewall_exemption_state ON firewall_exemption (state, requested_at DESC);
CREATE INDEX idx_firewall_exemption_repository ON firewall_exemption (repository_id);

-- ---------------------------------------------------------------------------
-- 2. Quarantine
-- ---------------------------------------------------------------------------
--
-- One row per (repository, component) that a rule decided to hold rather than
-- refuse outright. Quarantine is NOT "everything the policy denied": a critical
-- advisory or a malicious package is refused, full stop. Only verdicts that are
-- expected to change by themselves are held — too new, nothing known yet, or the
-- evaluation did not finish and the repository is fail-closed. See
-- FirewallQuarantineState.
--
-- The unique key is (repository_id, component_key) and not the asset path: the
-- same component is reachable under several paths (a jar and its sources jar,
-- an npm tarball and its metadata document), and a queue with one entry per path
-- would ask an operator to make the same decision four times.
CREATE TABLE firewall_quarantine (
    id              UUID          PRIMARY KEY DEFAULT gen_random_uuid(),

    -- NOT NULL, unlike firewall_violation.repository_id: a violation is a
    -- historical record that must outlive its repository, while a quarantine
    -- entry is live state about a repository that still exists. Deleting the
    -- repository deletes the queue entries with it; the violation rows that
    -- document the same events stay.
    repository_id   UUID          NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    repository_name VARCHAR(200)  NOT NULL,

    -- ComponentIdentity.key(): a canonical purl, or sha256:… for content that
    -- has no coordinates. Never a V8 legacy coordinate — nothing migrates into
    -- this table.
    component_key   VARCHAR(1000) NOT NULL,

    -- Diagnostics, all nullable because the identity above is the real key.
    -- component_id is SET NULL rather than CASCADE so that a cleanup task
    -- deleting the cached artifact does not silently empty the queue that says
    -- why it was being held.
    component_id    UUID          REFERENCES components(id) ON DELETE SET NULL,
    path            VARCHAR(2048),
    asset_sha256    VARCHAR(64),

    state           VARCHAR(20)   NOT NULL DEFAULT 'QUARANTINED',
    -- FirewallQuarantineReason / FirewallQuarantineResolution. No CHECK: a new
    -- rule type must be able to name its own reason without a migration, the
    -- same trade V11 made for rule_type.
    reason_code     VARCHAR(40)   NOT NULL,
    resolution      VARCHAR(40),

    policy_id       UUID          REFERENCES firewall_policy(id) ON DELETE SET NULL,

    -- Snapshot of the decision that created the entry — matched rules, advisory
    -- ids, confidences, the request that tripped it. JSONB for the same reason
    -- firewall_violation.request_context is: the detail a UI shows evolves, and
    -- none of it is queried.
    evaluation      JSONB         NOT NULL DEFAULT '{}',

    first_seen      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    last_seen       TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    -- Bounded so a queue entry cannot grow without limit while a client retries
    -- a blocked download in a loop; the number is what tells an operator whether
    -- an entry is blocking one nightly job or the whole CI fleet.
    hit_count       BIGINT        NOT NULL DEFAULT 1,

    last_evaluated_at TIMESTAMPTZ,
    next_evaluation_at TIMESTAMPTZ,

    decided_at      TIMESTAMPTZ,
    decided_by      VARCHAR(200),
    decision_reason VARCHAR(1000),
    exemption_id    UUID          REFERENCES firewall_exemption(id) ON DELETE SET NULL,

    created_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    CONSTRAINT firewall_quarantine_state_check
        CHECK (state IN ('QUARANTINED', 'RELEASED', 'BLOCKED')),
    -- A decided entry has to say who and when. The scheduled re-evaluation
    -- writes 'system' here, exactly like a person would.
    CONSTRAINT firewall_quarantine_decided_is_complete
        CHECK (state = 'QUARANTINED' OR (decided_at IS NOT NULL AND resolution IS NOT NULL)),
    CONSTRAINT firewall_quarantine_unique_component
        UNIQUE (repository_id, component_key)
);

-- The request-path lookup: "is this component held in this repository?"
-- Covered by the unique constraint's index above, so no second index for it.

-- The queue view an operator reads.
CREATE INDEX idx_firewall_quarantine_state ON firewall_quarantine (state, first_seen DESC);
-- The re-evaluation sweep: due entries first, and only ones still held.
CREATE INDEX idx_firewall_quarantine_due
    ON firewall_quarantine (next_evaluation_at)
    WHERE state = 'QUARANTINED';
CREATE INDEX idx_firewall_quarantine_repository ON firewall_quarantine (repository_id, state);

-- ---------------------------------------------------------------------------
-- 3. Component facts
-- ---------------------------------------------------------------------------
--
-- What the ecosystem says about a component version: when it was published, and
-- what license it declares. Neither is in MegaRepo's own tables —
-- components.created_at is when *this instance* first saw the artifact, which
-- for a proxy is the moment somebody depended on it and has nothing to do with
-- the release date. A MIN_AGE rule reading it would quarantine a decade-old
-- library on its first download.
--
-- Reading package metadata or a registry API on the request thread is what the
-- customer forbade, so the facts are resolved in the background and this table
-- is what the request path reads. A rule that finds state UNKNOWN or PENDING
-- reports "indeterminate" and the fail mode decides — which is the third
-- quarantine trigger.
--
-- Keyed on the qualifier-free purl coordinates rather than on components(id):
-- the same package version exists as a separate component row in every proxy
-- that cached it, and the release date of log4j-core 2.14.1 is one fact, not
-- one per repository.
--
-- LICENSES ARE DECLARED METADATA ONLY. The design says so in section 6 and it is
-- a promise, not an implementation detail: nothing here is derived from scanning
-- file contents, and license_source records which declaration was read so a
-- LICENSE rule's verdict can be argued with.
CREATE TABLE firewall_component_facts (
    purl              VARCHAR(1000) PRIMARY KEY,
    purl_type         VARCHAR(50)   NOT NULL,

    state             VARCHAR(20)   NOT NULL DEFAULT 'UNKNOWN',

    -- Upstream publication time of this exact version. NULL with state RESOLVED
    -- means the metadata is genuinely silent about it, which is a fact and not a
    -- failure — a MIN_AGE rule cannot act on it and must say so.
    published_at      TIMESTAMPTZ,

    -- SPDX ids where the metadata gives them, verbatim otherwise. Empty with
    -- state RESOLVED means "declares no license", which is exactly the thing a
    -- deny-list policy usually wants to catch.
    declared_licenses TEXT[]        NOT NULL DEFAULT '{}',
    license_source    VARCHAR(20),

    source            VARCHAR(40),
    fetched_at        TIMESTAMPTZ,
    attempts          INTEGER       NOT NULL DEFAULT 0,
    error_message     VARCHAR(1000),

    created_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    CONSTRAINT firewall_component_facts_state_check
        CHECK (state IN ('UNKNOWN', 'PENDING', 'RESOLVED', 'UNAVAILABLE')),
    -- Declared metadata only — see the note above. A future value would have to
    -- be argued against the design, which is what this constraint is for.
    CONSTRAINT firewall_component_facts_license_source_check
        CHECK (license_source IS NULL
               OR license_source IN ('PACKAGE_METADATA', 'UPSTREAM_REGISTRY'))
);

-- The background resolver's work list: everything queued, oldest first.
CREATE INDEX idx_firewall_component_facts_pending
    ON firewall_component_facts (created_at)
    WHERE state IN ('UNKNOWN', 'PENDING');

-- Re-resolution sweeps and per-ecosystem diagnostics.
CREATE INDEX idx_firewall_component_facts_type_state
    ON firewall_component_facts (purl_type, state);
