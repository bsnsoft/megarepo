import { useState, useEffect, useMemo } from 'react';
import { api } from '../../api/client';
import DataTable, { type Column } from '../../components/DataTable';
import LoadingSpinner from '../../components/LoadingSpinner';
import Badge from '../../components/Badge';
import ConfirmDialog from '../../components/ConfirmDialog';
import EmptyState from '../../components/EmptyState';
import { useToast } from '../../components/Toast';
import type {
  CleanupPolicyXO,
  CleanupCriteria,
  CreateCleanupPolicyRequest,
  CleanupPreviewResponse,
  Repository,
} from '../../types/api';

type PolicyRow = CleanupPolicyXO & Record<string, unknown>;

const RELEASE_TYPE_OPTIONS = [
  { value: '', label: 'Any' },
  { value: 'RELEASES', label: 'Releases only' },
  { value: 'PRERELEASES', label: 'Pre-releases only' },
];

const FORMAT_OPTIONS = [
  { value: '', label: 'All formats' },
  { value: 'maven2', label: 'Maven' },
  { value: 'npm', label: 'npm' },
  { value: 'pypi', label: 'PyPI' },
  { value: 'raw', label: 'Raw' },
];

interface PolicyFormData {
  name: string;
  format: string;
  notes: string;
  lastBlobUpdated: string;
  lastDownloaded: string;
  retainNVersions: string;
  regex: string;
  releaseType: string;
}

const emptyForm: PolicyFormData = {
  name: '',
  format: '',
  notes: '',
  lastBlobUpdated: '',
  lastDownloaded: '',
  retainNVersions: '',
  regex: '',
  releaseType: '',
};

type PresetKey = 'old-artifacts' | 'keep-versions' | 'old-prereleases' | 'keep-snapshots' | 'keep-releases-1y' | 'custom';

interface Preset {
  key: PresetKey;
  label: string;
  description: string;
  summary: string;
  apply: (form: PolicyFormData) => PolicyFormData;
}

const PRESETS: Preset[] = [
  {
    key: 'old-artifacts',
    label: 'Delete old artifacts',
    description: 'Older than 30 days',
    summary: 'This policy will delete artifacts that have not been updated in 30 days.',
    apply: (f) => ({ ...emptyForm, name: f.name, format: f.format, notes: f.notes, lastBlobUpdated: '30' }),
  },
  {
    key: 'keep-versions',
    label: 'Keep last 5 versions',
    description: 'Per component',
    summary: 'This policy will keep only the 5 most recent versions of each component and delete older ones.',
    apply: (f) => ({ ...emptyForm, name: f.name, format: f.format, notes: f.notes, retainNVersions: '5' }),
  },
  {
    key: 'old-prereleases',
    label: 'Clean pre-releases',
    description: 'Older than 7 days',
    summary: 'This policy will delete pre-release artifacts (snapshots, betas, etc.) that have not been updated in 7 days.',
    apply: (f) => ({ ...emptyForm, name: f.name, format: f.format, notes: f.notes, releaseType: 'PRERELEASES', lastBlobUpdated: '7' }),
  },
  {
    key: 'keep-snapshots',
    label: 'Keep last 5 snapshots',
    description: 'Pre-releases only',
    summary: 'This policy will keep only the 5 most recent pre-release versions (snapshots, betas, etc.) of each component and delete older ones.',
    apply: (f) => ({ ...emptyForm, name: f.name, format: f.format, notes: f.notes, retainNVersions: '5', releaseType: 'PRERELEASES' }),
  },
  {
    key: 'keep-releases-1y',
    label: 'Keep releases for 1 year',
    description: 'Releases older than 365 days',
    summary: 'This policy will delete release artifacts that have not been updated in over 365 days.',
    apply: (f) => ({ ...emptyForm, name: f.name, format: f.format, notes: f.notes, lastBlobUpdated: '365', releaseType: 'RELEASES' }),
  },
  {
    key: 'custom',
    label: 'Custom',
    description: 'Configure manually',
    summary: '',
    apply: (f) => f,
  },
];

function detectPreset(form: PolicyFormData): PresetKey {
  if (
    form.lastBlobUpdated === '30' &&
    !form.lastDownloaded &&
    !form.retainNVersions &&
    !form.regex &&
    !form.releaseType
  ) return 'old-artifacts';
  if (
    form.retainNVersions === '5' &&
    !form.lastBlobUpdated &&
    !form.lastDownloaded &&
    !form.regex &&
    !form.releaseType
  ) return 'keep-versions';
  if (
    form.releaseType === 'PRERELEASES' &&
    form.lastBlobUpdated === '7' &&
    !form.lastDownloaded &&
    !form.retainNVersions &&
    !form.regex
  ) return 'old-prereleases';
  if (
    form.retainNVersions === '5' &&
    form.releaseType === 'PRERELEASES' &&
    !form.lastBlobUpdated &&
    !form.lastDownloaded &&
    !form.regex
  ) return 'keep-snapshots';
  if (
    form.lastBlobUpdated === '365' &&
    form.releaseType === 'RELEASES' &&
    !form.lastDownloaded &&
    !form.retainNVersions &&
    !form.regex
  ) return 'keep-releases-1y';
  return 'custom';
}

function formToCriteria(form: PolicyFormData): CleanupCriteria {
  const criteria: CleanupCriteria = {};
  if (form.lastBlobUpdated) criteria.lastBlobUpdated = parseInt(form.lastBlobUpdated, 10);
  if (form.lastDownloaded) criteria.lastDownloaded = parseInt(form.lastDownloaded, 10);
  if (form.retainNVersions) criteria.retainNVersions = parseInt(form.retainNVersions, 10);
  if (form.regex) criteria.regex = form.regex;
  if (form.releaseType) criteria.releaseType = form.releaseType as 'RELEASES' | 'PRERELEASES';
  return criteria;
}

function policyToForm(policy: CleanupPolicyXO): PolicyFormData {
  const c = policy.criteria || {};
  return {
    name: policy.name,
    format: policy.format || '',
    notes: policy.notes || '',
    lastBlobUpdated: c.lastBlobUpdated != null ? String(c.lastBlobUpdated) : '',
    lastDownloaded: c.lastDownloaded != null ? String(c.lastDownloaded) : '',
    retainNVersions: c.retainNVersions != null ? String(c.retainNVersions) : '',
    regex: c.regex || '',
    releaseType: c.releaseType || '',
  };
}

function criteriaDescription(criteria: CleanupCriteria): string {
  const parts: string[] = [];
  if (criteria.lastBlobUpdated != null) parts.push(`Updated > ${criteria.lastBlobUpdated}d ago`);
  if (criteria.lastDownloaded != null) parts.push(`Downloaded > ${criteria.lastDownloaded}d ago`);
  if (criteria.retainNVersions != null) parts.push(`Keep ${criteria.retainNVersions} versions`);
  if (criteria.regex) parts.push(`Regex: ${criteria.regex}`);
  if (criteria.releaseType) parts.push(criteria.releaseType === 'PRERELEASES' ? 'Pre-releases' : 'Releases');
  return parts.length > 0 ? parts.join(' + ') : 'No criteria';
}

function formatBytes(bytes: number): string {
  if (bytes === 0) return '0 B';
  const k = 1024;
  const sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i];
}

/* ── Consistent form classes ─────────────────────────────────────── */
const inputClass =
  'w-full px-3 py-2 border border-gray-300 rounded-md text-sm text-gray-700 bg-white placeholder:text-gray-400 focus:outline-none focus:border-blue-600 focus:ring-1 focus:ring-blue-600 transition-colors';
const selectClass =
  'w-full px-3 py-2 border border-gray-300 rounded-md text-sm text-gray-700 bg-white focus:outline-none focus:border-blue-600 focus:ring-1 focus:ring-blue-600 transition-colors';
const labelClass = 'block text-sm font-medium text-gray-700 mb-1.5';

/* ── Main Component ───────────────────────────────────────────────── */
export default function CachePage() {
  const { showToast } = useToast();

  // List state
  const [policies, setPolicies] = useState<PolicyRow[]>([]);
  const [loading, setLoading] = useState(true);

  // Form state
  const [formOpen, setFormOpen] = useState(false);
  const [editingPolicy, setEditingPolicy] = useState<string | null>(null);
  const [form, setForm] = useState<PolicyFormData>(emptyForm);
  const [saving, setSaving] = useState(false);
  const [selectedPreset, setSelectedPreset] = useState<PresetKey>('custom');
  const [showAdvanced, setShowAdvanced] = useState(false);

  // Delete state
  const [deleteTarget, setDeleteTarget] = useState<PolicyRow | null>(null);
  const [deleting, setDeleting] = useState(false);

  // Preview state
  const [previewTarget, setPreviewTarget] = useState<string | null>(null);
  const [previewRepo, setPreviewRepo] = useState('');
  const [previewLoading, setPreviewLoading] = useState(false);
  const [previewResult, setPreviewResult] = useState<CleanupPreviewResponse | null>(null);
  const [repositories, setRepositories] = useState<Repository[]>([]);

  /* ── Data loading ─────────────────────────────────────────────── */
  function loadPolicies() {
    setLoading(true);
    api
      .get<CleanupPolicyXO[]>('/cleanup-policies')
      .then((data) => setPolicies(data as PolicyRow[]))
      .catch(() => showToast('error', 'Failed to load cleanup policies'))
      .finally(() => setLoading(false));
  }

  function loadRepositories() {
    api
      .get<Repository[]>('/repositories')
      .then(setRepositories)
      .catch(() => {
        /* repositories may fail silently for preview */
      });
  }

  useEffect(() => {
    loadPolicies();
    loadRepositories();
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  /* ── Form handlers ────────────────────────────────────────────── */
  function openCreateForm() {
    setForm(emptyForm);
    setEditingPolicy(null);
    setSelectedPreset('custom');
    setShowAdvanced(false);
    setFormOpen(true);
  }

  function openEditForm(policy: PolicyRow) {
    const f = policyToForm(policy);
    setForm(f);
    setEditingPolicy(policy.name);
    const detected = detectPreset(f);
    setSelectedPreset(detected);
    setShowAdvanced(detected === 'custom');
    setFormOpen(true);
  }

  function closeForm() {
    setFormOpen(false);
    setEditingPolicy(null);
    setForm(emptyForm);
    setSelectedPreset('custom');
    setShowAdvanced(false);
  }

  function applyPreset(key: PresetKey) {
    setSelectedPreset(key);
    const preset = PRESETS.find((p) => p.key === key)!;
    setForm(preset.apply(form));
    setShowAdvanced(key === 'custom');
  }

  function updateField(field: keyof PolicyFormData, value: string) {
    setForm((prev) => ({ ...prev, [field]: value }));
  }

  async function handleSave() {
    if (!form.name.trim()) {
      showToast('error', 'Policy name is required');
      return;
    }

    const criteria = formToCriteria(form);
    if (Object.keys(criteria).length === 0) {
      showToast('error', 'At least one cleanup criterion is required');
      return;
    }

    const request: CreateCleanupPolicyRequest = {
      name: form.name.trim(),
      format: form.format || null,
      notes: form.notes || null,
      criteria,
    };

    setSaving(true);
    try {
      if (editingPolicy) {
        await api.put<CleanupPolicyXO>(`/cleanup-policies/${editingPolicy}`, request);
        showToast('success', `Policy "${form.name}" updated`);
      } else {
        await api.post<CleanupPolicyXO>('/cleanup-policies', request);
        showToast('success', `Policy "${form.name}" created`);
      }
      closeForm();
      loadPolicies();
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Unknown error';
      showToast('error', editingPolicy ? `Failed to update policy: ${msg}` : `Failed to create policy: ${msg}`);
    } finally {
      setSaving(false);
    }
  }

  /* ── Delete handler ───────────────────────────────────────────── */
  async function handleDelete() {
    if (!deleteTarget) return;
    setDeleting(true);
    try {
      await api.delete(`/cleanup-policies/${deleteTarget.name}`);
      showToast('success', `Policy "${deleteTarget.name}" deleted`);
      setDeleteTarget(null);
      loadPolicies();
    } catch {
      showToast('error', `Failed to delete "${deleteTarget.name}"`);
    } finally {
      setDeleting(false);
    }
  }

  /* ── Preview handler ──────────────────────────────────────────── */
  function openPreview(policyName: string) {
    setPreviewTarget(policyName);
    setPreviewRepo('');
    setPreviewResult(null);
  }

  async function runPreview() {
    if (!previewTarget || !previewRepo) return;
    setPreviewLoading(true);
    try {
      const result = await api.post<CleanupPreviewResponse>(
        `/cleanup-policies/${previewTarget}/preview?repository=${encodeURIComponent(previewRepo)}`,
      );
      setPreviewResult(result);
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Unknown error';
      showToast('error', `Preview failed: ${msg}`);
    } finally {
      setPreviewLoading(false);
    }
  }

  /* ── Table columns ────────────────────────────────────────────── */
  const columns: Column<PolicyRow>[] = useMemo(
    () => [
      {
        key: 'name',
        label: 'Name',
        sortable: true,
        render: (row) => <span className="font-medium text-gray-900">{row.name}</span>,
      },
      {
        key: 'format',
        label: 'Format',
        sortable: true,
        width: '110px',
        render: (row) => (
          <Badge variant={row.format ? 'info' : 'default'}>{row.format || 'All'}</Badge>
        ),
      },
      {
        key: 'criteria',
        label: 'Criteria',
        render: (row) => (
          <span className="text-gray-600 text-xs">{criteriaDescription(row.criteria)}</span>
        ),
      },
      {
        key: 'notes',
        label: 'Notes',
        render: (row) => (
          <span className="text-gray-500 text-xs truncate max-w-[200px] block">
            {row.notes || '-'}
          </span>
        ),
      },
      {
        key: '_actions',
        label: '',
        width: '220px',
        render: (row) => (
          <div className="flex gap-2">
            <button
              className="inline-flex items-center px-3 py-1 bg-white border border-gray-200 hover:bg-gray-50 text-gray-700 text-xs font-medium rounded-md transition-colors"
              onClick={(e) => {
                e.stopPropagation();
                openEditForm(row);
              }}
            >
              Edit
            </button>
            <button
              className="inline-flex items-center px-3 py-1 bg-white border border-gray-200 hover:bg-gray-50 text-gray-700 text-xs font-medium rounded-md transition-colors"
              onClick={(e) => {
                e.stopPropagation();
                openPreview(row.name);
              }}
            >
              Preview
            </button>
            <button
              className="inline-flex items-center px-3 py-1 bg-white border border-gray-200 hover:bg-red-50 text-red-600 text-xs font-medium rounded-md transition-colors"
              onClick={(e) => {
                e.stopPropagation();
                setDeleteTarget(row);
              }}
            >
              Delete
            </button>
          </div>
        ),
      },
    ],
    [], // eslint-disable-line react-hooks/exhaustive-deps
  );

  /* ── Loading state ────────────────────────────────────────────── */
  if (loading) {
    return (
      <div className="flex justify-center items-center py-20">
        <LoadingSpinner message="Loading cleanup policies..." />
      </div>
    );
  }

  /* ── Render ───────────────────────────────────────────────────── */
  return (
    <div className="p-6 sm:p-8 max-w-7xl">
      {/* Header */}
      <div className="flex items-start justify-between mb-6 gap-4">
        <div>
          <h1 className="text-2xl font-semibold text-gray-950">Cleanup Policies</h1>
          <p className="text-sm text-gray-500 mt-1">
            {policies.length} polic{policies.length !== 1 ? 'ies' : 'y'} configured
          </p>
        </div>
        <button
          className="inline-flex items-center gap-1.5 px-4 py-2 bg-blue-600 hover:bg-blue-700 text-white text-sm font-medium rounded-md transition-colors"
          onClick={openCreateForm}
        >
          Create Policy
        </button>
      </div>

      {/* Policy table or empty state */}
      <div className="bg-white rounded-lg border border-gray-200 overflow-hidden">
        {policies.length === 0 ? (
          <EmptyState
            icon={
              <svg className="mx-auto h-12 w-12 text-gray-300" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
                <path strokeLinecap="round" strokeLinejoin="round" d="M14.74 9l-.346 9m-4.788 0L9.26 9m9.968-3.21c.342.052.682.107 1.022.166m-1.022-.165L18.16 19.673a2.25 2.25 0 01-2.244 2.077H8.084a2.25 2.25 0 01-2.244-2.077L4.772 5.79m14.456 0a48.108 48.108 0 00-3.478-.397m-12 .562c.34-.059.68-.114 1.022-.165m0 0a48.11 48.11 0 013.478-.397m7.5 0v-.916c0-1.18-.91-2.164-2.09-2.201a51.964 51.964 0 00-3.32 0c-1.18.037-2.09 1.022-2.09 2.201v.916m7.5 0a48.667 48.667 0 00-7.5 0" />
              </svg>
            }
            title="No Cleanup Policies"
            description="Cleanup policies automatically remove old or unused artifacts from your repositories. Create a policy to get started."
            action={
              <button
                className="inline-flex items-center gap-1.5 px-4 py-2 bg-blue-600 hover:bg-blue-700 text-white text-sm font-medium rounded-md transition-colors"
                onClick={openCreateForm}
              >
                Create Policy
              </button>
            }
          />
        ) : (
          <DataTable
            columns={columns}
            data={policies}
            keyField="name"
            searchPlaceholder="Filter policies..."
            emptyMessage="No cleanup policies found"
          />
        )}
      </div>

      {/* ── Create / Edit Form (modal) ────────────────────────── */}
      {formOpen && (
        <div
          className="fixed inset-0 bg-black/50 flex items-center justify-center z-50"
          onClick={closeForm}
        >
          <div
            className="bg-white rounded-lg w-[560px] max-w-[90vw] max-h-[90vh] shadow-lg flex flex-col"
            onClick={(e) => e.stopPropagation()}
          >
            {/* Form header */}
            <div className="px-6 py-5 border-b border-gray-200">
              <h3 className="text-lg font-semibold text-gray-950">
                {editingPolicy ? `Edit Policy: ${editingPolicy}` : 'Create Cleanup Policy'}
              </h3>
              <p className="text-sm text-gray-500 mt-1">
                Define criteria for automatically removing artifacts
              </p>
            </div>

            {/* Form body */}
            <div className="px-6 py-5 flex-1 overflow-y-auto space-y-5">
              {/* Name */}
              <div>
                <label className={labelClass}>Policy Name</label>
                <input
                  type="text"
                  className={inputClass}
                  placeholder="e.g. delete-old-snapshots"
                  value={form.name}
                  onChange={(e) => updateField('name', e.target.value)}
                  disabled={!!editingPolicy}
                />
                {editingPolicy && (
                  <p className="text-xs text-gray-400 mt-1">Name cannot be changed after creation</p>
                )}
              </div>

              {/* Format */}
              <div>
                <label className={labelClass}>Format</label>
                <select
                  className={selectClass}
                  value={form.format}
                  onChange={(e) => updateField('format', e.target.value)}
                >
                  {FORMAT_OPTIONS.map((opt) => (
                    <option key={opt.value} value={opt.value}>
                      {opt.label}
                    </option>
                  ))}
                </select>
                <p className="text-xs text-gray-400 mt-1">
                  Restrict this policy to a specific format, or apply to all
                </p>
              </div>

              {/* Notes */}
              <div>
                <label className={labelClass}>Notes</label>
                <textarea
                  className={`${inputClass} resize-none`}
                  rows={2}
                  placeholder="Optional description..."
                  value={form.notes}
                  onChange={(e) => updateField('notes', e.target.value)}
                />
              </div>

              {/* Preset buttons */}
              <div className="border-t border-gray-200 pt-5">
                <h4 className="text-sm font-semibold text-gray-900 mb-3">What should this policy do?</h4>
                <div className="grid grid-cols-3 gap-2">
                  {PRESETS.map((preset) => (
                    <button
                      key={preset.key}
                      type="button"
                      className={`text-left px-3 py-2.5 rounded-md border text-sm transition-colors ${
                        selectedPreset === preset.key
                          ? 'border-blue-600 bg-blue-50 text-blue-700 ring-1 ring-blue-600'
                          : 'border-gray-200 bg-white text-gray-700 hover:bg-gray-50'
                      }`}
                      onClick={() => applyPreset(preset.key)}
                    >
                      <span className="font-medium block">{preset.label}</span>
                      <span className={`text-xs ${selectedPreset === preset.key ? 'text-blue-500' : 'text-gray-400'}`}>
                        {preset.description}
                      </span>
                    </button>
                  ))}
                </div>

                {/* Human-readable summary for presets */}
                {selectedPreset !== 'custom' && PRESETS.find((p) => p.key === selectedPreset)?.summary && (
                  <div className="mt-3 px-3 py-2.5 bg-blue-50 border border-blue-100 rounded-md">
                    <p className="text-sm text-blue-700">
                      {PRESETS.find((p) => p.key === selectedPreset)!.summary}
                    </p>
                  </div>
                )}

                {/* Advanced toggle for preset mode */}
                {selectedPreset !== 'custom' && (
                  <button
                    type="button"
                    className="mt-3 flex items-center gap-1.5 text-xs text-gray-500 hover:text-gray-700 transition-colors"
                    onClick={() => setShowAdvanced(!showAdvanced)}
                  >
                    <svg
                      className={`w-3.5 h-3.5 transition-transform ${showAdvanced ? 'rotate-90' : ''}`}
                      fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}
                    >
                      <path strokeLinecap="round" strokeLinejoin="round" d="M9 5l7 7-7 7" />
                    </svg>
                    {showAdvanced ? 'Hide advanced options' : 'Show advanced options'}
                  </button>
                )}

                {/* Criteria fields -- shown always for custom, toggled for presets */}
                {(selectedPreset === 'custom' || showAdvanced) && (
                  <div className="mt-4 space-y-4">
                    <p className="text-xs text-gray-400">
                      All criteria are combined with AND logic. An artifact must match every specified criterion to be deleted.
                    </p>

                    <div className="grid grid-cols-2 gap-4">
                      {/* Last updated (days) */}
                      <div>
                        <label className={labelClass}>Last updated (days)</label>
                        <input
                          type="number"
                          min="1"
                          className={inputClass}
                          placeholder="e.g. 30"
                          value={form.lastBlobUpdated}
                          onChange={(e) => { updateField('lastBlobUpdated', e.target.value); setSelectedPreset('custom'); }}
                        />
                        <p className="text-xs text-gray-400 mt-1">Delete artifacts not updated in this many days</p>
                      </div>

                      {/* Last downloaded (days) */}
                      <div>
                        <label className={labelClass}>Last downloaded (days)</label>
                        <input
                          type="number"
                          min="1"
                          className={inputClass}
                          placeholder="e.g. 60"
                          value={form.lastDownloaded}
                          onChange={(e) => { updateField('lastDownloaded', e.target.value); setSelectedPreset('custom'); }}
                        />
                        <p className="text-xs text-gray-400 mt-1">Delete artifacts not downloaded in this many days</p>
                      </div>

                      {/* Keep N versions */}
                      <div>
                        <label className={labelClass}>Keep N versions</label>
                        <input
                          type="number"
                          min="1"
                          className={inputClass}
                          placeholder="e.g. 5"
                          value={form.retainNVersions}
                          onChange={(e) => { updateField('retainNVersions', e.target.value); setSelectedPreset('custom'); }}
                        />
                        <p className="text-xs text-gray-400 mt-1">Keep only the newest N versions per component</p>
                      </div>

                      {/* Release type */}
                      <div>
                        <label className={labelClass}>Release type</label>
                        <select
                          className={selectClass}
                          value={form.releaseType}
                          onChange={(e) => { updateField('releaseType', e.target.value); setSelectedPreset('custom'); }}
                        >
                          {RELEASE_TYPE_OPTIONS.map((opt) => (
                            <option key={opt.value} value={opt.value}>
                              {opt.label}
                            </option>
                          ))}
                        </select>
                        <p className="text-xs text-gray-400 mt-1">Target releases, pre-releases, or any</p>
                      </div>
                    </div>

                    {/* Path pattern (regex) */}
                    <div>
                      <label className={labelClass}>Path pattern (regex)</label>
                      <input
                        type="text"
                        className={`${inputClass} font-mono text-xs`}
                        placeholder="e.g. .*-SNAPSHOT/.* or com/example/.*"
                        value={form.regex}
                        onChange={(e) => { updateField('regex', e.target.value); setSelectedPreset('custom'); }}
                      />
                      <p className="text-xs text-gray-400 mt-1">
                        Java regex matched against the asset path. Example: <code className="bg-gray-100 px-1 rounded">.*-SNAPSHOT/.*</code>
                      </p>
                    </div>
                  </div>
                )}
              </div>
            </div>

            {/* Form footer */}
            <div className="px-6 py-4 border-t border-gray-200 flex gap-2 justify-end">
              <button
                className="inline-flex items-center px-4 py-2 bg-white border border-gray-200 text-gray-700 text-sm font-medium rounded-md hover:bg-gray-50 transition-colors"
                onClick={closeForm}
              >
                Cancel
              </button>
              <button
                className="inline-flex items-center px-4 py-2 bg-blue-600 hover:bg-blue-700 text-white text-sm font-medium rounded-md transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                onClick={handleSave}
                disabled={saving}
              >
                {saving ? 'Saving...' : editingPolicy ? 'Update Policy' : 'Create Policy'}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* ── Preview Dialog ─────────────────────────────────────── */}
      {previewTarget !== null && (
        <div
          className="fixed inset-0 bg-black/50 flex items-center justify-center z-50"
          onClick={() => setPreviewTarget(null)}
        >
          <div
            className="bg-white rounded-lg w-[600px] max-w-[90vw] max-h-[80vh] shadow-lg flex flex-col"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="px-6 py-5 border-b border-gray-200">
              <h3 className="text-lg font-semibold text-gray-950">
                Dry Run Preview: {previewTarget}
              </h3>
              <p className="text-sm text-gray-500 mt-1">
                See which assets would be affected without actually deleting anything
              </p>
            </div>

            <div className="px-6 py-5 flex-1 overflow-y-auto space-y-4">
              {/* Repository selector */}
              <div>
                <label className={labelClass}>Repository</label>
                <select
                  className={selectClass}
                  value={previewRepo}
                  onChange={(e) => {
                    setPreviewRepo(e.target.value);
                    setPreviewResult(null);
                  }}
                >
                  <option value="">Select a repository...</option>
                  {repositories.map((r) => (
                    <option key={r.name} value={r.name}>
                      {r.name} ({r.format} / {r.type})
                    </option>
                  ))}
                </select>
              </div>

              <button
                className="inline-flex items-center gap-1.5 px-4 py-2 bg-blue-600 hover:bg-blue-700 text-white text-sm font-medium rounded-md transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                onClick={runPreview}
                disabled={!previewRepo || previewLoading}
              >
                {previewLoading ? 'Running...' : 'Run Preview'}
              </button>

              {/* Preview results */}
              {previewResult && (
                <div className="border border-gray-200 rounded-lg overflow-hidden">
                  <div className="bg-gray-50 px-4 py-3 border-b border-gray-200 flex items-center gap-4">
                    <div>
                      <span className="text-xs font-semibold text-gray-500">Assets to delete</span>
                      <p className="text-lg font-semibold text-gray-950">{previewResult.count}</p>
                    </div>
                    <div className="w-px h-10 bg-gray-200" />
                    <div>
                      <span className="text-xs font-semibold text-gray-500">Space reclaimed</span>
                      <p className="text-lg font-semibold text-gray-950">{formatBytes(previewResult.totalSize)}</p>
                    </div>
                  </div>

                  {previewResult.assetsToDelete.length > 0 ? (
                    <div className="max-h-[300px] overflow-y-auto">
                      <table className="w-full">
                        <thead>
                          <tr>
                            <th className="text-left px-4 py-2 text-xs font-semibold text-gray-500 uppercase tracking-wider bg-gray-50 border-b border-gray-200">
                              Path
                            </th>
                            <th className="text-right px-4 py-2 text-xs font-semibold text-gray-500 uppercase tracking-wider bg-gray-50 border-b border-gray-200 w-[100px]">
                              Size
                            </th>
                          </tr>
                        </thead>
                        <tbody>
                          {previewResult.assetsToDelete.map((asset) => (
                            <tr key={asset.id} className="border-b border-gray-100 last:border-b-0">
                              <td className="px-4 py-2 text-xs text-gray-700 font-mono truncate max-w-[380px]">
                                {asset.path}
                              </td>
                              <td className="px-4 py-2 text-xs text-gray-500 text-right">
                                {formatBytes(asset.fileSize)}
                              </td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                  ) : (
                    <div className="px-4 py-8 text-center text-sm text-gray-400">
                      No assets match this policy in the selected repository
                    </div>
                  )}
                </div>
              )}
            </div>

            <div className="px-6 py-4 border-t border-gray-200 flex justify-end">
              <button
                className="inline-flex items-center px-4 py-2 bg-white border border-gray-200 text-gray-700 text-sm font-medium rounded-md hover:bg-gray-50 transition-colors"
                onClick={() => setPreviewTarget(null)}
              >
                Close
              </button>
            </div>
          </div>
        </div>
      )}

      {/* ── Delete confirmation ────────────────────────────────── */}
      <ConfirmDialog
        open={deleteTarget !== null}
        title="Delete Cleanup Policy"
        message={`Are you sure you want to delete cleanup policy "${deleteTarget?.name}"? This action cannot be undone.`}
        confirmLabel={deleting ? 'Deleting...' : 'Delete'}
        variant="danger"
        onConfirm={handleDelete}
        onCancel={() => setDeleteTarget(null)}
      />
    </div>
  );
}
