import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { api } from '../../../api/client';
import { policyApi, requiredConfirmationFrom } from '../../../api/firewall';
import ErrorState from '../../../components/ErrorState';
import LoadingSpinner from '../../../components/LoadingSpinner';
import { useToast } from '../../../components/Toast';
import type { FirewallOverview } from '../../../types/api';
import type {
  FirewallAction,
  FirewallPolicy,
  FirewallPolicyRule,
  FirewallRuleType,
  FirewallRuleTypeInfo,
} from '../../../types/firewall';
import ConfirmPhraseDialog, { type ConfirmPrompt } from './ConfirmPhraseDialog';
import FactsDisabledBanner from './FactsDisabledBanner';
import FirewallNav from './FirewallNav';
import RuleConfigForm from './RuleConfigForm';
import { blockWarning, recommendedAction, ruleCopy, ruleLabel } from './ruleCatalog';

/**
 * The policy editor.
 *
 * Three things here are deliberate and would be easy to lose in a refactor.
 *
 * 1. **The rule set is replaced, not patched.** `FirewallPolicyUpsertXO` takes
 *    the complete list, and the form holds the complete list, so what is on
 *    screen is what will exist. A delta-based editor would let a rule survive a
 *    save that removed it from the screen.
 * 2. **The warning appears where the decision is made.** Not in a tooltip, not in
 *    documentation: switching a heuristic or UNKNOWN_COMPONENT to BLOCK puts the
 *    consequence directly under the control that did it, in the same interaction.
 * 3. **A rule this build cannot run says so.** `implemented: false` means the
 *    registry has no bean — the row renders as "not enforced by this version"
 *    with its action locked, because a working-looking switch that enforces
 *    nothing is worse than no switch at all.
 */

type Params = { id?: string };

interface DraftRule extends FirewallPolicyRule {
  /** Stable key for React across reorderings; not sent to the server. */
  localId: string;
}

let localIdCounter = 0;
function nextLocalId(): string {
  localIdCounter += 1;
  return `draft-${localIdCounter}`;
}

function toDraft(rule: FirewallPolicyRule): DraftRule {
  return { ...rule, config: rule.config ?? {}, localId: nextLocalId() };
}

export default function PolicyEditorPage() {
  const { id } = useParams<Params>();
  const navigate = useNavigate();
  const { showToast } = useToast();
  const [searchParams, setSearchParams] = useSearchParams();

  const creating = !id || id === 'new';

  const [ruleTypes, setRuleTypes] = useState<FirewallRuleTypeInfo[]>([]);
  const [policy, setPolicy] = useState<FirewallPolicy | null>(null);
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [makeDefault, setMakeDefault] = useState(false);
  const [rules, setRules] = useState<DraftRule[]>([]);
  const [factsEnabled, setFactsEnabled] = useState<boolean | undefined>(undefined);

  const [loading, setLoading] = useState(true);
  const [failed, setFailed] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [dirty, setDirty] = useState(false);
  const [phrasePrompt, setPhrasePrompt] = useState<ConfirmPrompt | null>(null);
  const [staged, setStaged] = useState<string | null>(null);

  const stagedLicenseApplied = useRef(false);

  const infoByType = useMemo(() => {
    const map = new Map<FirewallRuleType, FirewallRuleTypeInfo>();
    for (const info of ruleTypes) {
      map.set(info.ruleType, info);
    }
    return map;
  }, [ruleTypes]);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const types = await policyApi.ruleTypes();
      setRuleTypes(types);

      if (creating) {
        setPolicy(null);
        setName('');
        setDescription('');
        setRules([]);
        setMakeDefault(false);
      } else {
        // `/policies/default` is what a finding links to when it does not know
        // which policy decided — resolve it rather than 404ing the operator.
        const loaded =
          id === 'default'
            ? (await policyApi.list()).find((candidate) => candidate.isDefault) ?? null
            : await policyApi.get(id as string);
        if (!loaded) {
          throw new Error('This instance has no default policy.');
        }
        setPolicy(loaded);
        setName(loaded.name);
        setDescription(loaded.description ?? '');
        setRules((loaded.rules ?? []).map(toDraft));
        setMakeDefault(loaded.isDefault);
      }
      setFailed(null);
      setDirty(false);
    } catch (error) {
      setFailed(error instanceof Error ? error.message : 'Failed to load the policy');
    } finally {
      setLoading(false);
    }
  }, [creating, id]);

  useEffect(() => {
    load();
  }, [load]);

  useEffect(() => {
    api
      .get<FirewallOverview>('/admin/firewall/status')
      .then((overview) => setFactsEnabled(overview.factsEnabled))
      .catch(() => setFactsEnabled(undefined));
  }, []);

  /**
   * A license string handed over from a finding (`?license=…&list=allowed`).
   *
   * It is staged, never saved: the operator arrives from a violation, sees the
   * exact declared spelling already in the right list, and still has to press
   * Save. Anything else would turn a link in a detail dialog into a silent
   * policy change.
   */
  useEffect(() => {
    if (loading || stagedLicenseApplied.current) {
      return;
    }
    const license = searchParams.get('license');
    const list = searchParams.get('list');
    if (!license || (list !== 'allowed' && list !== 'denied')) {
      return;
    }
    stagedLicenseApplied.current = true;

    setRules((previous) => {
      const existing = previous.find((rule) => rule.ruleType === 'LICENSE');
      const target = existing ?? {
        ...blankRule('LICENSE', infoByType.get('LICENSE')),
        localId: nextLocalId(),
      };
      const config = { ...(target.config ?? {}) };
      const currentList = Array.isArray(config[list]) ? (config[list] as unknown[]).map(String) : [];
      if (!currentList.includes(license)) {
        config[list] = [...currentList, license];
      }
      const updated: DraftRule = { ...target, config };
      return existing
        ? previous.map((rule) => (rule.localId === existing.localId ? updated : rule))
        : [...previous, updated];
    });
    setDirty(true);
    setStaged(
      `“${license}” was added to the ${list} list of the LICENSE rule. Nothing is saved until you press Save.`,
    );

    const next = new URLSearchParams(searchParams);
    next.delete('license');
    next.delete('list');
    setSearchParams(next, { replace: true });
  }, [loading, searchParams, setSearchParams, infoByType]);

  function blankRule(
    ruleType: FirewallRuleType,
    info?: FirewallRuleTypeInfo,
  ): FirewallPolicyRule {
    return {
      id: null,
      ruleType,
      action: recommendedAction(ruleType, info),
      config: {},
      enabled: true,
      implemented: info?.implemented ?? true,
    };
  }

  function addRule(ruleType: FirewallRuleType) {
    setRules((previous) => [
      ...previous,
      { ...blankRule(ruleType, infoByType.get(ruleType)), localId: nextLocalId() },
    ]);
    setDirty(true);
  }

  function updateRule(localId: string, patch: Partial<DraftRule>) {
    setRules((previous) =>
      previous.map((rule) => (rule.localId === localId ? { ...rule, ...patch } : rule)),
    );
    setDirty(true);
  }

  function removeRule(localId: string) {
    setRules((previous) => previous.filter((rule) => rule.localId !== localId));
    setDirty(true);
  }

  function updateConfig(localId: string, key: string, value: unknown) {
    setRules((previous) =>
      previous.map((rule) => {
        if (rule.localId !== localId) {
          return rule;
        }
        const config = { ...(rule.config ?? {}) };
        if (value === undefined) {
          delete config[key];
        } else {
          config[key] = value;
        }
        return { ...rule, config };
      }),
    );
    setDirty(true);
  }

  async function save(confirmation?: string) {
    if (name.trim().length === 0) {
      showToast('error', 'A policy needs a name.');
      return;
    }
    setSaving(true);
    const body = {
      name: name.trim(),
      description: description.trim() || null,
      makeDefault,
      rules: rules.map(({ localId: _localId, ...rule }) => ({
        ...rule,
        config: rule.config ?? {},
      })),
      confirmation,
    };
    try {
      const saved = creating
        ? await policyApi.create(body)
        : await policyApi.replace(policy?.id ?? (id as string), body);
      setPhrasePrompt(null);
      setStaged(null);
      setDirty(false);
      showToast('success', `${saved.name} saved. Held components decided by it are re-evaluated now.`);
      navigate(`/admin/firewall/policies/${saved.id}`, { replace: true });
      setPolicy(saved);
      setRules((saved.rules ?? []).map(toDraft));
    } catch (error) {
      const phrase = requiredConfirmationFrom(error);
      if (phrase && !confirmation) {
        setPhrasePrompt({
          title: 'This changes what an enforcing repository denies',
          body:
            (policy?.enforcingRepositories ?? 0) > 0
              ? `${policy?.enforcingRepositories} repositor${
                  policy?.enforcingRepositories === 1 ? 'y is' : 'ies are'
                } blocking with this policy right now. Saving changes what they refuse, in both ` +
                'directions: a rule you added starts failing builds, a rule you removed stops ' +
                'holding components that are currently held.'
              : 'Saving changes what repositories using this policy refuse. Builds that passed ' +
                'yesterday can start failing.',
          phrase,
          confirmLabel: 'Save policy',
          onConfirm: (typed) => save(typed),
        });
      } else {
        showToast('error', error instanceof Error ? error.message : 'Failed to save the policy');
      }
    } finally {
      setSaving(false);
    }
  }

  const usedTypes = new Set(rules.map((rule) => rule.ruleType));
  const addableTypes = ruleTypes.filter((info) => !usedTypes.has(info.ruleType));

  if (loading) {
    return (
      <div className="flex justify-center items-center py-20">
        <LoadingSpinner message="Loading the policy..." />
      </div>
    );
  }

  if (failed) {
    return (
      <div className="p-6 sm:p-8 max-w-7xl space-y-6">
        <FirewallNav />
        <ErrorState title="Failed to load the policy" message={failed} onRetry={() => load()} />
      </div>
    );
  }

  return (
    <div className="p-6 sm:p-8 max-w-5xl space-y-6">
      <div>
        <h1 className="text-2xl font-semibold text-gray-950">
          {creating ? 'New policy' : policy?.name}
        </h1>
        <p className="text-sm text-gray-500 mt-1">
          Rules are evaluated together. A repository using this policy uses it{' '}
          <strong>instead of</strong> the default, never on top of it.
        </p>
      </div>

      <FirewallNav />
      <FactsDisabledBanner factsEnabled={factsEnabled} />

      {staged && (
        <div
          role="status"
          className="rounded-md border border-blue-300 bg-blue-50 px-4 py-3 text-xs text-blue-900"
        >
          {staged}
        </div>
      )}

      {!creating && (policy?.enforcingRepositories ?? 0) > 0 && (
        <div
          role="status"
          className="rounded-md border border-red-300 bg-red-50 px-4 py-3 text-xs text-red-900"
        >
          <strong>
            {policy?.enforcingRepositories}{' '}
            {policy?.enforcingRepositories === 1 ? 'repository is' : 'repositories are'} blocking
            with this policy right now.
          </strong>{' '}
          Every change you save here takes effect on the next download, and held components decided
          by it are re-evaluated immediately.
        </div>
      )}

      {/* ── Identity ─────────────────────────────────────────────────── */}
      <div className="bg-white rounded-lg border border-gray-200 p-6 space-y-4">
        <div>
          <label htmlFor="policy-name" className="block text-xs font-medium text-gray-700 mb-1">
            Name
          </label>
          <input
            id="policy-name"
            type="text"
            value={name}
            onChange={(e) => {
              setName(e.target.value);
              setDirty(true);
            }}
            className="w-full px-3 py-2 border border-gray-300 rounded-md text-sm"
          />
        </div>
        <div>
          <label htmlFor="policy-description" className="block text-xs font-medium text-gray-700 mb-1">
            Description
          </label>
          <input
            id="policy-description"
            type="text"
            value={description}
            onChange={(e) => {
              setDescription(e.target.value);
              setDirty(true);
            }}
            className="w-full px-3 py-2 border border-gray-300 rounded-md text-sm"
          />
        </div>
        <label className="inline-flex items-start gap-2">
          <input
            type="checkbox"
            checked={makeDefault}
            disabled={policy?.isDefault}
            onChange={(e) => {
              setMakeDefault(e.target.checked);
              setDirty(true);
            }}
            className="mt-0.5"
          />
          <span className="text-sm text-gray-800">
            Use as the default policy
            <span className="block text-xs text-gray-500">
              Every repository that has not been given one of its own uses the default. Moving the
              flag takes it away from the policy that holds it now.
            </span>
          </span>
        </label>
      </div>

      {/* ── Rules ────────────────────────────────────────────────────── */}
      <div className="space-y-4">
        <div className="flex items-center justify-between gap-4">
          <h2 className="text-lg font-semibold text-gray-950">Rules</h2>
          {addableTypes.length > 0 && (
            <select
              aria-label="Add rule"
              value=""
              onChange={(e) => {
                if (e.target.value) {
                  addRule(e.target.value as FirewallRuleType);
                }
              }}
              className="px-3 py-2 border border-gray-300 rounded-md text-sm"
            >
              <option value="">Add rule…</option>
              {addableTypes.map((info) => (
                <option key={info.ruleType} value={info.ruleType}>
                  {ruleLabel(info.ruleType, info.label)}
                  {info.implemented ? '' : ' (not enforced by this version)'}
                </option>
              ))}
            </select>
          )}
        </div>

        {rules.length === 0 ? (
          <div className="bg-white rounded-lg border border-gray-200 p-8 text-center text-sm text-gray-500">
            No rules. A policy with no rules denies nothing — the repositories using it serve
            everything, whatever the firewall switch says.
          </div>
        ) : (
          rules.map((rule) => (
            <RuleCard
              key={rule.localId}
              rule={rule}
              info={infoByType.get(rule.ruleType)}
              factsEnabled={factsEnabled}
              onChange={(patch) => updateRule(rule.localId, patch)}
              onConfigChange={(key, value) => updateConfig(rule.localId, key, value)}
              onRemove={() => removeRule(rule.localId)}
            />
          ))
        )}
      </div>

      <div className="flex items-center gap-3">
        <button
          onClick={() => save()}
          disabled={saving || (!dirty && !creating)}
          className="px-4 py-2 text-sm font-semibold rounded-md bg-blue-600 hover:bg-blue-700 text-white disabled:opacity-40"
        >
          {saving ? 'Saving…' : 'Save policy'}
        </button>
        <button
          onClick={() => navigate('/admin/firewall/policies')}
          className="px-4 py-2 text-sm font-medium rounded-md border border-gray-300 bg-white hover:bg-gray-50"
        >
          Cancel
        </button>
        {dirty && <span className="text-xs text-amber-700">Unsaved changes</span>}
      </div>

      {phrasePrompt && (
        <ConfirmPhraseDialog
          prompt={phrasePrompt}
          busy={saving}
          onCancel={() => setPhrasePrompt(null)}
          onConfirm={(typed) => phrasePrompt.onConfirm(typed)}
        />
      )}
    </div>
  );
}

function RuleCard({
  rule,
  info,
  factsEnabled,
  onChange,
  onConfigChange,
  onRemove,
}: {
  rule: DraftRule;
  info?: FirewallRuleTypeInfo;
  factsEnabled: boolean | undefined;
  onChange: (patch: Partial<DraftRule>) => void;
  onConfigChange: (key: string, value: unknown) => void;
  onRemove: () => void;
}) {
  const copy = ruleCopy(rule.ruleType);
  const implemented = info?.implemented ?? rule.implemented;
  const heuristic = info?.heuristic ?? false;
  const quarantines = info?.quarantines ?? false;
  const needsFacts = info?.requiresComponentFacts ?? false;
  const warning = rule.action === 'BLOCK' ? blockWarning(rule.ruleType, info) : null;

  return (
    <section className="bg-white rounded-lg border border-gray-200 p-5 space-y-4">
      <div className="flex items-start justify-between gap-4">
        <div>
          <h3 className="text-sm font-semibold text-gray-900 flex items-center gap-2 flex-wrap">
            {ruleLabel(rule.ruleType, info?.label)}
            <span className="font-mono font-normal text-[10px] text-gray-400">{rule.ruleType}</span>
            {heuristic && (
              <span
                title="Infers intent from names. Useful, and sometimes wrong."
                className="px-1.5 py-0.5 rounded bg-purple-50 text-purple-700 text-[10px] font-semibold uppercase tracking-wide"
              >
                heuristic
              </span>
            )}
            {quarantines && (
              <span
                title="A match holds the component instead of refusing it outright — it may be released automatically later."
                className="px-1.5 py-0.5 rounded bg-amber-50 text-amber-800 text-[10px] font-semibold uppercase tracking-wide"
              >
                quarantines
              </span>
            )}
            {!implemented && (
              <span
                title="This build has no implementation for the rule. It is stored and enforces nothing."
                className="px-1.5 py-0.5 rounded bg-gray-100 text-gray-600 text-[10px] font-semibold uppercase tracking-wide"
              >
                not enforced by this version
              </span>
            )}
          </h3>
          <p className="text-xs text-gray-500 mt-1 max-w-2xl">
            {info?.description ?? copy?.summary ?? ''}
          </p>
        </div>
        <button
          onClick={onRemove}
          aria-label={`Remove ${rule.ruleType}`}
          className="shrink-0 px-2 py-1 text-xs font-medium rounded border border-gray-300 bg-white hover:bg-red-50 hover:text-red-700 hover:border-red-300"
        >
          Remove
        </button>
      </div>

      <div className="flex flex-wrap items-center gap-5">
        <label className="inline-flex items-center gap-2 text-sm text-gray-800">
          <input
            type="checkbox"
            checked={rule.enabled}
            onChange={(e) => onChange({ enabled: e.target.checked })}
          />
          Enabled
        </label>

        <label className="inline-flex items-center gap-2 text-sm text-gray-800">
          Action
          <select
            aria-label={`Action for ${rule.ruleType}`}
            value={rule.action}
            disabled={!implemented}
            onChange={(e) => onChange({ action: e.target.value as FirewallAction })}
            className="px-2 py-1 border border-gray-300 rounded-md text-sm disabled:bg-gray-50 disabled:text-gray-400"
          >
            <option value="WARN">Warn — record it, serve it</option>
            <option value="BLOCK">Block — refuse it</option>
          </select>
        </label>
      </div>

      {copy?.caution && <p className="text-xs text-gray-600 italic">{copy.caution}</p>}

      {needsFacts && factsEnabled === false && (
        <p role="alert" className="text-xs text-amber-900 bg-amber-50 border border-amber-300 rounded px-3 py-2">
          This rule reads component facts, and facts are switched off on this instance. It will
          answer “cannot decide” for every component — which on a fail-closed repository means
          permanent quarantine.
        </p>
      )}

      {warning && (
        <p
          role="alert"
          className="text-xs text-red-900 bg-red-50 border border-red-300 rounded px-3 py-2 leading-relaxed"
        >
          {warning}
        </p>
      )}

      {!implemented && (
        <p className="text-xs text-gray-600 bg-gray-50 border border-gray-200 rounded px-3 py-2">
          This version of MegaRepo has no implementation for {rule.ruleType}. The rule is stored with
          the policy and evaluated by nothing — do not count it as protection.
        </p>
      )}

      <div>
        <h4 className="text-xs font-semibold text-gray-700 mb-2">Configuration</h4>
        <RuleConfigForm
          fields={info?.configSchema ?? []}
          config={(rule.config ?? {}) as Record<string, unknown>}
          disabled={false}
          onChange={onConfigChange}
        />
      </div>
    </section>
  );
}
