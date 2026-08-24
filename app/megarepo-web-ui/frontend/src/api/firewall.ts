/**
 * Repository Firewall — the one place that knows the firewall's HTTP surface.
 *
 * Wave B splits the work: B2 writes the policy and quarantine controllers while
 * B3 writes these screens, so at the time this file was authored two of the
 * four endpoint groups existed only as DTOs. Every path, every query parameter
 * and every "is the list wrapped in a page or not" guess is therefore collected
 * here rather than sprinkled through the pages. If B2 lands on a different
 * shape, this file is what changes; the screens do not.
 *
 * ## What is certain
 *
 * * `/api/v1/admin/firewall/status | enforcement | repositories/{id} | violations`
 *   — Phase 1, `FirewallAdminController`, live.
 * * `/api/v1/firewall/exemptions[/…]` — wave A5, `FirewallExemptionController`,
 *   live, including `/summary`.
 *
 * ## What is assumed (B2)
 *
 * * quarantine queue under `ADMIN_BASE/quarantine`, filters
 *   `state`, `repositoryId`, `reason`, `search`, page cursor
 *   `continuationToken`, answering `PageResponse<FirewallQuarantineEntryXO>`;
 *   `POST …/{id}/release` and `POST …/{id}/block` taking
 *   `FirewallQuarantineDecisionXO` (`{ note }`, note mandatory).
 * * policies under `ADMIN_BASE/policies`: `GET` list, `GET/{id}`, `POST`,
 *   `PUT/{id}`, `DELETE/{id}`, `POST/{id}/default`.
 * * `GET ADMIN_BASE/rule-types` answering `FirewallRuleTypeXO[]`.
 * * `PUT ADMIN_BASE/repositories/{id}/policy` taking
 *   `FirewallRepositoryPolicyUpdateXO`.
 *
 * Two decoders absorb the most likely mismatches without a code change:
 * {@link unwrapList} accepts a bare array, a `PageResponse` or a Spring `Page`,
 * and {@link requiredConfirmationFrom} reads the demanded phrase out of the
 * server's own 400 rather than hard-coding it.
 */

import { api, ApiError } from './client';
import type { PageResponse } from '../types/api';
import type {
  FirewallExemption,
  FirewallExemptionFilter,
  FirewallExemptionRequest,
  FirewallExemptionSummary,
  FirewallPolicy,
  FirewallPolicyUpsert,
  FirewallQuarantineEntry,
  FirewallQuarantineFilter,
  FirewallQuarantineSummary,
  FirewallRepositoryPolicyUpdate,
  FirewallRuleTypeInfo,
} from '../types/firewall';

/** Phase 1's admin controller. Everything nx-admin-only hangs off this. */
export const ADMIN_BASE = '/admin/firewall';

/**
 * A5's controller. Deliberately *not* under `/admin`: requesting an exemption is
 * a developer action, and `self-service-requests` decides whether a non-admin
 * may. Deciding on one is still admin-only, enforced server-side.
 */
export const EXEMPTION_BASE = '/firewall/exemptions';

// ── Decoding helpers ──────────────────────────────────────────────────────

interface SpringPage<T> {
  content: T[];
}

/**
 * Accepts the three list shapes this codebase has produced: a bare array, the
 * app's own `PageResponse` and a Spring `Page`. A screen that has to know which
 * one it got is a screen that breaks on the day a controller is rewritten.
 */
export function unwrapList<T>(payload: unknown): T[] {
  if (Array.isArray(payload)) {
    return payload as T[];
  }
  if (payload && typeof payload === 'object') {
    const page = payload as Partial<PageResponse<T>> & Partial<SpringPage<T>>;
    if (Array.isArray(page.items)) {
      return page.items;
    }
    if (Array.isArray(page.content)) {
      return page.content;
    }
  }
  return [];
}

function unwrapPage<T>(payload: unknown): PageResponse<T> {
  const items = unwrapList<T>(payload);
  let continuationToken: string | null = null;
  if (payload && typeof payload === 'object' && !Array.isArray(payload)) {
    const token = (payload as Record<string, unknown>).continuationToken;
    continuationToken = typeof token === 'string' && token.length > 0 ? token : null;
  }
  return { items, continuationToken };
}

/**
 * A Java `Duration` in seconds, from whichever encoding arrived.
 *
 * Spring's default ObjectMapper writes durations as a decimal number of
 * seconds; an instance that sets `write-durations-as-timestamps=false` writes
 * ISO-8601 (`PT720H`). The exemption summary carries two of them and the
 * approval dialog is pre-filled from one, so guessing wrong would silently
 * offer an expiry 3600 times too short.
 */
export function durationSeconds(value: number | string | null | undefined): number | null {
  if (value == null) {
    return null;
  }
  if (typeof value === 'number') {
    return Number.isFinite(value) ? value : null;
  }
  const text = value.trim();
  if (text.length === 0) {
    return null;
  }
  const numeric = Number(text);
  if (Number.isFinite(numeric)) {
    return numeric;
  }
  const match = /^([+-])?P(?:(\d+(?:\.\d+)?)D)?(?:T(?:(\d+(?:\.\d+)?)H)?(?:(\d+(?:\.\d+)?)M)?(?:(\d+(?:\.\d+)?)S)?)?$/i.exec(
    text,
  );
  if (!match) {
    return null;
  }
  const [, sign, days, hours, minutes, seconds] = match;
  if (!days && !hours && !minutes && !seconds) {
    return null;
  }
  const total =
    Number(days ?? 0) * 86400 +
    Number(hours ?? 0) * 3600 +
    Number(minutes ?? 0) * 60 +
    Number(seconds ?? 0);
  return sign === '-' ? -total : total;
}

/**
 * The exact phrase a guarded write is refusing for, read out of the server's
 * own message (`… send confirmation="ENABLE ENFORCEMENT".`).
 *
 * Phase 1 already guards arming this way and B2 extends the same guard to
 * policy edits — but with a phrase B3 cannot know while the two packages are
 * being written in parallel. Reading it back from the rejection means the
 * dialog asks for whatever the server actually wants, on this build, with no
 * shared constant to drift.
 */
export function requiredConfirmationFrom(error: unknown): string | null {
  if (!(error instanceof ApiError) || (error.status !== 400 && error.status !== 409)) {
    return null;
  }
  const message = error.body?.message ?? error.message ?? '';
  const quoted = /confirmation\s*=\s*"([^"]+)"/i.exec(message);
  if (quoted) {
    return quoted[1];
  }
  const bare = /confirmation\s*=\s*([A-Z][A-Z0-9 _.:/-]{2,})/.exec(message);
  return bare ? bare[1].trim().replace(/[.\s]+$/, '') : null;
}

function query(params: Record<string, string | number | boolean | null | undefined>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === null || value === undefined || value === '' || value === false) {
      continue;
    }
    search.set(key, String(value));
  }
  const text = search.toString();
  return text ? `?${text}` : '';
}

// ── Quarantine queue (B2) ─────────────────────────────────────────────────

export const quarantineApi = {
  path: `${ADMIN_BASE}/quarantine`,

  async list(
    filter: FirewallQuarantineFilter,
    continuationToken?: string | null,
  ): Promise<PageResponse<FirewallQuarantineEntry>> {
    const path = `${quarantineApi.path}${query({
      state: filter.state,
      repositoryId: filter.repositoryId,
      reason: filter.reason,
      search: filter.search,
      continuationToken,
    })}`;
    return unwrapPage<FirewallQuarantineEntry>(await api.get<unknown>(path));
  },

  async get(id: string): Promise<FirewallQuarantineEntry> {
    return api.get<FirewallQuarantineEntry>(`${quarantineApi.path}/${encodeURIComponent(id)}`);
  },

  async summary(): Promise<FirewallQuarantineSummary | null> {
    try {
      return await api.get<FirewallQuarantineSummary>(`${quarantineApi.path}/summary`);
    } catch (error) {
      // A counter is not worth a broken page. The queue itself carries the truth.
      if (error instanceof ApiError && error.isNotFound) {
        return null;
      }
      throw error;
    }
  },

  /** The note is mandatory server-side (`@NotBlank`) and in the dialog. */
  async release(id: string, note: string): Promise<FirewallQuarantineEntry> {
    return api.post<FirewallQuarantineEntry>(
      `${quarantineApi.path}/${encodeURIComponent(id)}/release`,
      { note },
    );
  },

  async block(id: string, note: string): Promise<FirewallQuarantineEntry> {
    return api.post<FirewallQuarantineEntry>(
      `${quarantineApi.path}/${encodeURIComponent(id)}/block`,
      { note },
    );
  },
};

// ── Policies (B2) ─────────────────────────────────────────────────────────

export const policyApi = {
  path: `${ADMIN_BASE}/policies`,

  async list(): Promise<FirewallPolicy[]> {
    return unwrapList<FirewallPolicy>(await api.get<unknown>(policyApi.path));
  },

  async get(id: string): Promise<FirewallPolicy> {
    return api.get<FirewallPolicy>(`${policyApi.path}/${encodeURIComponent(id)}`);
  },

  async create(body: FirewallPolicyUpsert): Promise<FirewallPolicy> {
    return api.post<FirewallPolicy>(policyApi.path, body);
  },

  /** Replace, not patch: the body carries the complete rule set. */
  async replace(id: string, body: FirewallPolicyUpsert): Promise<FirewallPolicy> {
    return api.put<FirewallPolicy>(`${policyApi.path}/${encodeURIComponent(id)}`, body);
  },

  async remove(id: string): Promise<void> {
    return api.delete(`${policyApi.path}/${encodeURIComponent(id)}`);
  },

  async makeDefault(id: string, confirmation?: string): Promise<FirewallPolicy> {
    return api.post<FirewallPolicy>(`${policyApi.path}/${encodeURIComponent(id)}/default`, {
      confirmation,
    });
  },

  async ruleTypes(): Promise<FirewallRuleTypeInfo[]> {
    return unwrapList<FirewallRuleTypeInfo>(await api.get<unknown>(`${ADMIN_BASE}/rule-types`));
  },
};

// ── Repository assignment (B2) ────────────────────────────────────────────

export async function assignRepositoryPolicy(
  repositoryId: string,
  update: FirewallRepositoryPolicyUpdate,
): Promise<void> {
  await api.put<unknown>(
    `${ADMIN_BASE}/repositories/${encodeURIComponent(repositoryId)}/policy`,
    update,
  );
}

// ── Exemptions (A5 — live) ────────────────────────────────────────────────

export const exemptionApi = {
  path: EXEMPTION_BASE,

  async list(
    filter: FirewallExemptionFilter,
    continuationToken?: string | null,
  ): Promise<PageResponse<FirewallExemption>> {
    const path = `${EXEMPTION_BASE}${query({
      state: filter.state,
      repositoryId: filter.repositoryId,
      search: filter.search,
      expiringOnly: filter.expiringOnly,
      continuationToken,
    })}`;
    return unwrapPage<FirewallExemption>(await api.get<unknown>(path));
  },

  async get(id: string): Promise<FirewallExemption> {
    return api.get<FirewallExemption>(`${EXEMPTION_BASE}/${encodeURIComponent(id)}`);
  },

  async summary(): Promise<FirewallExemptionSummary> {
    return api.get<FirewallExemptionSummary>(`${EXEMPTION_BASE}/summary`);
  },

  async request(body: FirewallExemptionRequest): Promise<FirewallExemption> {
    return api.post<FirewallExemption>(EXEMPTION_BASE, body);
  },

  async approve(id: string, expiresAt: string | null, note: string | null): Promise<FirewallExemption> {
    return api.post<FirewallExemption>(`${EXEMPTION_BASE}/${encodeURIComponent(id)}/approve`, {
      expiresAt,
      note,
    });
  },

  async reject(id: string, note: string | null): Promise<FirewallExemption> {
    return api.post<FirewallExemption>(`${EXEMPTION_BASE}/${encodeURIComponent(id)}/reject`, {
      note,
    });
  },

  /**
   * Revoke — and there is no delete, on purpose. An exemption that was live is
   * the reason a build passed; deleting the row would erase why. Revoking ends
   * it and keeps the record.
   */
  async revoke(id: string, note: string | null): Promise<FirewallExemption> {
    return api.post<FirewallExemption>(`${EXEMPTION_BASE}/${encodeURIComponent(id)}/revoke`, {
      note,
    });
  },
};
