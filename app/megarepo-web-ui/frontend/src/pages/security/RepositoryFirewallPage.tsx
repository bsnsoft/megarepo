import { useState, useEffect, useCallback } from 'react';
import { api } from '../../api/client';
import { assignRepositoryPolicy, policyApi, requiredConfirmationFrom } from '../../api/firewall';
import LoadingSpinner from '../../components/LoadingSpinner';
import ErrorState from '../../components/ErrorState';
import { useToast } from '../../components/Toast';
import type {
  FirewallEffectiveState,
  FirewallMode,
  FirewallOverview,
  FirewallRepositoryState,
  FirewallViolation,
  PageResponse,
} from '../../types/api';
import type { FirewallPolicy } from '../../types/firewall';
import ConfirmPhraseDialog, { type ConfirmPrompt } from './firewall/ConfirmPhraseDialog';
import FactsDisabledBanner from './firewall/FactsDisabledBanner';
import FirewallNav from './firewall/FirewallNav';
import ViolationDetailDialog from './firewall/ViolationDetailDialog';

/**
 * Repository Firewall — the operator's control surface.
 *
 * The page has one job above every other: nobody looking at it may come away
 * with the wrong idea about whether this instance is blocking. Two things make
 * that hard, and both are handled deliberately here.
 *
 * 1. "Quarantine" is an intent, not a state. A repository can be configured for
 *    it while the instance is not armed, in which case it blocks nothing. The
 *    backend resolves the pair into `effectiveState` and every row renders that,
 *    never the raw mode on its own.
 * 2. A status that has to be assembled from several reads can contradict itself.
 *    The switch, the repositories and the summary therefore come from one call
 *    (`/admin/firewall/status`), so the banner and the rows are always the same
 *    moment.
 */

const MODE_LABEL: Record<FirewallMode, string> = {
  OFF: 'Off',
  AUDIT: 'Audit',
  QUARANTINE: 'Quarantine',
};

const MODE_HINT: Record<FirewallMode, string> = {
  OFF: 'Not evaluated at all.',
  AUDIT: 'Evaluate and record. Never blocks, armed or not.',
  QUARANTINE: 'Ask for blocking. Takes effect only while enforcement is on.',
};

interface StateStyle {
  label: string;
  detail: string;
  className: string;
  dot: string;
}

/** How each effective state is rendered. The wording is the safety feature. */
const STATE_STYLE: Record<FirewallEffectiveState, StateStyle> = {
  NOT_EVALUATED: {
    label: 'Not evaluated',
    detail: 'The firewall ignores this repository.',
    className: 'bg-gray-100 text-gray-600 border-gray-200',
    dot: 'bg-gray-400',
  },
  OBSERVING: {
    label: 'Observing',
    detail: 'Findings are recorded. Every download is served.',
    className: 'bg-blue-50 text-blue-700 border-blue-200',
    dot: 'bg-blue-500',
  },
  QUARANTINE_NOT_ENFORCED: {
    label: 'Not blocking',
    detail: 'Set to Quarantine, but enforcement is off — downloads are served.',
    className: 'bg-amber-50 text-amber-800 border-amber-300',
    dot: 'bg-amber-500',
  },
  BLOCKING: {
    label: 'Blocking',
    detail: 'Matching downloads are refused.',
    className: 'bg-red-50 text-red-700 border-red-300',
    dot: 'bg-red-600',
  },
};

const formatTimestamp = (iso: string | null): string =>
  iso ? new Date(iso).toLocaleString() : '—';

/**
 * A group holds no components of its own: it routes to a member, and the member
 * that resolves the request is the one whose mode and policy decide. The server
 * refuses a non-OFF mode on a group with a 400 rather than storing something
 * that governs nothing — so the control is disabled here too, with the reason on
 * the row. Leaving it enabled and letting the request fail would teach the
 * operator that the firewall is broken, not that groups work differently.
 */
const GROUP_TOOLTIP =
  'Enforcement follows the resolving member. A group routes to its members and holds nothing of ' +
  'its own, so set the mode and policy on the member repositories instead.';

function isGroup(repository: FirewallRepositoryState): boolean {
  return (repository.type ?? '').toLowerCase() === 'group';
}

export default function RepositoryFirewallPage() {
  const { showToast } = useToast();

  const [overview, setOverview] = useState<FirewallOverview | null>(null);
  const [violations, setViolations] = useState<FirewallViolation[]>([]);
  const [nextToken, setNextToken] = useState<string | null>(null);
  const [violationFilter, setViolationFilter] = useState<string>('');
  const [policies, setPolicies] = useState<FirewallPolicy[] | null>(null);
  const [openViolation, setOpenViolation] = useState<FirewallViolation | null>(null);

  const [loading, setLoading] = useState(true);
  const [failed, setFailed] = useState(false);
  const [busy, setBusy] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);

  const [confirmPrompt, setConfirmPrompt] = useState<ConfirmPrompt | null>(null);

  const loadOverview = useCallback(async () => {
    try {
      setOverview(await api.get<FirewallOverview>('/admin/firewall/status'));
      setFailed(false);
    } catch {
      setFailed(true);
    } finally {
      setLoading(false);
    }
  }, []);

  const loadViolations = useCallback(
    async (repositoryId: string) => {
      const query = repositoryId ? `?repositoryId=${encodeURIComponent(repositoryId)}` : '';
      try {
        const page = await api.get<PageResponse<FirewallViolation>>(
          `/admin/firewall/violations${query}`,
        );
        setViolations(page.items);
        setNextToken(page.continuationToken);
      } catch {
        showToast('error', 'Failed to load recorded findings');
      }
    },
    [showToast],
  );

  useEffect(() => {
    loadOverview();
  }, [loadOverview]);

  useEffect(() => {
    loadViolations(violationFilter);
  }, [loadViolations, violationFilter]);

  /**
   * The policy list is optional furniture: on a build without the policy
   * endpoint the column simply does not appear, and the rest of the page — which
   * is the part that says whether anything is being blocked — is unaffected.
   */
  useEffect(() => {
    policyApi
      .list()
      .then(setPolicies)
      .catch(() => setPolicies(null));
  }, []);

  async function loadMoreViolations() {
    if (!nextToken) return;
    setLoadingMore(true);
    try {
      const params = new URLSearchParams();
      if (violationFilter) params.set('repositoryId', violationFilter);
      params.set('continuationToken', nextToken);
      const page = await api.get<PageResponse<FirewallViolation>>(
        `/admin/firewall/violations?${params.toString()}`,
      );
      setViolations((prev) => [...prev, ...page.items]);
      setNextToken(page.continuationToken);
    } catch {
      showToast('error', 'Failed to load more findings');
    } finally {
      setLoadingMore(false);
    }
  }

  async function writeEnforcement(enabled: boolean, confirmation?: string) {
    setBusy(true);
    try {
      await api.put('/admin/firewall/enforcement', { enabled, confirmation });
      await loadOverview();
      showToast(
        enabled ? 'warning' : 'success',
        enabled
          ? 'Enforcement is on. Repositories set to Quarantine now block downloads.'
          : 'Enforcement is off. Nothing is being blocked.',
      );
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Failed to change enforcement');
    } finally {
      setBusy(false);
    }
  }

  async function writeMode(repository: FirewallRepositoryState, mode: FirewallMode, confirmation?: string) {
    setBusy(true);
    try {
      await api.put(`/admin/firewall/repositories/${repository.repositoryId}`, { mode, confirmation });
      await loadOverview();
      showToast('success', `${repository.repositoryName} set to ${MODE_LABEL[mode]}`);
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Failed to change mode');
    } finally {
      setBusy(false);
    }
  }

  /**
   * Assigning a policy to a repository. A repository policy *replaces* the
   * default — it does not stack on top of it — which is the sentence next to the
   * control, because the opposite assumption is the natural one and it is wrong.
   *
   * Like arming, this can change what an enforcing repository denies, so the
   * server may demand a typed confirmation. The phrase comes back in the
   * server's own rejection rather than from a constant here.
   */
  async function writePolicy(
    repository: FirewallRepositoryState,
    policyId: string | null,
    confirmation?: string,
  ) {
    setBusy(true);
    try {
      await assignRepositoryPolicy(repository.repositoryId, {
        policyId,
        failMode: repository.failMode,
        confirmation,
      });
      await loadOverview();
      const policyName = policies?.find((policy) => policy.id === policyId)?.name;
      showToast(
        'success',
        policyId
          ? `${repository.repositoryName} now uses ${policyName ?? 'the selected policy'} instead of the default.`
          : `${repository.repositoryName} falls back to the default policy.`,
      );
      setConfirmPrompt(null);
    } catch (e) {
      const phrase = requiredConfirmationFrom(e);
      if (phrase && !confirmation) {
        setConfirmPrompt({
          title: `Change the policy for ${repository.repositoryName}?`,
          body:
            'The repository is enforcing, so what it refuses changes as soon as you confirm. ' +
            'A repository policy replaces the instance default; it is not added to it.',
          phrase,
          confirmLabel: 'Change policy',
          onConfirm: (typed) => writePolicy(repository, policyId, typed),
        });
      } else {
        showToast('error', e instanceof Error ? e.message : 'Failed to change the policy');
      }
    } finally {
      setBusy(false);
    }
  }

  /**
   * Turning enforcement on, and moving a repository into Quarantine, are the two
   * actions that can start failing other people's builds. Both ask for the exact
   * phrase the API demands; reverting either is a plain click. A dialog you can
   * dismiss with Enter would not be a guard here — the damage is silent and
   * lands on someone else's CI, not on the person clicking.
   */
  function handleEnforcementToggle() {
    if (!overview) return;
    if (overview.enforcement.enabled) {
      writeEnforcement(false);
      return;
    }
    setConfirmPrompt({
      title: 'Turn on enforcement?',
      body:
        overview.summary.quarantineNotEnforced > 0
          ? `${overview.summary.quarantineNotEnforced} ${
              overview.summary.quarantineNotEnforced === 1 ? 'repository is' : 'repositories are'
            } set to Quarantine and will start refusing downloads immediately. Builds that pull a flagged component will fail.`
          : 'No repository is set to Quarantine yet, so nothing will be blocked until you set one. From then on, blocking is live.',
      phrase: overview.enforcement.requiredConfirmation,
      confirmLabel: 'Turn on enforcement',
      onConfirm: (phrase) => writeEnforcement(true, phrase),
    });
  }

  function handleModeChange(repository: FirewallRepositoryState, mode: FirewallMode) {
    if (mode === repository.mode) return;
    if (mode !== 'QUARANTINE') {
      writeMode(repository, mode);
      return;
    }
    setConfirmPrompt({
      title: `Set ${repository.repositoryName} to Quarantine?`,
      body: overview?.enforcement.enabled
        ? 'Enforcement is on, so this repository starts refusing flagged downloads as soon as you confirm.'
        : 'Enforcement is off, so this changes nothing yet — the repository keeps serving every download. It starts blocking the moment enforcement is turned on.',
      phrase: `QUARANTINE ${repository.repositoryName}`,
      confirmLabel: 'Set to Quarantine',
      onConfirm: (phrase) => writeMode(repository, mode, phrase),
    });
  }

  if (loading) {
    return (
      <div className="flex justify-center items-center py-20">
        <LoadingSpinner message="Loading Repository Firewall..." />
      </div>
    );
  }

  if (failed || !overview) {
    return (
      <ErrorState
        title="Failed to load Repository Firewall"
        message="The firewall administration API could not be reached. It requires the nx-admin role."
        onRetry={() => {
          setLoading(true);
          loadOverview();
        }}
      />
    );
  }

  const { enforcement, summary, repositories, violationWindowDays } = overview;
  const active = enforcement.enabled;

  return (
    <div className="p-6 sm:p-8 max-w-7xl space-y-6">
      <div>
        <h1 className="text-2xl font-semibold text-gray-950">Repository Firewall</h1>
        <p className="text-sm text-gray-500 mt-1">
          Evaluates downloaded components against known advisories. It observes until you arm it —
          nothing is blocked before then.
        </p>
      </div>

      <FirewallNav />
      <FactsDisabledBanner factsEnabled={overview.factsEnabled} />

      {/* ── The one thing nobody may misread ─────────────────────────── */}
      <div
        className={`rounded-lg border-2 p-5 ${
          active ? 'bg-red-50 border-red-400' : 'bg-slate-50 border-slate-300'
        }`}
      >
        <div className="flex items-start gap-4">
          <span
            className={`mt-1 w-3 h-3 rounded-full shrink-0 ${
              active ? 'bg-red-600 animate-pulse' : 'bg-slate-400'
            }`}
          />
          <div className="flex-1">
            <div className="flex items-center gap-3 flex-wrap">
              <h2
                className={`text-xl font-bold tracking-tight ${
                  active ? 'text-red-800' : 'text-slate-700'
                }`}
              >
                {active ? 'ACTIVE — downloads can be blocked' : 'PASSIVE — nothing is blocked'}
              </h2>
            </div>
            <p className={`text-sm mt-1.5 ${active ? 'text-red-900' : 'text-slate-600'}`}>
              {active
                ? summary.blocking > 0
                  ? `Enforcement is on. ${summary.blocking} ${
                      summary.blocking === 1 ? 'repository refuses' : 'repositories refuse'
                    } flagged downloads. Builds pulling a flagged component will fail.`
                  : 'Enforcement is on, but no repository is set to Quarantine — so nothing is being blocked yet.'
                : 'Enforcement is off. Every download is served. The firewall only records what it would have caught.'}
            </p>

            {/* The dangerous illusion, called out where it cannot be missed. */}
            {!active && summary.quarantineNotEnforced > 0 && (
              <div className="mt-3 flex items-start gap-2 rounded-md border border-amber-300 bg-amber-50 px-3 py-2">
                <svg
                  className="w-4 h-4 text-amber-600 shrink-0 mt-0.5"
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth="2"
                  strokeLinecap="round"
                  strokeLinejoin="round"
                >
                  <path d="M10.29 3.86L1.82 18a2 2 0 001.71 3h16.94a2 2 0 001.71-3L13.71 3.86a2 2 0 00-3.42 0z" />
                  <line x1="12" y1="9" x2="12" y2="13" />
                  <line x1="12" y1="17" x2="12.01" y2="17" />
                </svg>
                <p className="text-xs text-amber-900">
                  <strong>
                    {summary.quarantineNotEnforced}{' '}
                    {summary.quarantineNotEnforced === 1 ? 'repository is' : 'repositories are'} set
                    to Quarantine but{' '}
                    {summary.quarantineNotEnforced === 1 ? 'is' : 'are'} not blocking.
                  </strong>{' '}
                  Their configuration asks for protection that enforcement being off does not
                  deliver. Turn enforcement on, or set them back to Audit so the configuration says
                  what actually happens.
                </p>
              </div>
            )}

            <div className="flex flex-wrap items-center gap-x-5 gap-y-1 mt-3 text-xs text-gray-600">
              <span>
                <strong className="text-gray-900">{summary.blocking}</strong> blocking
              </span>
              <span>
                <strong className="text-gray-900">{summary.quarantineNotEnforced}</strong> quarantine,
                not enforced
              </span>
              <span>
                <strong className="text-gray-900">{summary.observing}</strong> observing
              </span>
              <span>
                <strong className="text-gray-900">{summary.notEvaluated}</strong> not evaluated
              </span>
            </div>
          </div>

          <div className="shrink-0 text-right">
            <button
              onClick={handleEnforcementToggle}
              disabled={busy}
              className={`px-4 py-2 text-sm font-semibold rounded-md disabled:opacity-50 ${
                active
                  ? 'bg-white border border-gray-300 text-gray-800 hover:bg-gray-50'
                  : 'bg-red-600 hover:bg-red-700 text-white'
              }`}
            >
              {active ? 'Turn off enforcement' : 'Turn on enforcement'}
            </button>
            <p className="text-[11px] text-gray-500 mt-2">
              {enforcement.updatedBy
                ? `Last changed by ${enforcement.updatedBy}`
                : 'Never changed'}
              <br />
              {formatTimestamp(enforcement.updatedAt)}
            </p>
          </div>
        </div>
      </div>

      {/* ── Per-repository ───────────────────────────────────────────── */}
      <div className="bg-white rounded-lg border border-gray-200">
        <div className="p-6">
          <h2 className="text-lg font-semibold text-gray-950 mb-1">Repositories</h2>
          <p className="text-xs text-gray-500 mb-4">
            <strong>Mode</strong> is what the repository is configured to do.{' '}
            <strong>Effective</strong> is what it actually does right now, given the enforcement
            switch above. They differ whenever Quarantine is configured on a passive instance.{' '}
            <strong>Policy</strong> is which rule set decides — a policy chosen here{' '}
            <em>replaces</em> the instance default rather than adding to it.
          </p>

          {repositories.length === 0 ? (
            <p className="text-sm text-gray-400 italic">No repositories exist yet.</p>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="text-left text-xs font-medium text-gray-500 border-b border-gray-200">
                    <th className="py-2 pr-4">Repository</th>
                    <th className="py-2 pr-4">Mode</th>
                    {policies && <th className="py-2 pr-4">Policy</th>}
                    <th className="py-2 pr-4">Effective</th>
                    <th className="py-2 pr-4">Findings ({violationWindowDays}d)</th>
                    <th className="py-2">Changed</th>
                  </tr>
                </thead>
                <tbody>
                  {repositories.map((repository) => {
                    const style = STATE_STYLE[repository.effectiveState];
                    const group = isGroup(repository);
                    return (
                      <tr key={repository.repositoryId} className="border-b border-gray-100">
                        <td className="py-2.5 pr-4">
                          <div className="font-medium text-gray-900">{repository.repositoryName}</div>
                          <div className="text-xs text-gray-400">
                            {repository.format} · {repository.type}
                          </div>
                        </td>
                        <td className="py-2.5 pr-4">
                          <select
                            value={group ? 'OFF' : repository.mode}
                            disabled={busy || group}
                            aria-label={`Mode for ${repository.repositoryName}`}
                            onChange={(e) =>
                              handleModeChange(repository, e.target.value as FirewallMode)
                            }
                            title={group ? GROUP_TOOLTIP : MODE_HINT[repository.mode]}
                            className="px-2 py-1 border border-gray-300 rounded-md text-sm disabled:opacity-50 disabled:bg-gray-50"
                          >
                            <option value="OFF">Off</option>
                            <option value="AUDIT">Audit</option>
                            <option value="QUARANTINE">Quarantine</option>
                          </select>
                          {group ? (
                            <div className="text-[11px] text-gray-500 mt-0.5 max-w-48">
                              follows the resolving member
                            </div>
                          ) : (
                            !repository.configured && (
                              <div className="text-[11px] text-gray-400 mt-0.5">instance default</div>
                            )
                          )}
                        </td>
                        {policies && (
                          <td className="py-2.5 pr-4">
                            <select
                              value={repository.policyId ?? ''}
                              disabled={busy || group}
                              aria-label={`Policy for ${repository.repositoryName}`}
                              title={group ? GROUP_TOOLTIP : undefined}
                              onChange={(e) => writePolicy(repository, e.target.value || null)}
                              className="px-2 py-1 border border-gray-300 rounded-md text-sm disabled:opacity-50 disabled:bg-gray-50"
                            >
                              <option value="">Instance default</option>
                              {policies.map((policy) => (
                                <option key={policy.id} value={policy.id}>
                                  {policy.name}
                                  {policy.isDefault ? ' (default)' : ''}
                                </option>
                              ))}
                            </select>
                          </td>
                        )}
                        <td className="py-2.5 pr-4">
                          <span
                            className={`inline-flex items-center gap-1.5 px-2 py-0.5 rounded border text-xs font-semibold ${style.className}`}
                            title={style.detail}
                          >
                            <span className={`w-1.5 h-1.5 rounded-full ${style.dot}`} />
                            {style.label}
                          </span>
                        </td>
                        <td className="py-2.5 pr-4">
                          {repository.violations > 0 ? (
                            <button
                              onClick={() => setViolationFilter(repository.repositoryId)}
                              className="text-blue-600 hover:underline font-mono text-xs"
                            >
                              {repository.violations}
                            </button>
                          ) : (
                            <span className="text-gray-400 font-mono text-xs">0</span>
                          )}
                        </td>
                        <td className="py-2.5 text-xs text-gray-500">
                          {formatTimestamp(repository.updatedAt)}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </div>

      {/* ── Evidence ─────────────────────────────────────────────────── */}
      <div className="bg-white rounded-lg border border-gray-200">
        <div className="p-6">
          <div className="flex items-start justify-between gap-4 mb-4">
            <div>
              <h2 className="text-lg font-semibold text-gray-950 mb-1">Recorded findings</h2>
              <p className="text-xs text-gray-500">
                What the firewall has caught, newest first. In Audit these were served anyway — the
                action column is what a policy <em>would</em> have done. This is the evidence to read
                before arming.
              </p>
            </div>
            <select
              value={violationFilter}
              onChange={(e) => setViolationFilter(e.target.value)}
              className="px-3 py-2 border border-gray-300 rounded-md text-sm shrink-0"
            >
              <option value="">All repositories</option>
              {repositories.map((repository) => (
                <option key={repository.repositoryId} value={repository.repositoryId}>
                  {repository.repositoryName}
                </option>
              ))}
            </select>
          </div>

          {violations.length === 0 ? (
            <p className="text-sm text-gray-400 italic">
              Nothing recorded yet. Set a repository to Audit and let downloads run through it.
            </p>
          ) : (
            <>
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead>
                    <tr className="text-left text-xs font-medium text-gray-500 border-b border-gray-200">
                      <th className="py-2 pr-4">When</th>
                      <th className="py-2 pr-4">Repository</th>
                      <th className="py-2 pr-4">Component</th>
                      <th className="py-2 pr-4">Rule</th>
                      <th className="py-2 pr-4">Action</th>
                      <th className="py-2">Advisories</th>
                    </tr>
                  </thead>
                  <tbody>
                    {violations.map((violation) => (
                      <tr key={violation.id} className="border-b border-gray-100">
                        <td className="py-2 pr-4 text-xs text-gray-600 whitespace-nowrap">
                          {formatTimestamp(violation.occurredAt)}
                        </td>
                        <td className="py-2 pr-4 text-gray-700 text-xs">{violation.repositoryName}</td>
                        <td className="py-2 pr-4 font-mono text-xs break-all">
                          {/*
                            The row is a summary; the detail behind it is where a
                            disagreement gets settled — which advisories, from which
                            sources, at what confidence, and for a license verdict the
                            exact string the component declared.
                          */}
                          <button
                            onClick={() => setOpenViolation(violation)}
                            className="text-left text-blue-700 hover:underline break-all"
                          >
                            {violation.purl}
                          </button>
                        </td>
                        <td className="py-2 pr-4 text-xs text-gray-600">{violation.ruleType}</td>
                        <td className="py-2 pr-4">
                          <span
                            className={`inline-block whitespace-nowrap px-2 py-0.5 rounded text-xs font-medium ${
                              violation.action === 'BLOCK'
                                ? 'bg-red-50 text-red-700'
                                : 'bg-amber-50 text-amber-700'
                            }`}
                          >
                            {violation.action === 'BLOCK' ? 'would block' : 'warn'}
                          </span>
                        </td>
                        <td className="py-2 text-xs">
                          {violation.advisoryIds.slice(0, 3).map((advisoryId) => (
                            <span key={advisoryId} className="inline-block mr-2 font-mono text-gray-700">
                              {advisoryId}
                            </span>
                          ))}
                          {violation.advisoryIds.length > 3 && (
                            <span className="text-gray-400">
                              +{violation.advisoryIds.length - 3} more
                            </span>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>

              {nextToken && (
                <div className="flex justify-center pt-4">
                  <button
                    onClick={loadMoreViolations}
                    disabled={loadingMore}
                    className="px-4 py-2 text-sm font-medium rounded-md border border-gray-300 bg-white hover:bg-gray-50 disabled:opacity-50"
                  >
                    {loadingMore ? 'Loading…' : 'Load more'}
                  </button>
                </div>
              )}
            </>
          )}
        </div>
      </div>

      {openViolation && (
        <ViolationDetailDialog
          violation={openViolation}
          onClose={() => setOpenViolation(null)}
        />
      )}

      {confirmPrompt && (
        <ConfirmPhraseDialog
          prompt={confirmPrompt}
          busy={busy}
          onCancel={() => setConfirmPrompt(null)}
          onConfirm={(phrase) => {
            const { onConfirm } = confirmPrompt;
            setConfirmPrompt(null);
            onConfirm(phrase);
          }}
        />
      )}
    </div>
  );
}
