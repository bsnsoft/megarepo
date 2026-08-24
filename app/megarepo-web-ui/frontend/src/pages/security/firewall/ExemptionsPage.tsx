import { useCallback, useEffect, useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { ApiError, api } from '../../../api/client';
import { durationSeconds, exemptionApi } from '../../../api/firewall';
import ErrorState from '../../../components/ErrorState';
import LoadingSpinner from '../../../components/LoadingSpinner';
import { useToast } from '../../../components/Toast';
import type { FirewallOverview } from '../../../types/api';
import type {
  FirewallExemption,
  FirewallExemptionFilter,
  FirewallExemptionScope,
  FirewallExemptionState,
  FirewallExemptionSummary,
  FirewallRuleType,
} from '../../../types/firewall';
import ExemptionApproveDialog from './ExemptionApproveDialog';
import FirewallNav from './FirewallNav';
import NoteDialog, { type NotePrompt } from './NoteDialog';
import { formatRelative, formatTimestamp, isoFromNowSeconds, toLocalInputValue, fromLocalInputValue } from './format';
import { ruleLabel } from './ruleCatalog';

/**
 * Exemptions — the approval queue and the record of every hole in the policy.
 *
 * Three things this screen refuses to make easy, on purpose.
 *
 * **There is no delete.** Revoke is the way out. An exemption that was once live
 * is the answer to "why did this build pass in March", and a table you can
 * delete rows from cannot answer that. `REVOKED` is a terminal state that keeps
 * the row.
 *
 * **Legacy rows are marked.** V18 migrated the old NVD component whitelist into
 * this table as approved, non-expiring exemptions. They are keyed on the old
 * coordinate format (`keyKind = LEGACY_COORDINATE`), not on a purl, and an
 * operator who does not know that will look at a whitelist decision from two
 * years ago and read it as somebody's considered judgement from last week.
 *
 * **Expiry is a column, not a detail.** The instance's default validity is what
 * the approval dialog pre-fills, and the list sorts the question "what is about
 * to lapse" to the surface with a filter, because an exemption expiring on a
 * Friday afternoon is a build failure on Monday morning.
 */

const STATE_STYLE: Record<FirewallExemptionState, { label: string; className: string }> = {
  REQUESTED: { label: 'Requested', className: 'bg-blue-50 text-blue-700 border-blue-200' },
  APPROVED: { label: 'Approved', className: 'bg-emerald-50 text-emerald-700 border-emerald-200' },
  REJECTED: { label: 'Rejected', className: 'bg-gray-100 text-gray-600 border-gray-200' },
  EXPIRED: { label: 'Expired', className: 'bg-amber-50 text-amber-800 border-amber-300' },
  REVOKED: { label: 'Revoked', className: 'bg-red-50 text-red-700 border-red-200' },
};

const RULE_TYPES: FirewallRuleType[] = [
  'ADVISORY_MATCH',
  'CVSS_THRESHOLD',
  'KNOWN_MALICIOUS',
  'LICENSE',
  'MIN_AGE',
  'UNKNOWN_COMPONENT',
  'TYPOSQUAT',
  'NAMESPACE_CONFUSION',
];

export default function ExemptionsPage() {
  const { showToast } = useToast();
  const [searchParams, setSearchParams] = useSearchParams();

  const [exemptions, setExemptions] = useState<FirewallExemption[]>([]);
  const [nextToken, setNextToken] = useState<string | null>(null);
  const [summary, setSummary] = useState<FirewallExemptionSummary | null>(null);
  const [overview, setOverview] = useState<FirewallOverview | null>(null);

  const [loading, setLoading] = useState(true);
  const [failed, setFailed] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [dialogError, setDialogError] = useState<string | null>(null);

  const [approving, setApproving] = useState<FirewallExemption | null>(null);
  const [notePrompt, setNotePrompt] = useState<NotePrompt | null>(null);
  const [requestOpen, setRequestOpen] = useState(searchParams.get('request') === '1');

  const filters: Required<FirewallExemptionFilter> = useMemo(
    () => ({
      state: (searchParams.get('state') as FirewallExemptionState | null) ?? '',
      repositoryId: searchParams.get('repositoryId') ?? '',
      search: searchParams.get('search') ?? '',
      expiringOnly: searchParams.get('expiringOnly') === '1',
    }),
    [searchParams],
  );

  function setFilter(key: string, value: string) {
    const next = new URLSearchParams(searchParams);
    if (value) {
      next.set(key, value);
    } else {
      next.delete(key);
    }
    setSearchParams(next, { replace: true });
  }

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const page = await exemptionApi.list({
        state: filters.state,
        repositoryId: filters.repositoryId,
        search: filters.search,
        expiringOnly: filters.expiringOnly,
      });
      setExemptions(page.items);
      setNextToken(page.continuationToken);
      setFailed(null);
    } catch (error) {
      setFailed(error instanceof Error ? error.message : 'Failed to load exemptions');
    } finally {
      setLoading(false);
    }
  }, [filters.state, filters.repositoryId, filters.search, filters.expiringOnly]);

  const loadSummary = useCallback(async () => {
    try {
      setSummary(await exemptionApi.summary());
    } catch {
      setSummary(null);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  useEffect(() => {
    loadSummary();
    api
      .get<FirewallOverview>('/admin/firewall/status')
      .then(setOverview)
      .catch(() => setOverview(null));
  }, [loadSummary]);

  async function loadMore() {
    if (!nextToken) return;
    setLoadingMore(true);
    try {
      const page = await exemptionApi.list(filters, nextToken);
      setExemptions((previous) => [...previous, ...page.items]);
      setNextToken(page.continuationToken);
    } catch (error) {
      showToast('error', error instanceof Error ? error.message : 'Failed to load more');
    } finally {
      setLoadingMore(false);
    }
  }

  function applyDecision(updated: FirewallExemption) {
    setExemptions((previous) => previous.map((row) => (row.id === updated.id ? updated : row)));
    loadSummary();
  }

  function decisionFailed(error: unknown): string {
    if (error instanceof ApiError && error.isConflict) {
      return `${error.message} The exemption is no longer in a state this decision is valid from — reload the list.`;
    }
    if (error instanceof ApiError && error.isValidationError) {
      return error.message;
    }
    return error instanceof Error ? error.message : 'The decision was refused.';
  }

  async function approve(exemption: FirewallExemption, expiresAt: string | null, note: string) {
    setBusy(true);
    setDialogError(null);
    try {
      applyDecision(await exemptionApi.approve(exemption.id, expiresAt, note || null));
      setApproving(null);
      showToast('success', `Exemption for ${exemption.componentKey} approved.`);
    } catch (error) {
      setDialogError(decisionFailed(error));
    } finally {
      setBusy(false);
    }
  }

  function askReject(exemption: FirewallExemption) {
    setDialogError(null);
    setNotePrompt({
      title: `Reject the request for ${exemption.componentKey}?`,
      body: 'The requester keeps being blocked. The note is what they will be told.',
      noteLabel: 'Why is this being rejected?',
      confirmLabel: 'Reject',
      variant: 'danger',
      required: false,
      onConfirm: async (note) => {
        setBusy(true);
        setDialogError(null);
        try {
          applyDecision(await exemptionApi.reject(exemption.id, note || null));
          setNotePrompt(null);
          showToast('success', 'Request rejected.');
        } catch (error) {
          setDialogError(decisionFailed(error));
        } finally {
          setBusy(false);
        }
      },
    });
  }

  function askRevoke(exemption: FirewallExemption) {
    setDialogError(null);
    setNotePrompt({
      title: `Revoke the exemption for ${exemption.componentKey}?`,
      body:
        'The component starts being refused again on the next download — immediately, not at the ' +
        'next sweep. The row stays as the record of what was allowed and when.',
      noteLabel: 'Why is this being revoked?',
      confirmLabel: 'Revoke',
      variant: 'danger',
      required: false,
      onConfirm: async (note) => {
        setBusy(true);
        setDialogError(null);
        try {
          applyDecision(await exemptionApi.revoke(exemption.id, note || null));
          setNotePrompt(null);
          showToast('success', 'Exemption revoked.');
        } catch (error) {
          setDialogError(decisionFailed(error));
        } finally {
          setBusy(false);
        }
      },
    });
  }

  const defaultValidity = durationSeconds(summary?.defaultValidity);
  const maxValidity = durationSeconds(summary?.maxValidity);
  const repositories = overview?.repositories ?? [];

  return (
    <div className="p-6 sm:p-8 max-w-7xl space-y-6">
      <div className="flex items-start justify-between gap-4">
        <div>
          <h1 className="text-2xl font-semibold text-gray-950">Repository Firewall</h1>
          <p className="text-sm text-gray-500 mt-1">
            Exemptions let a named component past named rules, for a bounded time, with a reason on
            the record.
          </p>
        </div>
        <button
          onClick={() => setRequestOpen((open) => !open)}
          className="shrink-0 px-4 py-2 text-sm font-semibold rounded-md bg-blue-600 hover:bg-blue-700 text-white"
        >
          {requestOpen ? 'Close request form' : 'Request exemption'}
        </button>
      </div>

      <FirewallNav />

      {summary && (
        <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-6 gap-3">
          <SummaryCard label="Awaiting decision" value={summary.requested} highlight />
          <SummaryCard label="Approved" value={summary.approved} />
          <SummaryCard label="Rejected" value={summary.rejected} />
          <SummaryCard label="Expired" value={summary.expired} />
          <SummaryCard label="Revoked" value={summary.revoked} />
          <SummaryCard
            label="Migrated from whitelist"
            value={summary.legacy}
            title="V8 NVD whitelist rows, migrated by V18 as approved, non-expiring exemptions."
          />
        </div>
      )}

      {summary && !summary.selfServiceRequests && (
        <p className="text-xs text-gray-600 bg-gray-50 border border-gray-200 rounded-md px-4 py-3">
          Self-service requests are switched off (
          <code className="font-mono">megarepo.firewall.exemption.self-service-requests=false</code>
          ). Only administrators can file a request; developers hitting a block have to ask.
        </p>
      )}

      {requestOpen && (
        <RequestForm
          repositories={repositories.map((repository) => ({
            id: repository.repositoryId,
            name: repository.repositoryName,
          }))}
          defaultValiditySeconds={defaultValidity}
          initialComponentKey={searchParams.get('componentKey') ?? ''}
          initialRepositoryId={searchParams.get('repositoryId') ?? ''}
          onCancel={() => setRequestOpen(false)}
          onSubmitted={() => {
            setRequestOpen(false);
            load();
            loadSummary();
          }}
        />
      )}

      {/* ── Filters ──────────────────────────────────────────────────── */}
      <div className="bg-white rounded-lg border border-gray-200 p-4 flex flex-wrap gap-3 items-end">
        <label className="flex flex-col gap-1">
          <span className="text-xs text-gray-500">State</span>
          <select
            aria-label="State"
            value={filters.state}
            onChange={(e) => setFilter('state', e.target.value)}
            className="px-3 py-2 border border-gray-300 rounded-md text-sm"
          >
            <option value="">All states</option>
            {(Object.keys(STATE_STYLE) as FirewallExemptionState[]).map((state) => (
              <option key={state} value={state}>
                {STATE_STYLE[state].label}
              </option>
            ))}
          </select>
        </label>

        <label className="flex flex-col gap-1">
          <span className="text-xs text-gray-500">Repository</span>
          <select
            aria-label="Repository"
            value={filters.repositoryId}
            onChange={(e) => setFilter('repositoryId', e.target.value)}
            className="px-3 py-2 border border-gray-300 rounded-md text-sm"
          >
            <option value="">All repositories</option>
            {repositories.map((repository) => (
              <option key={repository.repositoryId} value={repository.repositoryId}>
                {repository.repositoryName}
              </option>
            ))}
          </select>
        </label>

        <label className="flex flex-col gap-1 flex-1 min-w-52">
          <span className="text-xs text-gray-500">Component</span>
          <input
            aria-label="Component"
            type="search"
            value={filters.search}
            onChange={(e) => setFilter('search', e.target.value)}
            className="px-3 py-2 border border-gray-300 rounded-md text-sm"
          />
        </label>

        <label className="inline-flex items-center gap-2 text-sm text-gray-800 pb-2">
          <input
            type="checkbox"
            checked={filters.expiringOnly}
            onChange={(e) => setFilter('expiringOnly', e.target.checked ? '1' : '')}
          />
          Expiring soon only
        </label>
      </div>

      {loading ? (
        <div className="flex justify-center items-center py-20">
          <LoadingSpinner message="Loading exemptions..." />
        </div>
      ) : failed ? (
        <ErrorState title="Failed to load exemptions" message={failed} onRetry={() => load()} />
      ) : exemptions.length === 0 ? (
        <div className="bg-white rounded-lg border border-gray-200 p-10 text-center text-sm text-gray-500">
          No exemptions match these filters.
        </div>
      ) : (
        <div className="bg-white rounded-lg border border-gray-200">
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-left text-xs font-medium text-gray-500 border-b border-gray-200">
                  <th className="py-2 pl-4 pr-4">Component</th>
                  <th className="py-2 pr-4">Scope</th>
                  <th className="py-2 pr-4">Rule</th>
                  <th className="py-2 pr-4">Repository</th>
                  <th className="py-2 pr-4">State</th>
                  <th className="py-2 pr-4">Expires</th>
                  <th className="py-2 pr-4">Requested</th>
                  <th className="py-2 pr-4" />
                </tr>
              </thead>
              <tbody>
                {exemptions.map((exemption) => {
                  const style = STATE_STYLE[exemption.state];
                  const legacy = exemption.keyKind === 'LEGACY_COORDINATE';
                  return (
                    <tr key={exemption.id} className="border-b border-gray-100 align-top">
                      <td className="py-2.5 pl-4 pr-4">
                        <div className="font-mono text-xs text-gray-900 break-all">
                          {exemption.componentKey}
                        </div>
                        {legacy && (
                          <span
                            title="Carried over from the pre-Phase-2 NVD whitelist. Keyed on the old coordinate format, not a purl, and it was never given an expiry."
                            className="inline-block mt-1 px-1.5 py-0.5 rounded bg-slate-100 text-slate-700 text-[10px] font-semibold uppercase tracking-wide"
                          >
                            migrated from whitelist
                          </span>
                        )}
                        {exemption.justification && (
                          <div className="text-[11px] text-gray-500 mt-1 max-w-md">
                            {exemption.justification}
                          </div>
                        )}
                      </td>
                      <td className="py-2.5 pr-4 text-xs text-gray-700">
                        {exemption.scope === 'COMPONENT' ? 'All versions' : 'This version'}
                      </td>
                      <td className="py-2.5 pr-4 text-xs text-gray-700">
                        {exemption.ruleType ? ruleLabel(exemption.ruleType) : 'Every rule'}
                      </td>
                      <td className="py-2.5 pr-4 text-xs text-gray-700">
                        {exemption.repositoryName ?? (exemption.repositoryId ? '—' : 'All repositories')}
                      </td>
                      <td className="py-2.5 pr-4">
                        <span
                          className={`inline-block px-2 py-0.5 rounded border text-xs font-semibold ${style.className}`}
                        >
                          {style.label}
                        </span>
                      </td>
                      <td className="py-2.5 pr-4 text-xs whitespace-nowrap">
                        {exemption.expiresAt ? (
                          <span
                            title={formatTimestamp(exemption.expiresAt)}
                            className={exemption.expired ? 'text-amber-800 font-medium' : 'text-gray-700'}
                          >
                            {exemption.expired ? 'expired' : formatRelative(exemption.expiresAt)}
                          </span>
                        ) : (
                          <span className="text-gray-500" title="Nothing ends this but a revocation.">
                            never
                          </span>
                        )}
                      </td>
                      <td className="py-2.5 pr-4 text-xs text-gray-500 whitespace-nowrap">
                        {exemption.requestedBy ?? '—'}
                        <div className="text-gray-400">{formatTimestamp(exemption.requestedAt)}</div>
                      </td>
                      <td className="py-2.5 pr-4 text-right whitespace-nowrap">
                        {exemption.state === 'REQUESTED' && (
                          <>
                            <button
                              onClick={() => {
                                setDialogError(null);
                                setApproving(exemption);
                              }}
                              disabled={busy}
                              className="px-2 py-1 text-xs font-medium rounded border border-emerald-300 text-emerald-700 bg-white hover:bg-emerald-50 disabled:opacity-50"
                            >
                              Approve
                            </button>
                            <button
                              onClick={() => askReject(exemption)}
                              disabled={busy}
                              className="ml-1 px-2 py-1 text-xs font-medium rounded border border-gray-300 bg-white hover:bg-gray-50 disabled:opacity-50"
                            >
                              Reject
                            </button>
                          </>
                        )}
                        {exemption.state === 'APPROVED' && (
                          <button
                            onClick={() => askRevoke(exemption)}
                            disabled={busy}
                            className="px-2 py-1 text-xs font-medium rounded border border-red-300 text-red-700 bg-white hover:bg-red-50 disabled:opacity-50"
                          >
                            Revoke
                          </button>
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>

          {nextToken && (
            <div className="flex justify-center py-4">
              <button
                onClick={loadMore}
                disabled={loadingMore}
                className="px-4 py-2 text-sm font-medium rounded-md border border-gray-300 bg-white hover:bg-gray-50 disabled:opacity-50"
              >
                {loadingMore ? 'Loading…' : 'Load more'}
              </button>
            </div>
          )}
        </div>
      )}

      {approving && (
        <ExemptionApproveDialog
          exemption={approving}
          defaultValiditySeconds={defaultValidity}
          maxValiditySeconds={maxValidity}
          busy={busy}
          error={dialogError}
          onCancel={() => {
            setApproving(null);
            setDialogError(null);
          }}
          onConfirm={(expiresAt, note) => approve(approving, expiresAt, note)}
        />
      )}

      {notePrompt && (
        <NoteDialog
          prompt={notePrompt}
          busy={busy}
          error={dialogError}
          onCancel={() => {
            setNotePrompt(null);
            setDialogError(null);
          }}
        />
      )}
    </div>
  );
}

function SummaryCard({
  label,
  value,
  highlight,
  title,
}: {
  label: string;
  value: number;
  highlight?: boolean;
  title?: string;
}) {
  return (
    <div
      title={title}
      className={`rounded-lg border p-3 ${
        highlight && value > 0 ? 'border-blue-300 bg-blue-50' : 'border-gray-200 bg-white'
      }`}
    >
      <div className="text-xl font-semibold text-gray-900">{value}</div>
      <div className="text-[11px] text-gray-500 leading-tight mt-0.5">{label}</div>
    </div>
  );
}

/**
 * Filing a request.
 *
 * Reached from the quarantine queue with the component key already filled in,
 * which is the only way it should normally be reached: a key typed by hand is a
 * key that matches nothing, and an exemption that matches nothing still reads as
 * "approved" in every list.
 */
function RequestForm({
  repositories,
  defaultValiditySeconds,
  initialComponentKey,
  initialRepositoryId,
  onCancel,
  onSubmitted,
}: {
  repositories: { id: string; name: string }[];
  defaultValiditySeconds: number | null;
  initialComponentKey: string;
  initialRepositoryId: string;
  onCancel: () => void;
  onSubmitted: () => void;
}) {
  const { showToast } = useToast();
  const [componentKey, setComponentKey] = useState(initialComponentKey);
  const [scope, setScope] = useState<FirewallExemptionScope>('VERSION');
  const [repositoryId, setRepositoryId] = useState(initialRepositoryId);
  const [ruleType, setRuleType] = useState<FirewallRuleType | ''>('');
  const [advisoryIds, setAdvisoryIds] = useState('');
  const [expiry, setExpiry] = useState(() =>
    defaultValiditySeconds != null && defaultValiditySeconds > 0
      ? toLocalInputValue(isoFromNowSeconds(defaultValiditySeconds))
      : '',
  );
  const [justification, setJustification] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit() {
    setBusy(true);
    setError(null);
    try {
      await exemptionApi.request({
        componentKey: componentKey.trim(),
        scope,
        repositoryId: repositoryId || null,
        ruleType: ruleType || null,
        advisoryIds: advisoryIds
          .split(/[\s,]+/)
          .map((entry) => entry.trim())
          .filter(Boolean),
        requestedExpiry: fromLocalInputValue(expiry),
        justification: justification.trim(),
      });
      showToast('success', 'Exemption requested. It is refused until somebody approves it.');
      onSubmitted();
    } catch (requestError) {
      setError(
        requestError instanceof Error ? requestError.message : 'The request could not be filed.',
      );
    } finally {
      setBusy(false);
    }
  }

  const invalid = componentKey.trim().length === 0 || justification.trim().length === 0;

  return (
    <section className="bg-white rounded-lg border border-gray-200 p-6 space-y-4">
      <h2 className="text-lg font-semibold text-gray-950">Request an exemption</h2>

      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
        <div className="md:col-span-2">
          <label htmlFor="exemption-key" className="block text-xs font-medium text-gray-700 mb-1">
            Component key <span className="text-red-600">*</span>
          </label>
          <input
            id="exemption-key"
            type="text"
            value={componentKey}
            onChange={(e) => setComponentKey(e.target.value)}
            placeholder="pkg:maven/org.example/thing@1.2.3"
            className="w-full px-3 py-2 border border-gray-300 rounded-md text-sm font-mono"
          />
          <p className="text-[11px] text-gray-500 mt-1">
            Copy it from the queue rather than typing it. A key that does not match exactly produces
            an exemption that looks approved and lets nothing through.
          </p>
        </div>

        <div>
          <label htmlFor="exemption-scope" className="block text-xs font-medium text-gray-700 mb-1">
            Scope
          </label>
          <select
            id="exemption-scope"
            value={scope}
            onChange={(e) => setScope(e.target.value as FirewallExemptionScope)}
            className="w-full px-3 py-2 border border-gray-300 rounded-md text-sm"
          >
            <option value="VERSION">This version only</option>
            <option value="COMPONENT">Every version of the component</option>
          </select>
        </div>

        <div>
          <label htmlFor="exemption-repository" className="block text-xs font-medium text-gray-700 mb-1">
            Repository
          </label>
          <select
            id="exemption-repository"
            value={repositoryId}
            onChange={(e) => setRepositoryId(e.target.value)}
            className="w-full px-3 py-2 border border-gray-300 rounded-md text-sm"
          >
            <option value="">All repositories</option>
            {repositories.map((repository) => (
              <option key={repository.id} value={repository.id}>
                {repository.name}
              </option>
            ))}
          </select>
        </div>

        <div>
          <label htmlFor="exemption-rule" className="block text-xs font-medium text-gray-700 mb-1">
            Rule
          </label>
          <select
            id="exemption-rule"
            value={ruleType}
            onChange={(e) => setRuleType(e.target.value as FirewallRuleType | '')}
            className="w-full px-3 py-2 border border-gray-300 rounded-md text-sm"
          >
            <option value="">Every rule</option>
            {RULE_TYPES.map((type) => (
              <option key={type} value={type}>
                {ruleLabel(type)}
              </option>
            ))}
          </select>
          <p className="text-[11px] text-gray-500 mt-1">
            Narrow it to the rule that blocked you. “Every rule” exempts the component from
            everything, including rules nobody has looked at yet.
          </p>
        </div>

        <div>
          <label htmlFor="exemption-requested-expiry" className="block text-xs font-medium text-gray-700 mb-1">
            Requested expiry
          </label>
          <input
            id="exemption-requested-expiry"
            type="datetime-local"
            value={expiry}
            onChange={(e) => setExpiry(e.target.value)}
            className="w-full px-3 py-2 border border-gray-300 rounded-md text-sm"
          />
        </div>

        <div className="md:col-span-2">
          <label htmlFor="exemption-advisories" className="block text-xs font-medium text-gray-700 mb-1">
            Advisories (optional)
          </label>
          <input
            id="exemption-advisories"
            type="text"
            value={advisoryIds}
            onChange={(e) => setAdvisoryIds(e.target.value)}
            placeholder="CVE-2021-44228, GHSA-jfh8-c2jp-5v3q"
            className="w-full px-3 py-2 border border-gray-300 rounded-md text-sm font-mono"
          />
        </div>

        <div className="md:col-span-2">
          <label htmlFor="exemption-justification" className="block text-xs font-medium text-gray-700 mb-1">
            Justification <span className="text-red-600">*</span>
          </label>
          <textarea
            id="exemption-justification"
            rows={3}
            value={justification}
            onChange={(e) => setJustification(e.target.value)}
            className="w-full px-3 py-2 border border-gray-300 rounded-md text-sm"
          />
        </div>
      </div>

      {error && (
        <div role="alert" className="rounded-md border border-red-300 bg-red-50 px-3 py-2 text-xs text-red-800">
          {error}
        </div>
      )}

      <div className="flex gap-2">
        <button
          onClick={submit}
          disabled={busy || invalid}
          className="px-4 py-2 text-sm font-semibold rounded-md bg-blue-600 hover:bg-blue-700 text-white disabled:opacity-40"
        >
          {busy ? 'Filing…' : 'File request'}
        </button>
        <button
          onClick={onCancel}
          className="px-4 py-2 text-sm font-medium rounded-md border border-gray-300 bg-white hover:bg-gray-50"
        >
          Cancel
        </button>
      </div>
    </section>
  );
}
