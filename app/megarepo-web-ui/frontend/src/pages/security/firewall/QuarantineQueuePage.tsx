import { Fragment, useCallback, useEffect, useMemo, useState, type ReactNode } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { ApiError, api } from '../../../api/client';
import { quarantineApi } from '../../../api/firewall';
import ErrorState from '../../../components/ErrorState';
import LoadingSpinner from '../../../components/LoadingSpinner';
import { useToast } from '../../../components/Toast';
import type { FirewallOverview } from '../../../types/api';
import type {
  FirewallQuarantineEntry,
  FirewallQuarantineReason,
  FirewallQuarantineState,
} from '../../../types/firewall';
import FactsDisabledBanner from './FactsDisabledBanner';
import FirewallNav from './FirewallNav';
import NoteDialog, { type NotePrompt } from './NoteDialog';
import { AdvisoryLinks } from './ViolationDetailDialog';
import { formatAge, formatRelative, formatTimestamp, humanizeEnum } from './format';

/**
 * The quarantine queue — held components, and what happens to them next.
 *
 * A held component is not a verdict, it is a *pause*, and that is the thing this
 * screen has to get across. Most entries leave on their own: a MIN_AGE hold
 * lifts when the version is old enough, an UNKNOWN_COMPONENT hold lifts when the
 * next advisory sync brings data. So the column that matters most is not the
 * reason, it is `nextEvaluationAt` — "this fixes itself in six hours" turns a
 * queue of forty entries into a queue of three that need a human.
 *
 * Release and block are both overrides, both keep a note forever, and both go
 * through the same dialog. Blocking is the heavier of the two and is styled that
 * way: a released entry can be blocked again by the next sweep, a blocked one is
 * done being re-evaluated.
 */

const STATE_STYLE: Record<FirewallQuarantineState, { label: string; className: string; detail: string }> = {
  QUARANTINED: {
    label: 'Held',
    className: 'bg-amber-50 text-amber-800 border-amber-300',
    detail: 'Refused while enforcement is on, and re-evaluated on a schedule.',
  },
  RELEASED: {
    label: 'Released',
    className: 'bg-emerald-50 text-emerald-700 border-emerald-200',
    detail: 'Served. The entry stays as the record of why.',
  },
  BLOCKED: {
    label: 'Blocked',
    className: 'bg-red-50 text-red-700 border-red-300',
    detail: 'Refused, and no longer re-evaluated in the hope of a different answer.',
  },
};

const REASON_LABEL: Record<FirewallQuarantineReason, string> = {
  MIN_AGE_NOT_MET: 'Too new',
  UNKNOWN_COMPONENT: 'No advisory data',
  EVALUATION_INCOMPLETE: 'Could not evaluate',
  POLICY_VIOLATION: 'Policy violation',
};

const REASON_HINT: Record<FirewallQuarantineReason, string> = {
  MIN_AGE_NOT_MET: 'The version is younger than the policy allows. Lifts by itself with age.',
  UNKNOWN_COMPONENT: 'No advisory names this component. Lifts if a sync brings data.',
  EVALUATION_INCOMPLETE:
    'A rule could not decide and the repository fails closed. Lifts once the missing facts arrive.',
  POLICY_VIOLATION: 'A rule matched outright. This one does not lift by itself.',
};

interface Filters {
  state: FirewallQuarantineState | '';
  repositoryId: string;
  reason: FirewallQuarantineReason | '';
  search: string;
}

export default function QuarantineQueuePage() {
  const { showToast } = useToast();
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();

  const [entries, setEntries] = useState<FirewallQuarantineEntry[]>([]);
  const [nextToken, setNextToken] = useState<string | null>(null);
  const [overview, setOverview] = useState<FirewallOverview | null>(null);
  const [loading, setLoading] = useState(true);
  const [failed, setFailed] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [expanded, setExpanded] = useState<string | null>(null);
  const [prompt, setPrompt] = useState<NotePrompt | null>(null);
  const [promptError, setPromptError] = useState<string | null>(null);

  const filters: Filters = useMemo(
    () => ({
      state: (searchParams.get('state') as FirewallQuarantineState | null) ?? 'QUARANTINED',
      repositoryId: searchParams.get('repositoryId') ?? '',
      reason: (searchParams.get('reason') as FirewallQuarantineReason | null) ?? '',
      search: searchParams.get('search') ?? '',
    }),
    [searchParams],
  );

  function setFilter(key: keyof Filters, value: string) {
    const next = new URLSearchParams(searchParams);
    // State is the one filter with a non-empty default ("held"), so choosing
    // "all states" has to be written down as an empty value rather than dropped
    // — a missing parameter means "not chosen", which reads back as the default.
    if (value || key === 'state') {
      next.set(key, value);
    } else {
      next.delete(key);
    }
    setSearchParams(next, { replace: true });
  }

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const page = await quarantineApi.list({
        state: filters.state,
        repositoryId: filters.repositoryId,
        reason: filters.reason,
        search: filters.search,
      });
      setEntries(page.items);
      setNextToken(page.continuationToken);
      setFailed(null);
    } catch (error) {
      setFailed(error instanceof Error ? error.message : 'Failed to load the quarantine queue');
    } finally {
      setLoading(false);
    }
  }, [filters.state, filters.repositoryId, filters.reason, filters.search]);

  useEffect(() => {
    load();
  }, [load]);

  useEffect(() => {
    // Only for the repository filter and the facts banner — the queue itself
    // never waits on it.
    api
      .get<FirewallOverview>('/admin/firewall/status')
      .then(setOverview)
      .catch(() => setOverview(null));
  }, []);

  async function loadMore() {
    if (!nextToken) return;
    setLoadingMore(true);
    try {
      const page = await quarantineApi.list(filters, nextToken);
      setEntries((previous) => [...previous, ...page.items]);
      setNextToken(page.continuationToken);
    } catch (error) {
      showToast('error', error instanceof Error ? error.message : 'Failed to load more entries');
    } finally {
      setLoadingMore(false);
    }
  }

  /**
   * 409 is the interesting failure: it means somebody — or a sweep — already
   * moved the entry, and the server's own sentence says which transition was
   * refused. Showing that verbatim inside the dialog beats a generic toast,
   * because the operator is standing at the exact decision it is about.
   */
  async function decide(
    entry: FirewallQuarantineEntry,
    action: 'release' | 'block',
    note: string,
  ) {
    setBusy(true);
    setPromptError(null);
    try {
      const updated =
        action === 'release'
          ? await quarantineApi.release(entry.id, note)
          : await quarantineApi.block(entry.id, note);
      setEntries((previous) => previous.map((row) => (row.id === updated.id ? updated : row)));
      setPrompt(null);
      showToast(
        'success',
        action === 'release'
          ? `${entry.componentKey} released — downloads are served again.`
          : `${entry.componentKey} blocked. It will not be re-evaluated.`,
      );
    } catch (error) {
      if (error instanceof ApiError && error.isConflict) {
        setPromptError(
          `${error.message} Reload the queue — this entry is no longer in a state that allows it.`,
        );
      } else {
        setPromptError(error instanceof Error ? error.message : 'The decision was refused.');
      }
    } finally {
      setBusy(false);
    }
  }

  function askRelease(entry: FirewallQuarantineEntry) {
    setPromptError(null);
    setPrompt({
      title: `Release ${entry.componentKey}?`,
      body:
        'The component is served from now on, without waiting for the next re-evaluation. ' +
        'It stays in the queue as the record of the decision.',
      noteLabel: 'Why is this safe to serve?',
      placeholder: 'e.g. reviewed the advisory, we do not use the affected code path',
      confirmLabel: 'Release',
      variant: 'primary',
      required: true,
      onConfirm: (note) => decide(entry, 'release', note),
    });
  }

  function askBlock(entry: FirewallQuarantineEntry) {
    setPromptError(null);
    setPrompt({
      title: `Block ${entry.componentKey}?`,
      body:
        'The component is refused and stops being re-evaluated — no sweep and no advisory update ' +
        'will release it again. Builds pulling it will keep failing until somebody blocks it no ' +
        'more or grants an exemption.',
      noteLabel: 'Why is this being blocked for good?',
      placeholder: 'e.g. confirmed malicious, upstream has not withdrawn it',
      confirmLabel: 'Block permanently',
      variant: 'danger',
      required: true,
      onConfirm: (note) => decide(entry, 'block', note),
    });
  }

  /**
   * The exemption is the other way out of the queue, and the componentKey is the
   * one thing that must not be retyped: an exemption keyed on a typo matches
   * nothing and looks approved.
   */
  function askExemption(entry: FirewallQuarantineEntry) {
    const params = new URLSearchParams({ componentKey: entry.componentKey, request: '1' });
    if (entry.repositoryId) {
      params.set('repositoryId', entry.repositoryId);
    }
    navigate(`/admin/firewall/exemptions?${params.toString()}`);
  }

  const repositories = overview?.repositories ?? [];

  return (
    <div className="p-6 sm:p-8 max-w-7xl space-y-6">
      <div>
        <h1 className="text-2xl font-semibold text-gray-950">Repository Firewall</h1>
        <p className="text-sm text-gray-500 mt-1">
          Components the firewall is holding rather than refusing outright. Most of them leave on
          their own.
        </p>
      </div>

      <FirewallNav />
      <FactsDisabledBanner factsEnabled={overview?.factsEnabled} />

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
            <option value="QUARANTINED">Held</option>
            <option value="RELEASED">Released</option>
            <option value="BLOCKED">Blocked</option>
            <option value="">All states</option>
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

        <label className="flex flex-col gap-1">
          <span className="text-xs text-gray-500">Reason</span>
          <select
            aria-label="Reason"
            value={filters.reason}
            onChange={(e) => setFilter('reason', e.target.value)}
            className="px-3 py-2 border border-gray-300 rounded-md text-sm"
          >
            <option value="">All reasons</option>
            {(Object.keys(REASON_LABEL) as FirewallQuarantineReason[]).map((reason) => (
              <option key={reason} value={reason}>
                {REASON_LABEL[reason]}
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
            placeholder="part of a purl or coordinate"
            onChange={(e) => setFilter('search', e.target.value)}
            className="px-3 py-2 border border-gray-300 rounded-md text-sm"
          />
        </label>
      </div>

      {loading ? (
        <div className="flex justify-center items-center py-20">
          <LoadingSpinner message="Loading the quarantine queue..." />
        </div>
      ) : failed ? (
        <ErrorState
          title="Failed to load the quarantine queue"
          message={failed}
          onRetry={() => load()}
        />
      ) : entries.length === 0 ? (
        <div className="bg-white rounded-lg border border-gray-200 p-10 text-center">
          <p className="text-sm text-gray-500">
            Nothing is being held with these filters. An empty queue on an armed instance is the
            normal state — entries appear when a rule holds something rather than refusing it.
          </p>
        </div>
      ) : (
        <div className="bg-white rounded-lg border border-gray-200">
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-left text-xs font-medium text-gray-500 border-b border-gray-200">
                  <th className="py-2 pl-4 pr-4">Component</th>
                  <th className="py-2 pr-4">Repository</th>
                  <th className="py-2 pr-4">Reason</th>
                  <th className="py-2 pr-4">State</th>
                  <th className="py-2 pr-4">Held for</th>
                  <th className="py-2 pr-4">Hits</th>
                  <th className="py-2 pr-4">Next check</th>
                  <th className="py-2 pr-4" />
                </tr>
              </thead>
              <tbody>
                {entries.map((entry) => {
                  const style = STATE_STYLE[entry.state];
                  const open = expanded === entry.id;
                  return (
                    <Fragment key={entry.id}>
                      <tr className="border-b border-gray-100 align-top">
                        <td className="py-2.5 pl-4 pr-4">
                          <button
                            onClick={() => setExpanded(open ? null : entry.id)}
                            aria-expanded={open}
                            className="text-left font-mono text-xs text-blue-700 hover:underline break-all"
                          >
                            {entry.componentKey}
                          </button>
                          {entry.path && (
                            <div className="text-[11px] text-gray-400 break-all">{entry.path}</div>
                          )}
                        </td>
                        <td className="py-2.5 pr-4 text-xs text-gray-700">
                          {entry.repositoryName ?? '—'}
                        </td>
                        <td className="py-2.5 pr-4">
                          <span
                            className="text-xs text-gray-700"
                            title={REASON_HINT[entry.reason]}
                          >
                            {REASON_LABEL[entry.reason] ?? humanizeEnum(entry.reason)}
                          </span>
                        </td>
                        <td className="py-2.5 pr-4">
                          <span
                            title={style.detail}
                            className={`inline-block px-2 py-0.5 rounded border text-xs font-semibold ${style.className}`}
                          >
                            {style.label}
                          </span>
                        </td>
                        <td className="py-2.5 pr-4 text-xs text-gray-600 whitespace-nowrap">
                          {formatAge(entry.firstSeen)}
                        </td>
                        <td className="py-2.5 pr-4 font-mono text-xs text-gray-700">
                          {entry.hitCount}
                        </td>
                        {/*
                          The column the queue exists for. A held entry with a next
                          check in six hours needs nobody; one with none needs a
                          decision, and saying so in the cell is cheaper than
                          teaching every operator the state machine.
                        */}
                        <td className="py-2.5 pr-4 text-xs whitespace-nowrap">
                          {entry.state !== 'QUARANTINED' ? (
                            <span className="text-gray-400">not re-evaluated</span>
                          ) : entry.nextEvaluationAt ? (
                            <span
                              className="text-gray-700"
                              title={formatTimestamp(entry.nextEvaluationAt)}
                            >
                              {formatRelative(entry.nextEvaluationAt)}
                            </span>
                          ) : (
                            <span className="text-amber-700" title="Nothing will lift this by itself.">
                              needs a decision
                            </span>
                          )}
                        </td>
                        <td className="py-2.5 pr-4 text-right whitespace-nowrap">
                          {entry.state === 'QUARANTINED' && (
                            <span className="inline-flex gap-1">
                              <button
                                onClick={() => askRelease(entry)}
                                disabled={busy}
                                className="px-2 py-1 text-xs font-medium rounded border border-gray-300 bg-white hover:bg-gray-50 disabled:opacity-50"
                              >
                                Release
                              </button>
                              <button
                                onClick={() => askBlock(entry)}
                                disabled={busy}
                                className="px-2 py-1 text-xs font-medium rounded border border-red-300 text-red-700 bg-white hover:bg-red-50 disabled:opacity-50"
                              >
                                Block
                              </button>
                              <button
                                onClick={() => askExemption(entry)}
                                className="px-2 py-1 text-xs font-medium rounded border border-gray-300 bg-white hover:bg-gray-50"
                              >
                                Exempt…
                              </button>
                            </span>
                          )}
                        </td>
                      </tr>
                      {open && (
                        <tr className="border-b border-gray-100 bg-gray-50">
                          <td colSpan={8} className="px-4 py-4">
                            <QuarantineDetail entry={entry} />
                          </td>
                        </tr>
                      )}
                    </Fragment>
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

      {prompt && (
        <NoteDialog
          prompt={prompt}
          busy={busy}
          error={promptError}
          onCancel={() => {
            setPrompt(null);
            setPromptError(null);
          }}
        />
      )}
    </div>
  );
}

function QuarantineDetail({ entry }: { entry: FirewallQuarantineEntry }) {
  return (
    <dl className="grid grid-cols-1 md:grid-cols-2 gap-x-8 gap-y-1.5 text-xs">
      <Field label="First seen">{formatTimestamp(entry.firstSeen)}</Field>
      <Field label="Last requested">{formatTimestamp(entry.lastSeen)}</Field>
      <Field label="Last evaluated">{formatTimestamp(entry.lastEvaluatedAt)}</Field>
      <Field label="Next evaluation">{formatTimestamp(entry.nextEvaluationAt)}</Field>
      <Field label="Policy">{entry.policyName ?? entry.policyId ?? '—'}</Field>
      <Field label="Resolution">{humanizeEnum(entry.resolution)}</Field>
      <Field label="Advisories">
        <AdvisoryLinks ids={entry.advisoryIds ?? []} />
      </Field>
      <Field label="Exemption">
        {entry.exemptionId ? (
          <a
            href={`/admin/firewall/exemptions?search=${encodeURIComponent(entry.componentKey)}`}
            className="text-blue-600 hover:underline font-mono"
          >
            {entry.exemptionId}
          </a>
        ) : (
          '—'
        )}
      </Field>
      {entry.decidedBy && (
        <Field label="Decided by">
          {entry.decidedBy} · {formatTimestamp(entry.decidedAt)}
        </Field>
      )}
      {entry.decisionReason && (
        <div className="md:col-span-2 mt-1">
          <dt className="text-gray-500">Decision note</dt>
          <dd className="text-gray-800">{entry.decisionReason}</dd>
        </div>
      )}
      {entry.evaluation && Object.keys(entry.evaluation).length > 0 && (
        <div className="md:col-span-2 mt-2">
          <dt className="text-gray-500 mb-1">Evaluation snapshot</dt>
          <dd>
            <pre className="bg-white border border-gray-200 rounded p-3 overflow-x-auto text-[11px] text-gray-700">
              {JSON.stringify(entry.evaluation, null, 2)}
            </pre>
          </dd>
        </div>
      )}
    </dl>
  );
}

function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex gap-2">
      <dt className="text-gray-500 min-w-32">{label}</dt>
      <dd className="text-gray-800 break-all">{children}</dd>
    </div>
  );
}
