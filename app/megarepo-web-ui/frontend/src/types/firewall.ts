/**
 * Repository Firewall — Phase 2 types.
 *
 * Mirrors `rest/dto/firewall/*XO` from the contract commit. Phase 1's types
 * (`FirewallMode`, `FirewallOverview`, `FirewallViolation`, …) stay in
 * `types/api.ts`; everything quarantine-, policy- and exemption-shaped lives
 * here, because that is what the Phase 2 screens read.
 *
 * Fields the backend may not send yet are optional rather than `| null`. The
 * difference matters: `null` is "the server said there is none", `undefined` is
 * "this build of the server does not know the field". The UI has to render both
 * without lying — a missing `factsEnabled` is not the same as facts being off.
 */

import type { FirewallMode } from './api';

export type FirewallAction = 'WARN' | 'BLOCK';

export type FirewallFailMode = 'FAIL_OPEN' | 'FAIL_CLOSED';

export type FirewallRuleType =
  | 'ADVISORY_MATCH'
  | 'CVSS_THRESHOLD'
  | 'KNOWN_MALICIOUS'
  | 'LICENSE'
  | 'MIN_AGE'
  | 'UNKNOWN_COMPONENT'
  | 'TYPOSQUAT'
  | 'NAMESPACE_CONFUSION';

// ── Quarantine ────────────────────────────────────────────────────────────

export type FirewallQuarantineState = 'QUARANTINED' | 'RELEASED' | 'BLOCKED';

export type FirewallQuarantineReason =
  | 'MIN_AGE_NOT_MET'
  | 'UNKNOWN_COMPONENT'
  | 'EVALUATION_INCOMPLETE'
  | 'POLICY_VIOLATION';

export type FirewallQuarantineResolution =
  | 'RE_EVALUATED_CLEAN'
  | 'AGE_REACHED'
  | 'ADVISORY_DATA_ARRIVED'
  | 'EXEMPTION_GRANTED'
  | 'POLICY_CHANGED'
  | 'MANUAL_RELEASE'
  | 'POLICY_VIOLATION'
  | 'MANUAL_BLOCK';

export interface FirewallQuarantineEntry {
  id: string;
  repositoryId: string | null;
  repositoryName: string | null;
  componentKey: string;
  path: string | null;
  state: FirewallQuarantineState;
  reason: FirewallQuarantineReason;
  resolution: FirewallQuarantineResolution | null;
  policyId: string | null;
  policyName: string | null;
  advisoryIds: string[] | null;
  evaluation: Record<string, unknown> | null;
  firstSeen: string;
  lastSeen: string | null;
  hitCount: number;
  lastEvaluatedAt: string | null;
  /** When the entry gets another chance. The most useful column in the queue. */
  nextEvaluationAt: string | null;
  decidedAt: string | null;
  decidedBy: string | null;
  decisionReason: string | null;
  exemptionId: string | null;
}

export interface FirewallQuarantineSummary {
  quarantined: number;
  released: number;
  blocked: number;
}

// ── Policies ──────────────────────────────────────────────────────────────

export interface FirewallPolicyRule {
  id: string | null;
  ruleType: FirewallRuleType;
  action: FirewallAction;
  config: Record<string, unknown> | null;
  enabled: boolean;
  /** False ⇒ this build has no bean for the rule; it enforces nothing. */
  implemented: boolean;
}

export interface FirewallPolicy {
  id: string;
  name: string;
  description: string | null;
  isDefault: boolean;
  rules: FirewallPolicyRule[];
  assignedRepositories: number;
  enforcingRepositories: number;
  createdAt: string | null;
  createdBy: string | null;
}

export interface FirewallPolicyUpsert {
  name: string;
  description: string | null;
  makeDefault: boolean;
  rules: FirewallPolicyRule[];
  confirmation?: string;
}

export type ConfigFieldType = 'boolean' | 'number' | 'integer' | 'string' | 'duration' | 'list' | 'enum';

export interface FirewallRuleConfigField {
  key: string;
  /** Server-declared type. Unknown values fall back to a text input. */
  type: string;
  label: string | null;
  description: string | null;
  defaultValue: unknown;
  required: boolean;
  allowedValues: string[] | null;
}

export interface FirewallRuleTypeInfo {
  ruleType: FirewallRuleType;
  label: string | null;
  description: string | null;
  implemented: boolean;
  heuristic: boolean;
  quarantines: boolean;
  requiresComponentFacts: boolean;
  configSchema: FirewallRuleConfigField[] | null;
}

// ── Exemptions ────────────────────────────────────────────────────────────

export type FirewallExemptionScope = 'VERSION' | 'COMPONENT';

export type FirewallExemptionState =
  | 'REQUESTED'
  | 'APPROVED'
  | 'REJECTED'
  | 'EXPIRED'
  | 'REVOKED';

export type FirewallComponentKeyKind = 'PURL' | 'LEGACY_COORDINATE';

export interface FirewallExemption {
  id: string;
  componentKey: string;
  keyKind: FirewallComponentKeyKind;
  scope: FirewallExemptionScope;
  repositoryId: string | null;
  repositoryName: string | null;
  ruleType: FirewallRuleType | null;
  advisoryIds: string[] | null;
  state: FirewallExemptionState;
  expiresAt: string | null;
  expired: boolean;
  expiryNotifiedAt: string | null;
  justification: string | null;
  requestedBy: string | null;
  requestedAt: string | null;
  approvedBy: string | null;
  approvedAt: string | null;
  decisionNote: string | null;
}

export interface FirewallExemptionRequest {
  componentKey: string;
  scope: FirewallExemptionScope | null;
  repositoryId: string | null;
  ruleType: FirewallRuleType | null;
  advisoryIds: string[];
  requestedExpiry: string | null;
  justification: string;
}

/**
 * `/firewall/exemptions/summary`. The three settings at the bottom are what the
 * approval dialog is pre-filled from — the counts are the queue badges.
 *
 * `defaultValidity` / `maxValidity` are Java `Duration`s and arrive either as
 * seconds (Jackson's default) or as an ISO-8601 string, depending on how the
 * instance configures its ObjectMapper. Both are parsed by `durationSeconds()`.
 */
export interface FirewallExemptionSummary {
  requested: number;
  approved: number;
  rejected: number;
  expired: number;
  revoked: number;
  legacy: number;
  defaultValidity: number | string | null;
  maxValidity: number | string | null;
  selfServiceRequests: boolean;
}

// ── Repository assignment ─────────────────────────────────────────────────

/**
 * The per-repository write B2 adds next to the existing mode write. A null
 * `policyId` means "fall back to the instance default policy" — a repository
 * policy *replaces* the default, it never stacks on top of it.
 */
export interface FirewallRepositoryPolicyUpdate {
  policyId: string | null;
  failMode: FirewallFailMode | null;
  confirmation?: string;
}

export interface FirewallQuarantineFilter {
  state?: FirewallQuarantineState | '';
  repositoryId?: string;
  reason?: FirewallQuarantineReason | '';
  search?: string;
}

export interface FirewallExemptionFilter {
  state?: FirewallExemptionState | '';
  repositoryId?: string;
  search?: string;
  expiringOnly?: boolean;
}

export type { FirewallMode };
