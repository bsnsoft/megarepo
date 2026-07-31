# Design Proposal — Repository Firewall

Status: **awaiting approval** · osTicket #155155 (TASK 1) · supersedes the NVD Firewall

This is the STEP 0 deliverable. No implementation has started.

## 1. What the current code actually does

Read before writing this: `repository/nvd/*`, `RepositoryRouter`, `V8__nvd_firewall.sql`,
the `Nvd*` entities, `NvdFirewallController`, `NvdFirewallPage.tsx`.

The four weaknesses named in the request are all reproducible in the code:

| Weakness | Where | Detail |
|---|---|---|
| CPE guessing | `NvdCveLookupService.buildProductCandidates` | Candidates are derived from the **artifact name only**. `namespace` is accepted as a parameter and then never used. `com.acme:util` and `org.other:util` therefore match the same CPE product `util`. The "first segment before dash" rule (`log4j-core` → `log4j`) widens this further. |
| Check runs too late | `RepositoryRouter` ~line 112 | `checkDownload` is called on the `ContentResponse` **after** `handleProxyGet` has already fetched and cached the artifact. A blocked component is on disk before the 403 is written. |
| One global threshold | `nvd_firewall_settings` | Single row, `CHECK (id = 1)`, one `cvss_threshold` for every repository. |
| Whitelist never expires | `nvd_firewall_whitelist` | Has `reason` and `added_by`, but no expiry and no approver. `isComponentWhitelisted` also silently prefix-matches the coordinate without version, so a whitelist entry for one version quietly covers all of them. |

Two more worth naming, since they affect the schema:

- `VersionComparator` is generic. Maven qualifiers/`-SNAPSHOT`, PEP 440 `rc`/`post`,
  semver prerelease precedence and NuGet four-part versions are all mis-ordered by it.
- `NvdFirewallController` carries no `@PreAuthorize` at all.

## 2. Module layout

No new Gradle module for the core. The firewall stays in `megarepo-repository`, renamed
package `repository/firewall`, with `repository/nvd` kept as the NVD advisory source.

```
megarepo-repository/
  repository/firewall/
    FirewallEvaluationService      orchestrates: identify → advise → policy → decide
    identity/PurlBuilder           ComponentEntity → purl, per format
    identity/VersionScheme         interface + Maven/Pep440/Semver/NuGet/Generic impls
    policy/PolicyEngine            rule evaluation, returns Decision(action, violations)
    policy/rule/*                  one class per rule type
    quarantine/QuarantineService   state transitions + scheduled re-evaluation
    exemption/ExemptionService
  repository/advisory/
    AdvisorySource                 interface
    NvdAdvisorySource              wraps the existing NVD mirror
    OsvAdvisorySource              OSV.dev, purl-native
    GhsaAdvisorySource             GitHub Advisories
    AdvisoryMergeService           merge per purl, de-dup by CVE/GHSA id
```

`PurlBuilder` needs per-format knowledge that already lives in the format modules. To avoid
`megarepo-repository` depending on all six, each format module contributes a small
`PurlMapper` bean (`format()` + `toPurl(ComponentEntity)`), collected by Spring. Formats
without coordinates (raw, docker layers) return empty and fall back to hash identity.

`packageurl-java` (`com.github.package-url:packageurl-java`) as the purl implementation —
Apache-2.0, no transitive weight. Maven ordering uses `maven-artifact`'s `ComparableVersion`,
which is already on the classpath via `megarepo-format-maven`.

## 3. Schema (V9 onward, forward-only)

`V9__firewall_policies.sql`

```
firewall_policy            id, name, description, is_default, created_at/by
firewall_policy_rule       id, policy_id, rule_type, action (WARN|BLOCK), config JSONB, enabled
firewall_repository_config repository_id PK, mode (OFF|AUDIT|QUARANTINE), policy_id, fail_mode
```

`rule_type` ∈ `CVSS_THRESHOLD`, `KNOWN_MALICIOUS`, `LICENSE`, `MIN_AGE`, `UNKNOWN_COMPONENT`,
`TYPOSQUAT`, `NAMESPACE_CONFUSION`. Rule parameters live in `config` JSONB rather than
one column per rule type, so a new rule type is a code change and not a migration.

`V10__firewall_advisories.sql`

```
advisory                   id (GHSA/CVE/OSV id) PK, source, summary, severity, cvss_score,
                           cvss_vector, published, modified, withdrawn_at
advisory_affected          id, advisory_id, purl_type, purl_namespace, purl_name,
                           version_range (raw), introduced, fixed, last_affected
advisory_sync_state        source PK, status, cursor, last_success_at, error_message
```

Indexed on `(purl_type, purl_namespace, purl_name)`. The existing `cve_entries` /
`cve_affected_products` tables stay as the NVD source's own storage — `NvdAdvisorySource`
reads them and normalises into `advisory`. Nothing is dropped in V9/V10.

`V11__firewall_quarantine_exemptions.sql`

```
firewall_quarantine        id, repository_id, purl, hash, state (QUARANTINED|RELEASED|BLOCKED),
                           reason_code, first_seen, decided_at, decided_by, evaluation JSONB
firewall_violation         id, repository_id, purl, policy_id, rule_type, action,
                           advisory_ids TEXT[], occurred_at, request_context JSONB
firewall_exemption         id, scope_type (VERSION|COMPONENT), purl, repository_id NULL=all,
                           expires_at NULL=never, justification, requested_by, approved_by,
                           approved_at, state (REQUESTED|APPROVED|REJECTED|EXPIRED)
```

`V12__firewall_migrate_v8.sql` — data migration, no drops:

- `nvd_firewall_settings.cvss_threshold` → one `firewall_policy` named "Default"
  (`is_default = true`) with a single `CVSS_THRESHOLD` rule, action `BLOCK`.
- `nvd_firewall_settings.enabled` decides the *global* default mode: `QUARANTINE` if it was
  on, `OFF` if it was off. Every existing repository gets an explicit
  `firewall_repository_config` row with that mode, so upgrade behaviour is identical to today.
  New repositories created after the upgrade default to `AUDIT` as requested.
- `nvd_firewall_whitelist` rows → `firewall_exemption` with `expires_at = NULL`,
  `state = APPROVED`, `approved_by = added_by`, justification carried over from `reason`.
  `COMPONENT` entries become `scope_type = COMPONENT`, `CVE` entries become a policy-level
  advisory-id ignore list (they are not component-scoped and do not map onto an exemption).
- `nvd_firewall_blocks` → `firewall_violation`, so the existing history stays visible.

The V8 tables are left in place and unused for one release, then dropped in a later
migration. That keeps a rollback possible.

## 4. Where the check moves to

The evaluation hook moves **before** the upstream fetch in the proxy path, not after the
`ContentResponse`. Concretely: `RepositoryRouter` gains a pre-fetch evaluation for
`PROXY` repositories keyed on the coordinates parsed from the path, and the existing
post-response call is kept only for `AUDIT` bookkeeping on already-cached content.

Hosted uploads are evaluated in the upload path. Components already present in a
repository are audited only — the quarantine service never transitions an existing
component into `QUARANTINED`, which is what keeps current builds working.

Request threads never make network calls. The local `advisory` table is the fast path;
a miss enqueues an async refresh and the decision is made on the "unknown component"
rule instead of waiting.

## 5. Delivery

Matches the three phases in the request:

- **Phase 1** — purl identity, version schemes, `AdvisorySource` + OSV/GHSA ingestion,
  AUDIT mode only. Ships a comparison report: current CPE matching vs purl matching over
  real repository data, so the false-positive claim is measured and not asserted.
- **Phase 2** — quarantine, per-repo modes, policy engine, exemptions, 403 bodies.
- **Phase 3** — PCCS version filtering, webhooks, CI/SBOM endpoint, dashboard.

Each phase leaves the previous behaviour intact until the phase that replaces it lands.

## 6. Out of scope — stated, not faked

- Sonatype's proprietary malware intelligence and Release Integrity ratings cannot be
  replicated. Findings will carry an explicit `source` and `confidence` so users can weigh
  them; heuristic rules (typosquat, namespace confusion) will be labelled as heuristics.
- No reachability analysis.
- No license detection from file contents — declared metadata only.

## 7. Open points that need a decision before Phase 1

1. **`exemption` vs `waiver`.** Item 7 says to use "exemption" throughout and explicitly not
   "waiver". Items 10 and 12 then say "waiver CRUD + requests" and "waiver management".
   This proposal uses `exemption` everywhere (entity `PolicyExemption`, `/exemptions`,
   UI "Exemptions") per item 7. Please confirm.
2. **The latency budget is missing.** The constraint reads "a cache hit must add" and then
   breaks off. What is the number?
3. **`KNOWN_MALICIOUS` source.** OSV carries malicious-package advisories (`MAL-` ids) via
   the malicious-packages feed. Confirm that feed is acceptable as the sole signal, given
   Sonatype's own intelligence is out of scope.
4. **Global vs per-repo policy precedence.** When a repository has an assigned policy and a
   global default exists, does the repository policy replace the global one or stack on top?
   This proposal assumes **replace**.
