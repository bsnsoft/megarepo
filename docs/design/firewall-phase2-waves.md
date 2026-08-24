# Repository Firewall Phase 2 — work packages

osTicket #155155 · branch `feat/firewall-phase2` · supersedes nothing, extends
Phase 1 (merged as `8cf025e`)

This document cuts Phase 2 into packages that can be implemented in parallel.
It is written for the agent picking up one package: what it owns, what it may
assume, what it must not touch, and what its tests have to prove.

The contract commit on this branch is the reason the packages are independent.
Schema, enums, entities, service interfaces, the rule SPI and the API DTOs are
already in place and are **not** a package's to change — a package that finds
the contract wrong raises it rather than editing around it, because five other
packages are compiling against the same file.

---

## 0. What Phase 1 already delivered

Worth knowing before assuming something is missing.

| Already there | Where |
|---|---|
| purl identity per format, version schemes | `repository/firewall/identity/**`, `PurlMapper` beans in each format module |
| Advisory ingest + merge (NVD/OSV/GHSA), confidence labelling | `repository/advisory/**`, tables `advisory`, `advisory_affected`, `advisory_sync_state` (V12/V14/V15) |
| AUDIT observation path, violation log | `FirewallEvaluationService`, `FirewallDownloadObserver`, `FirewallViolationRecorder`, table `firewall_violation` (V13) |
| Enforcement path with timeout + fail mode | `FirewallEnforcementService`, `megarepo.firewall.enforcement.*` |
| Global master switch, grandfathering watermark | `FirewallEnforcementSettingsService`, table `firewall_enforcement_settings` (V16) |
| Policy model + two rules (CVSS_THRESHOLD, KNOWN_MALICIOUS) | `FirewallPolicyEvaluator`, tables `firewall_policy`, `firewall_policy_rule` (V11), seeded default policy (V16) |
| Per-repository mode + fail mode | `firewall_repository_config` (V11), `FirewallAdminController` |
| 403 body/headers for Maven, npm and NuGet clients | `FirewallBlockResponse` |
| Group routing: the resolving member decides | `RepositoryRouter`, `FirewallGroupEndToEndTest` |

Two things Phase 1 deliberately did **not** do, and Phase 2 does: it never held
anything (a denied download was simply refused, with no state and no release
path), and it never evaluated an upload.

---

## 1. What the contract commit adds

### Schema

| Migration | Tables / rows | Notes |
|---|---|---|
| `V17__firewall_quarantine_exemptions.sql` | `firewall_exemption`, `firewall_quarantine`, `firewall_component_facts` | Three empty tables. Changes no behaviour on upgrade. |
| `V18__firewall_migrate_nvd_whitelist.sql` | rows in `firewall_exemption` | V8 `COMPONENT` whitelist rows become non-expiring approved exemptions. `CVE` rows are **not** migrated — see §5.2. |
| `V19__firewall_phase2_tasks.sql` | rows in `scheduled_tasks` | Three jobs: quarantine re-evaluation (15 min), exemption expiry (daily 06:00), component-facts resolution (10 min). All with an explicit `next_run`. |

V17 is documented in the migration itself; the load-bearing decisions are
repeated in §5 below.

### Enums (`megarepo-core`, package `core.firewall`)

`FirewallQuarantineState`, `FirewallQuarantineReason`,
`FirewallQuarantineResolution`, `FirewallExemptionScope`,
`FirewallExemptionState`, `FirewallComponentKeyKind`, `FirewallFactsState`.

### Entities and JPA repositories (`megarepo-database`)

`FirewallQuarantineEntity`, `FirewallExemptionEntity`,
`FirewallComponentFactsEntity` and one repository each. The repositories carry
the queries the request path and the sweeps need — `findApplicable`,
`findDueForReevaluation`, `findExpired`, `findDueForExpiryNotice`,
`findUnresolved`, `recordHit`. Adding a query is fine; changing an existing
signature is a contract change.

### Interfaces (`megarepo-repository`)

```java
// firewall/rule — one bean per rule type, collected like PurlMapper
interface FirewallRule {
    FirewallRuleType ruleType();
    FirewallRuleOutcome evaluate(FirewallRuleContext context, FirewallRuleSettings settings);
    default boolean quarantineOnMatch()                  { return false; }
    default FirewallQuarantineReason quarantineReason()  { return EVALUATION_INCOMPLETE; }
    default boolean appliesToUnidentifiedComponents()    { return false; }
}

record FirewallRuleOutcome(Kind kind, FirewallRuleViolation violation, String reason)
    // Kind = NOT_MATCHED | MATCHED | INDETERMINATE
    static notMatched() / matched(violation) / indeterminate(reason)

record FirewallRuleContext(UUID repositoryId, String repositoryName, RepositoryType repositoryType,
                           String path, ComponentIdentity identity, List<AdvisoryFinding> findings,
                           ComponentFacts facts, FirewallRepositorySettings settings,
                           boolean upload, boolean preExisting, Instant evaluatedAt)
    List<AdvisoryFinding> findingsAtLeast(MatchConfidence min)

record FirewallRuleSettings(UUID id, FirewallRuleType ruleType, FirewallAction action,
                            Map<String,Object> config, boolean enabled)
    double number(k, fb) · boolean flag(k, fb) · String text(k, fb)
    List<String> textList(k, fb) · Duration duration(k, fb) · MatchConfidence minConfidence()

class FirewallRuleRegistry            // implemented: indexes beans, rejects duplicates at startup,
    Optional<FirewallRule> find(t)    // contains exceptions as INDETERMINATE
    boolean isImplemented(t) · Set<FirewallRuleType> implemented()
    FirewallRuleOutcome evaluate(FirewallRuleContext, FirewallRuleSettings)
```

```java
// firewall/facts — the local cache that makes MIN_AGE and LICENSE possible
interface ComponentFactsService {
    ComponentFacts lookup(ComponentIdentity identity);                       // local read, never blocks
    Map<String, ComponentFacts> lookupAll(Collection<ComponentIdentity>);
    void requestResolution(ComponentIdentity identity);                      // enqueue, returns at once
}

interface ComponentFactsSource {                                            // one bean per ecosystem
    String purlType();
    default Set<String> purlTypeAliases() { return Set.of(); }
    Optional<ResolvedFacts> resolve(PackageURL purl) throws ComponentFactsException;
    record ResolvedFacts(Instant publishedAt, List<String> declaredLicenses,
                         String licenseSource, String source)
}

record ComponentFacts(String purl, FirewallFactsState state, Instant publishedAt,
                      List<String> declaredLicenses, String licenseSource,
                      String source, Instant fetchedAt)
    boolean isSettled() · boolean isIndeterminate() · Optional<Duration> age(Instant)
```

```java
// firewall/quarantine
interface QuarantineService {
    Optional<FirewallQuarantineEntry> find(UUID repositoryId, String componentKey);
    void recordHit(UUID quarantineId, Instant seenAt);
    Optional<FirewallQuarantineEntry> quarantine(FirewallEvaluation, FirewallQuarantineReason,
                                                 FirewallRequestContext);
    FirewallQuarantineEntry release(UUID id, QuarantineDecision decision);
    FirewallQuarantineEntry block(UUID id, QuarantineDecision decision);
    Page<FirewallQuarantineEntry> queue(QuarantineQuery query, Pageable pageable);
    QuarantineSummary summary();
    int reevaluateDue(Instant now, int limit);
    int invalidatePolicy(UUID policyId);
}
```

```java
// firewall/exemption
interface ExemptionService {
    List<FirewallExemption> findApplicable(UUID repositoryId, ComponentIdentity, Instant at);
    Optional<FirewallExemption> findApplicable(UUID repositoryId, ComponentIdentity,
                                               FirewallRuleType, Instant at);
    FirewallExemption request(ExemptionRequest request);
    FirewallExemption approve(UUID id, String approver, String note, Instant expiresAt);
    FirewallExemption reject(UUID id, String approver, String note);
    FirewallExemption revoke(UUID id, String approver, String note);
    Optional<FirewallExemption> find(UUID id);
    Page<FirewallExemption> list(ExemptionQuery query, Pageable pageable);
    ExemptionSummary summary();
    int expireLapsed(Instant now);
    List<FirewallExemption> notifyUpcomingExpiry(Instant now, Duration lead);
}
```

### Configuration

`megarepo.firewall.quarantine.*`, `.exemption.*`, `.facts.*`, `.block.*` —
`QuarantineProperties`, `ExemptionProperties`, `ComponentFactsProperties`,
`FirewallBlockProperties`, all registered in `FirewallAuditConfiguration`. A
package needing a new property adds it to the record that already exists for its
area; nobody edits `FirewallAuditConfiguration` again.

### API DTOs (`megarepo-rest-api`, `rest/dto/firewall`)

`FirewallQuarantineEntryXO`, `FirewallQuarantineDecisionXO`,
`FirewallExemptionXO`, `FirewallExemptionRequestXO`,
`FirewallExemptionDecisionXO`, `FirewallPolicyXO`, `FirewallPolicyRuleXO`,
`FirewallPolicyUpsertXO`, `FirewallRepositoryPolicyUpdateXO`,
`FirewallRuleTypeXO`.

The UI package can start against these without waiting for a controller.

---

## 2. Wave A — five packages, fully parallel

No package in this wave reads another package's implementation. They meet at the
contract and nowhere else.

### A1 · Quarantine state machine and automatic release

**Owns**

```
megarepo-repository/…/repository/firewall/quarantine/DefaultQuarantineService.java
megarepo-repository/…/repository/firewall/quarantine/QuarantineMapper.java
megarepo-repository/…/repository/firewall/quarantine/QuarantineReevaluator.java
megarepo-repository/…/repository/firewall/FirewallUploadEvaluator.java
megarepo-tasks/…/tasks/firewall/QuarantineReevaluationTask.java
megarepo-tasks/…/tasks/advisory/AdvisorySyncTask.java            (edit: post-sync hook only)
+ tests
```

**Scope**

* Implement `QuarantineService`. The state machine is
  `QUARANTINED → RELEASED | BLOCKED`, and nothing goes back without a fresh
  trigger.
* `quarantine(...)` returns empty — records nothing — when quarantine is
  disabled, when the component predates the enforcement watermark
  (`FirewallEvaluation.preExisting()`), or when there is no usable component
  key. The pre-existing check lives here, not in the caller.
* `reevaluateDue` re-runs the policy for due entries and releases what has
  become acceptable, recording a `FirewallQuarantineResolution` and a sentence.
  A `MIN_AGE` entry is scheduled for the exact moment it becomes old enough
  rather than being re-polled; everything else backs off between
  `min-reevaluation-interval` and `max-reevaluation-interval`.
* The task (V19 type `security.firewall.quarantine.reevaluate`) and the
  post-advisory-sync hook both call `reevaluateDue`. One code path, two
  triggers.
* `FirewallUploadEvaluator`: evaluate an upload into a hosted repository the
  same way a download is evaluated. **It exposes a method; it does not wire
  itself into `RepositoryRouter`** — the router belongs to B1.

**Depends on** contract only.

**Must not touch** `RepositoryRouter`, `FirewallEnforcementService`,
`FirewallPolicyEvaluator`, `FirewallBlockResponse`.

**Tests**

* State-machine unit tests: every legal transition, and that an illegal one
  throws rather than being silently absorbed.
* A pre-existing component is never quarantined — the customer's hardest
  constraint, asserted directly.
* Quarantine disabled: `quarantine()` writes nothing, `find()` returns empty,
  existing rows are untouched.
* Re-evaluation releases a `MIN_AGE_NOT_MET` entry once the clock passes the
  threshold, with resolution `AGE_REACHED`, and does not touch an entry that is
  not due.
* `recordHit` increments without loading the entity.
* An upload of a component the policy denies is refused; an upload of a clean
  one is not.

---

### A2 · Component facts store and resolver

**Owns**

```
megarepo-repository/…/repository/firewall/facts/DefaultComponentFactsService.java
megarepo-repository/…/repository/firewall/facts/ComponentFactsResolver.java
megarepo-format-maven/…/format/maven/firewall/MavenComponentFactsSource.java
megarepo-format-npm/…/format/npm/firewall/NpmComponentFactsSource.java
megarepo-format-pypi/…/format/pypi/firewall/PypiComponentFactsSource.java
megarepo-format-nuget/…/format/nuget/firewall/NuGetComponentFactsSource.java
megarepo-tasks/…/tasks/firewall/ComponentFactsTask.java
+ tests
```

**Scope**

* `lookup` reads `firewall_component_facts` and returns whatever is there. **It
  never fetches.** A miss inserts an `UNKNOWN` row and returns
  `ComponentFacts.unknown(...)`; the rule reports `INDETERMINATE` and the fail
  mode decides. This is the whole reason the table exists — the 20 ms budget and
  "no network on a request thread" are the customer's constraints, not
  guidance.
* `ComponentFactsResolver` drains the queue on a small background pool and is
  also driven by the V19 task (`security.firewall.facts.resolve`), which catches
  what a restart lost.
* Per-format sources read **declared metadata only**: the stored POM, the
  `package.json`, the `METADATA`, the `.nuspec`, or the upstream registry
  document. Prefer the locally stored descriptor when
  `prefer-local-metadata` is on — it costs no outbound request and describes the
  artifact this instance actually serves. **No license detection from file
  contents**; that is a stated scope boundary, and
  `firewall_component_facts.license_source` has a CHECK constraint that enforces
  it.
* Outbound HTTP goes through `megarepo.outbound-proxy`, like everything else.
* `RESOLVED` with a null `published_at` is a settled answer, not a pending one.
  Rows that can never be resolved end `UNAVAILABLE` after `max-attempts`.

**Depends on** contract only.

**Must not touch** any rule implementation, the router, the policy evaluator.

**Tests**

* Never-fetches: a `lookup` on a miss issues no outbound call (asserted with a
  source stub that fails the test if called) and returns `UNKNOWN`.
* `requestResolution` is idempotent — calling it on every download of the same
  component produces one queue row.
* Per-format source tests against recorded fixtures, offline. Never against a
  live registry.
* A source that throws marks attempts and leaves the row unresolved without
  taking the other ecosystems down; after `max-attempts` the row is
  `UNAVAILABLE`.
* A package whose metadata states no license resolves to `RESOLVED` with an
  empty array, distinct from `UNAVAILABLE`.

---

### A3 · Rules: MIN_AGE, UNKNOWN_COMPONENT, LICENSE

**Owns**

```
megarepo-repository/…/repository/firewall/rule/impl/MinimumAgeRule.java
megarepo-repository/…/repository/firewall/rule/impl/UnknownComponentRule.java
megarepo-repository/…/repository/firewall/rule/impl/LicenseRule.java
+ tests
```

**Scope**

* `MIN_AGE` — config `{"minAge": "P7D"}` or `{"minAge": 7}` (days).
  `quarantineOnMatch() = true`, reason `MIN_AGE_NOT_MET`. Facts
  `UNKNOWN`/`PENDING` → `INDETERMINATE`. Facts `RESOLVED` with no date, or
  `UNAVAILABLE` → `NOT_MATCHED`: a settled "we cannot know" must not hold a
  component forever.
* `UNKNOWN_COMPONENT` — matches when no advisory names the component, or when
  the component has no purl at all. The only rule with
  `appliesToUnidentifiedComponents() = true`. `quarantineOnMatch() = true`,
  reason `UNKNOWN_COMPONENT`. Config `{"allowUnidentifiedFormats": ["raw"]}` so
  an operator can exempt formats that structurally have no coordinates instead
  of quarantining every raw file in the instance.
* `LICENSE` — config `{"allowed": [...]}` and/or `{"denied": [...]}`,
  `{"allowUndeclared": true|false}`. Declared metadata only. SPDX comparison
  case-insensitive; an expression the rule cannot parse is `INDETERMINATE`, not
  a match. Does **not** quarantine — a license verdict does not change by
  waiting.

**Depends on** the `ComponentFactsService` **interface** (contract). Implement
against a stub; A2 supplies the real one. That is what makes A2 and A3
concurrent.

**Must not touch** the registry, the evaluator, the facts implementation.

**Tests**

* One unit test class per rule, constructing `FirewallRuleContext` directly. No
  Spring, no database.
* The `INDETERMINATE` cases explicitly — they are the reason the outcome type
  has three values, and a rule that quietly returns `NOT_MATCHED` on missing
  facts is the bug this design exists to prevent.
* Malformed config falls back to the default and does not match.
* A `BLOCK` rule only acts on `EXACT` findings unless `minConfidence` says
  otherwise.

---

### A4 · Rules: TYPOSQUAT, NAMESPACE_CONFUSION

**Owns**

```
megarepo-repository/…/repository/firewall/rule/impl/TyposquatRule.java
megarepo-repository/…/repository/firewall/rule/impl/NamespaceConfusionRule.java
megarepo-repository/…/repository/firewall/rule/corpus/**
+ tests
```

**Scope**

* Both are **heuristics** and say so: the violation reason names its evidence —
  the package the name resembles, the edit distance, the namespace the name was
  expected under. A build log that says "TYPOSQUAT" and nothing else is
  indistinguishable from an accusation.
* `TYPOSQUAT` — compares an incoming proxied coordinate against a corpus of
  names this instance already trusts. **Recommended corpus: the local
  `components` table**, i.e. the packages this organisation actually uses, held
  in memory and refreshed periodically. Those are exactly the names worth
  squatting, it needs no external feed, no new table and no network, and it
  cannot be gamed by whoever publishes the corpus. If the package concludes a
  stored corpus is unavoidable, that is a contract change — raise it, do not add
  a migration; V20 onwards is unclaimed but the schema is reviewed, not
  invented.
* Config: `{"maxDistance": 1, "minPopularity": …, "ignore": [...]}`. Only
  matches on a **proxied** component (`context.fromProxy()`) — a package
  published into a hosted repository by a colleague is not a typosquat of
  anything.
* `NAMESPACE_CONFUSION` — internal coordinates must never arrive from an
  upstream proxy. Internal namespaces come from
  `{"internalNamespaces": ["com.acme", "com.acme.*"]}` and, when
  `{"deriveFromHostedRepositories": true}`, from the namespaces present in
  hosted repositories. Matches only when `context.fromProxy()`.
* Neither quarantines. Both default to `WARN` in any policy this package seeds
  or documents: a heuristic set to BLOCK on day one is a heuristic that gets the
  whole firewall switched off.

**Depends on** contract only.

**Tests**

* Unit tests with a synthetic corpus: the classic near-misses match, the real
  package does not match itself, and a hosted upload never matches.
* Distance thresholds at the boundary, both sides.
* An internal namespace arriving from a proxy matches; the same namespace in a
  hosted repository does not.
* Every violation reason contains the evidence, asserted as text.

---

### A5 · Exemptions: service, legacy matching, API, expiry

**Owns**

```
megarepo-repository/…/repository/firewall/exemption/DefaultExemptionService.java
megarepo-repository/…/repository/firewall/exemption/ExemptionKeyBuilder.java
megarepo-repository/…/repository/firewall/exemption/ExemptionMapper.java
megarepo-rest-api/…/rest/controller/FirewallExemptionController.java
megarepo-tasks/…/tasks/firewall/ExemptionExpiryTask.java
+ tests
```

**Scope**

* Implement `ExemptionService`. `/api/v1/firewall/exemptions` — **the word is
  "exemption" everywhere**; `waiver` and `whitelist` must not appear in any
  identifier, path, message or comment this package writes.
* `ExemptionKeyBuilder` is the load-bearing piece. For a component it produces
  every key an exemption may name it by:
  1. the purl identity key (`ComponentIdentity.key()`) — scope `VERSION`;
  2. the version-less purl — scope `COMPONENT`;
  3. while any `LEGACY_COORDINATE` row exists: the V8 coordinate
     `format:namespace:name:version` (`NvdFirewallService.buildComponentKey` is
     already `public static`) and its version-less prefix.
  All four go into one `findApplicable` query. This reproduces the V8 matcher
  exactly, which is the requirement — an operator's existing whitelist entry has
  to go on working the day after the upgrade. Skip 3–4 when
  `countByKeyKind(LEGACY_COORDINATE) == 0`.
* Expiry is a **stored** transition driven by the V19 task
  (`security.firewall.exemption.expiry`), *and* `findApplicable` filters on
  `expires_at` as well — an exemption that lapsed at noon stops applying at
  noon, not at the next sweep.
* The expiry notice fires once per exemption (`expiry_notified_at`).
* Approval requires an explicit expiry decision: null means never and the API
  does not default to it. The UI pre-fills `default-validity`.
* Authorization follows `FirewallAdminController`'s existing filter-chain
  convention; requesting is available to any authenticated user when
  `self-service-requests` is on, approving is not.

**Depends on** contract only.

**Must not touch** the policy evaluator (B1 consumes this service),
`FirewallAdminController`.

**Tests**

* A migrated legacy row keeps working: a `maven2:com.acme:util:1.0.0` exemption
  matches the component whose purl is `pkg:maven/com.acme/util@1.0.0`, and a
  version-less legacy row matches every version — the V8 prefix rule,
  reproduced.
* Scope matrix: version × component × this-repository × all-repositories, all
  four combinations, positive and negative.
* An expired exemption blocks again — asserted through `findApplicable` before
  the sweep has run, and again after.
* Rule-scoped exemption suppresses its rule and no other.
* State machine: approve/reject/revoke from legal states, refusal from illegal
  ones.
* Controller slice tests including the 403 for an unauthorised approval.

---

## 3. Wave B — after A

### B1 · Enforcement wiring

**Owns**

```
megarepo-repository/…/repository/firewall/FirewallPolicyEvaluator.java        (rewrite onto the SPI)
megarepo-repository/…/repository/firewall/FirewallEnforcementService.java     (quarantine + exemptions)
megarepo-repository/…/repository/firewall/FirewallBlockResponse.java          (extend)
megarepo-repository/…/repository/firewall/FirewallViolationRecorder.java      (record exemption/quarantine)
megarepo-repository/…/repository/RepositoryRouter.java                        (GET hook + PUT hook)
megarepo-app/…/app/firewall/*EndToEndTest.java                                (extend)
```

**Scope**

* `FirewallPolicyEvaluator` stops switching on rule type and asks
  `FirewallRuleRegistry`. Its two existing rules move into
  `rule/impl/CvssThresholdRule` and `rule/impl/KnownMaliciousRule` — behaviour
  unchanged, including the `BLOCK ⇒ EXACT` confidence default, which is now
  `FirewallRuleSettings.minConfidence()`.
* Decision assembly, in this order:
  1. an existing quarantine entry short-circuits (`denies()` → refuse, count a
     hit; `RELEASED` → serve);
  2. rules are evaluated;
  3. a matched rule covered by a live exemption is recorded and suppressed —
     which exemption did it goes into the violation row;
  4. a matched `BLOCK` rule with `quarantineOnMatch()` → quarantine under the
     rule's reason; any other matched `BLOCK` rule → plain refusal;
  5. `INDETERMINATE` outcomes with `FAIL_CLOSED` → quarantine under
     `EVALUATION_INCOMPLETE`; with `FAIL_OPEN` → serve;
  6. `preExisting` still never denies. That check belongs here and in
     `QuarantineService`, and in nothing else.
* `FirewallBlockResponse` gains the policy name, the exemption-request link and
  the configurable contact message from `FirewallBlockProperties`. The
  configurable text is **appended**: no configuration may produce a 403 that
  fails to say what was blocked and why.
* `RepositoryRouter`: the GET hook keeps its current shape; the PUT path gains
  the hosted-upload evaluation through `FirewallUploadEvaluator` (A1). Through a
  group the resolving member still decides — do not regress
  `FirewallGroupEndToEndTest`.

**Depends on** A1, A3, A4, A5 (and A2 transitively through A3).

**Tests**

* Extend `FirewallSwitchEndToEndTest` / `FirewallGroupEndToEndTest`: switch off
  ⇒ nothing blocks; QUARANTINE + switch on + a quarantining rule ⇒ 403 and a
  queue entry; release ⇒ the same download succeeds.
* An approved exemption turns a block into a served download, and the violation
  row records the exemption id.
* An upload of a denied component is refused and the artifact is not published.
* A pre-existing component is served in every configuration.
* Latency: an enforced cache hit stays inside the budget with warm data —
  assert on query count rather than wall-clock, so it is not flaky in CI.

---

### B2 · Admin API completion

**Owns**

```
megarepo-rest-api/…/rest/controller/FirewallPolicyController.java
megarepo-rest-api/…/rest/controller/FirewallQuarantineController.java
megarepo-rest-api/…/rest/controller/FirewallAdminController.java   (extend: rule-types, repo policy)
+ tests
```

**Scope**

* Policies: list, read, create, replace, delete, set-default. Replace takes the
  complete rule set (`FirewallPolicyUpsertXO`), not a delta. Moving the default
  flag clears it from the previous holder in one transaction.
* Repository policy assignment (`FirewallRepositoryPolicyUpdateXO`). **A
  repository policy replaces the global default; it does not stack.** Confirmed
  by the customer; the UI copy has to say so too.
* Quarantine queue: list with filters, read one, release, block.
* `GET /api/v1/firewall/rule-types` → `FirewallRuleTypeXO`, built from
  `FirewallRuleRegistry.implemented()` plus each rule's `quarantineOnMatch()`.
  The editor renders an unimplemented rule as "not enforced by this version"
  rather than as a working switch.
* Any policy edit that changes what an enforcing repository denies requires the
  same typed confirmation `FirewallAdminController` already demands for arming a
  repository, and calls `QuarantineService.invalidatePolicy` so a loosened
  policy takes effect immediately instead of at the next sweep.

**Depends on** A1, A5, B1 (for the registry being populated).

**Tests** Controller slice tests: validation, the confirmation guard, the
single-default invariant, authorization, and that `invalidatePolicy` is called
on a policy edit.

---

### B3 · Web UI

**Owns**

```
megarepo-web-ui/frontend/src/pages/security/firewall/**            (new)
megarepo-web-ui/frontend/src/pages/security/RepositoryFirewallPage.tsx  (extend)
megarepo-web-ui/frontend/src/App.tsx, layout/Sidebar.tsx           (routes/nav)
```

**Scope**

* Quarantine queue: entry list with reason, age, hit count and — the single most
  useful column — *when it will be released*, from `nextEvaluationAt`. Actions:
  release, block, request exemption.
* Policy editor: rules from `/rule-types`, fields rendered from `configSchema`
  rather than a JSON textarea. Heuristic rules visibly labelled as heuristics at
  the point where somebody would set one to BLOCK.
* Per-repository policy assignment, alongside the existing mode control.
* Exemption management: approval queue, expiry column, an obvious expiry field
  pre-filled with `default-validity`, and a visible marker on
  `LEGACY_COORDINATE` rows.
* Violation detail: purl, sources, confidence, advisory links.
* **No dashboard.** That is Phase 3, and so are PCCS filtering, webhooks and the
  CI/SBOM endpoint.

**Depends on** the DTOs (contract) to start; on A5/B2 to run end to end.

**Tests** Component tests plus one bcurl click-through of each new screen
before the package is called done.

---

## 4. Dependency graph

```
        contract (this branch)
   ┌────────┬────────┬────────┬────────┐
   A1       A2       A3       A4       A5          ← wave A, parallel
 quarantine facts   rules    rules  exemptions
   │        └──▶ A3 │        │        │
   └────────────────┴────────┴────────┘
                    ▼
                    B1  enforcement wiring
                    ▼
                    B2  admin API           B3  UI (starts on the DTOs,
                     └──────────────────────┘    finishes against B2/A5)
```

A3 compiles against A2's interface, so both start at the same time; only A3's
integration test needs A2 merged.

## 5. Decisions that differ from the design proposal, and why

### 5.1 Quarantine is not "everything the policy denied"

The proposal treated `firewall_quarantine` as the record of blocked components.
It is not, and the customer's wording is explicit: quarantine is rule-driven and
never blanket. Only three verdicts produce an entry — `MIN_AGE_NOT_MET`,
`UNKNOWN_COMPONENT`, `EVALUATION_INCOMPLETE` — and all three are expected to
resolve on their own. A critical advisory or a malicious package is refused with
no entry at all.

The reason is operational. A queue that fills with things nobody will ever
release stops being read, and a "release" button next to a credential stealer is
an invitation. Automatic release only makes sense for verdicts that can change,
which is the same set.

### 5.2 The V8 whitelist keeps its own key format

The proposal assumed V8 whitelist rows could be migrated into purl-keyed
exemptions. They cannot: a V8 value is `format:namespace:name:version` built
from the raw format key with no purl type mapping and no per-ecosystem name
normalisation, and producing the purl needs `PurlMapper`, which is not reachable
from Flyway.

Guessing in SQL fails in both directions — a key that no longer matches breaks a
build for a component the operator had explicitly allowed, and one that matches
more is a hole nobody opened. So the value is stored verbatim under
`key_kind = LEGACY_COORDINATE` and the matcher reproduces the V8 comparison for
those rows (§A5). Scope is *derived* from the old matcher's behaviour rather than
assumed: three colons matched one version, two matched every version.

Legacy `CVE` whitelist rows are **not** migrated. "Ignore this advisory
everywhere" has no component to scope an exemption to, and widening it into one
is not a migration's decision. They stay in `nvd_firewall_whitelist`;
`firewall_exemption.advisory_ids` exists so an operator can restate them
component-scoped, and the Exemptions page surfaces them as needing a decision.

`nvd_firewall_blocks → firewall_violation` is also dropped from the plan: a V8
block row has a legacy key, no purl, no policy and no rule type, and writing it
into the purl-keyed log under an invented rule type would corrupt the one table
Phase 1 built to be trustworthy.

### 5.3 A new table the proposal did not have: `firewall_component_facts`

`MIN_AGE` needs a publication date and `LICENSE` needs a declared license, and
neither is in MegaRepo's tables. `components.created_at` is when *this instance*
first saw the artifact — for a proxy, the moment somebody depended on it. A
MIN_AGE rule reading it would quarantine a decade-old library on its first
download, which is not a tuning problem but a wrong answer.

The real facts come from package metadata, and reading metadata on the request
thread is exactly what the customer forbade. Hence a background-filled cache
with an explicit state, and hence `INDETERMINATE` as a rule outcome.

### 5.4 Rules may answer "I cannot decide"

Phase 1's evaluator returned matched-or-not. With facts that may not have
arrived, both answers are guesses that fail in opposite directions: "not
matched" serves a package that might be four minutes old, "matched" quarantines
the whole repository until the resolver catches up. `INDETERMINATE` hands the
decision to the engine, which knows the fail mode; fail-open serves, fail-closed
quarantines under `EVALUATION_INCOMPLETE` — the customer's third trigger, which
otherwise had no mechanism behind it.

### 5.5 `FirewallExemptionState.REVOKED`

The proposal listed REQUESTED/APPROVED/REJECTED/EXPIRED. Withdrawing an
exemption early then means deleting the row — destroying the record of a
decision that was live in production — or backdating the expiry, which makes the
log claim it lapsed by itself. A fifth constant is cheaper than either.

### 5.6 No typosquat corpus table

The proposal implied an external popularity feed. The local `components` table
is a better corpus for this purpose: it is the set of packages the organisation
actually uses, which is precisely what an attacker would squat, and it needs no
feed, no table, no network and no trust in whoever publishes the list. Held in
memory, refreshed periodically. If A4 finds this insufficient, it is a contract
change, not a migration a package writes on its own.

### 5.7 Migration numbering

The design's V9–V12 are long gone. Phase 1 occupied V11–V16; Phase 2's contract
takes V17 (schema), V18 (whitelist data) and V19 (task rows). **V20 onwards is
unclaimed and no wave package should need it** — a package that thinks it does
has found a contract gap and should say so.

## 6. Rules that hold for every package

1. **An upgrade may not change behaviour.** The master switch ships off, and
   nothing a package does may make an installation start refusing downloads it
   served before an operator flipped it.
2. **Pre-existing components are audited, never blocked.** In enforcement and in
   quarantine, and nowhere else — two places, both already written.
3. **No network and no blocking I/O on a request thread.** The local tables are
   the fast path; a miss is answered, not resolved.
4. **A firewall fault serves the artifact.** Only a decided verdict withholds
   anything. Every entry point catches `RuntimeException` and answers "serve".
5. **"exemption", never "waiver" or "whitelist".** In code, in the API, in the
   UI, in log lines and in customer-facing text.
6. **Heuristics are labelled as heuristics** wherever they are shown.
7. **Tests never reach the internet.** Fixtures and Testcontainers; the advisory
   and facts sources are stubbed.
8. **Run the build before handing back:**
   `./gradlew --no-daemon build` with the Testcontainers environment and the IT
   database override.
