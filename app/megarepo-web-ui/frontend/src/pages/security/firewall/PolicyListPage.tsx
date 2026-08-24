import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { ApiError } from '../../../api/client';
import { policyApi, requiredConfirmationFrom } from '../../../api/firewall';
import ConfirmDialog from '../../../components/ConfirmDialog';
import ErrorState from '../../../components/ErrorState';
import LoadingSpinner from '../../../components/LoadingSpinner';
import { useToast } from '../../../components/Toast';
import type { FirewallPolicy } from '../../../types/firewall';
import ConfirmPhraseDialog, { type ConfirmPrompt } from './ConfirmPhraseDialog';
import FirewallNav from './FirewallNav';
import { formatTimestamp } from './format';

/**
 * The policies this instance has, and which of them is doing anything.
 *
 * Two numbers carry the page. `assignedRepositories` is how many repositories
 * name this policy; `enforcingRepositories` is how many of those are actually
 * blocking with it. The gap between them is where the surprises live — a policy
 * edited confidently because "it is only on the test repo" turns out to be the
 * default, and the default is what every unassigned repository uses.
 */
export default function PolicyListPage() {
  const { showToast } = useToast();
  const [policies, setPolicies] = useState<FirewallPolicy[]>([]);
  const [loading, setLoading] = useState(true);
  const [failed, setFailed] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [deleting, setDeleting] = useState<FirewallPolicy | null>(null);
  const [phrasePrompt, setPhrasePrompt] = useState<ConfirmPrompt | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setPolicies(await policyApi.list());
      setFailed(null);
    } catch (error) {
      setFailed(error instanceof Error ? error.message : 'Failed to load policies');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  /**
   * Moving the default is a write against every repository that never chose a
   * policy, so the server may demand a typed confirmation for it. Which phrase
   * it demands is B2's business — the attempt is made without one, and the
   * server's rejection carries the phrase to ask for.
   */
  async function makeDefault(policy: FirewallPolicy, confirmation?: string) {
    setBusy(true);
    try {
      await policyApi.makeDefault(policy.id, confirmation);
      await load();
      showToast('success', `${policy.name} is now the default policy.`);
      setPhrasePrompt(null);
    } catch (error) {
      const phrase = requiredConfirmationFrom(error);
      if (phrase && !confirmation) {
        setPhrasePrompt({
          title: `Make ${policy.name} the default?`,
          body:
            'Every repository that has not been given a policy of its own switches to this one, ' +
            'including the enforcing ones. What they deny changes as soon as you confirm.',
          phrase,
          confirmLabel: 'Make default',
          onConfirm: (typed) => makeDefault(policy, typed),
        });
      } else {
        showToast('error', error instanceof Error ? error.message : 'Failed to set the default');
      }
    } finally {
      setBusy(false);
    }
  }

  async function remove(policy: FirewallPolicy) {
    setBusy(true);
    try {
      await policyApi.remove(policy.id);
      setDeleting(null);
      await load();
      showToast('success', `${policy.name} deleted.`);
    } catch (error) {
      setDeleting(null);
      const message =
        error instanceof ApiError && error.isConflict
          ? error.message
          : error instanceof Error
            ? error.message
            : 'Failed to delete the policy';
      showToast('error', message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="p-6 sm:p-8 max-w-7xl space-y-6">
      <div className="flex items-start justify-between gap-4">
        <div>
          <h1 className="text-2xl font-semibold text-gray-950">Repository Firewall</h1>
          <p className="text-sm text-gray-500 mt-1">
            A policy is a set of rules and what each one does when it matches. Repositories point at
            one; a repository policy <strong>replaces</strong> the default rather than adding to it.
          </p>
        </div>
        <Link
          to="/admin/firewall/policies/new"
          className="shrink-0 px-4 py-2 text-sm font-semibold rounded-md bg-blue-600 hover:bg-blue-700 text-white"
        >
          New policy
        </Link>
      </div>

      <FirewallNav />

      {loading ? (
        <div className="flex justify-center items-center py-20">
          <LoadingSpinner message="Loading policies..." />
        </div>
      ) : failed ? (
        <ErrorState title="Failed to load policies" message={failed} onRetry={() => load()} />
      ) : policies.length === 0 ? (
        <div className="bg-white rounded-lg border border-gray-200 p-10 text-center">
          <p className="text-sm text-gray-500">
            No policies yet. The instance ships one seeded default — if this list is empty, nothing
            is being evaluated against anything.
          </p>
        </div>
      ) : (
        <div className="bg-white rounded-lg border border-gray-200 overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="text-left text-xs font-medium text-gray-500 border-b border-gray-200">
                <th className="py-2 pl-4 pr-4">Policy</th>
                <th className="py-2 pr-4">Rules</th>
                <th className="py-2 pr-4">Assigned</th>
                <th className="py-2 pr-4">Enforcing</th>
                <th className="py-2 pr-4">Created</th>
                <th className="py-2 pr-4" />
              </tr>
            </thead>
            <tbody>
              {policies.map((policy) => {
                const blocking = policy.rules.filter(
                  (rule) => rule.enabled && rule.action === 'BLOCK',
                ).length;
                return (
                  <tr key={policy.id} className="border-b border-gray-100 align-top">
                    <td className="py-2.5 pl-4 pr-4">
                      <Link
                        to={`/admin/firewall/policies/${policy.id}`}
                        className="font-medium text-blue-700 hover:underline"
                      >
                        {policy.name}
                      </Link>
                      {policy.isDefault && (
                        <span className="ml-2 inline-block px-1.5 py-0.5 rounded bg-blue-50 text-blue-700 text-[10px] font-semibold uppercase tracking-wide">
                          default
                        </span>
                      )}
                      {policy.description && (
                        <div className="text-xs text-gray-500 mt-0.5">{policy.description}</div>
                      )}
                    </td>
                    <td className="py-2.5 pr-4 text-xs text-gray-700">
                      {policy.rules.length}
                      {blocking > 0 && (
                        <span className="text-gray-500"> · {blocking} blocking</span>
                      )}
                    </td>
                    <td className="py-2.5 pr-4 font-mono text-xs text-gray-700">
                      {policy.assignedRepositories}
                    </td>
                    <td className="py-2.5 pr-4">
                      <span
                        className={`font-mono text-xs ${
                          policy.enforcingRepositories > 0 ? 'text-red-700 font-semibold' : 'text-gray-500'
                        }`}
                        title={
                          policy.enforcingRepositories > 0
                            ? 'Repositories that refuse downloads with this policy right now.'
                            : 'Nothing is blocking with this policy.'
                        }
                      >
                        {policy.enforcingRepositories}
                      </span>
                    </td>
                    <td className="py-2.5 pr-4 text-xs text-gray-500">
                      {formatTimestamp(policy.createdAt)}
                      {policy.createdBy && <div className="text-gray-400">{policy.createdBy}</div>}
                    </td>
                    <td className="py-2.5 pr-4 text-right whitespace-nowrap">
                      {!policy.isDefault && (
                        <button
                          onClick={() => makeDefault(policy)}
                          disabled={busy}
                          className="px-2 py-1 text-xs font-medium rounded border border-gray-300 bg-white hover:bg-gray-50 disabled:opacity-50"
                        >
                          Make default
                        </button>
                      )}
                      <button
                        onClick={() => setDeleting(policy)}
                        disabled={busy || policy.isDefault}
                        title={
                          policy.isDefault
                            ? 'The default policy cannot be deleted — make another one default first.'
                            : undefined
                        }
                        className="ml-1 px-2 py-1 text-xs font-medium rounded border border-red-300 text-red-700 bg-white hover:bg-red-50 disabled:opacity-40"
                      >
                        Delete
                      </button>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}

      <ConfirmDialog
        open={deleting !== null}
        title={`Delete ${deleting?.name ?? ''}?`}
        message={
          deleting && deleting.assignedRepositories > 0
            ? `${deleting.assignedRepositories} repositor${
                deleting.assignedRepositories === 1 ? 'y falls' : 'ies fall'
              } back to the default policy, and what they deny changes immediately.`
            : 'No repository uses this policy, so nothing changes for anyone.'
        }
        confirmLabel="Delete"
        onCancel={() => setDeleting(null)}
        onConfirm={() => deleting && remove(deleting)}
      />

      {phrasePrompt && (
        <ConfirmPhraseDialog
          prompt={phrasePrompt}
          busy={busy}
          onCancel={() => setPhrasePrompt(null)}
          onConfirm={(typed) => phrasePrompt.onConfirm(typed)}
        />
      )}
    </div>
  );
}
