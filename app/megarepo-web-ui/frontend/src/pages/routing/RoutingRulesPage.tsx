import { useState, useEffect, useMemo } from 'react';
import { api } from '../../api/client';
import DataTable, { type Column } from '../../components/DataTable';
import LoadingSpinner from '../../components/LoadingSpinner';
import Badge from '../../components/Badge';
import ConfirmDialog from '../../components/ConfirmDialog';
import { useToast } from '../../components/Toast';
import type { RoutingRuleXO } from '../../types/api';

type RoutingRuleRow = RoutingRuleXO & Record<string, unknown>;

interface FormData {
  name: string;
  description: string;
  mode: 'ALLOW' | 'BLOCK';
  matchers: string[];
}

const emptyForm: FormData = { name: '', description: '', mode: 'BLOCK', matchers: [''] };

export default function RoutingRulesPage() {
  const { showToast } = useToast();
  const [rules, setRules] = useState<RoutingRuleRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [deleteTarget, setDeleteTarget] = useState<RoutingRuleRow | null>(null);
  const [deleting, setDeleting] = useState(false);
  const [dialogOpen, setDialogOpen] = useState(false);
  const [editingName, setEditingName] = useState<string | null>(null);
  const [form, setForm] = useState<FormData>(emptyForm);
  const [saving, setSaving] = useState(false);

  function loadRules() {
    setLoading(true);
    api
      .get<RoutingRuleXO[]>('/routing-rules')
      .then((data) => setRules(data as RoutingRuleRow[]))
      .catch(() => showToast('error', 'Failed to load routing rules'))
      .finally(() => setLoading(false));
  }

  useEffect(() => {
    loadRules();
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  function openCreate() {
    setEditingName(null);
    setForm(emptyForm);
    setDialogOpen(true);
  }

  function openEdit(row: RoutingRuleRow) {
    setEditingName(row.name);
    setForm({
      name: row.name,
      description: row.description || '',
      mode: row.mode as 'ALLOW' | 'BLOCK',
      matchers: row.matchers.length > 0 ? [...row.matchers] : [''],
    });
    setDialogOpen(true);
  }

  async function handleSave() {
    const cleanMatchers = form.matchers.filter((m) => m.trim() !== '');
    if (!form.name.trim()) {
      showToast('error', 'Name is required');
      return;
    }
    if (cleanMatchers.length === 0) {
      showToast('error', 'At least one matcher pattern is required');
      return;
    }

    setSaving(true);
    try {
      const body = {
        name: form.name.trim(),
        description: form.description.trim(),
        mode: form.mode,
        matchers: cleanMatchers,
      };

      if (editingName) {
        await api.put(`/routing-rules/${editingName}`, body);
        showToast('success', `Routing rule "${form.name}" updated`);
      } else {
        await api.post('/routing-rules', body);
        showToast('success', `Routing rule "${form.name}" created`);
      }
      setDialogOpen(false);
      loadRules();
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : 'Operation failed';
      showToast('error', message);
    } finally {
      setSaving(false);
    }
  }

  async function handleDelete() {
    if (!deleteTarget) return;
    setDeleting(true);
    try {
      await api.delete(`/routing-rules/${deleteTarget.name}`);
      showToast('success', `Routing rule "${deleteTarget.name}" deleted`);
      setDeleteTarget(null);
      loadRules();
    } catch {
      showToast('error', `Failed to delete "${deleteTarget.name}"`);
    } finally {
      setDeleting(false);
    }
  }

  function addMatcher() {
    setForm({ ...form, matchers: [...form.matchers, ''] });
  }

  function removeMatcher(index: number) {
    const updated = form.matchers.filter((_, i) => i !== index);
    setForm({ ...form, matchers: updated.length > 0 ? updated : [''] });
  }

  function updateMatcher(index: number, value: string) {
    const updated = [...form.matchers];
    updated[index] = value;
    setForm({ ...form, matchers: updated });
  }

  const columns: Column<RoutingRuleRow>[] = useMemo(
    () => [
      {
        key: 'name',
        label: 'Name',
        sortable: true,
        render: (row) => <span className="font-medium text-gray-900">{row.name}</span>,
      },
      {
        key: 'description',
        label: 'Description',
        sortable: true,
        render: (row) => <span className="text-gray-700">{row.description || '-'}</span>,
      },
      {
        key: 'mode',
        label: 'Mode',
        sortable: true,
        width: '110px',
        render: (row) => (
          <Badge variant={row.mode === 'BLOCK' ? 'danger' : 'success'}>{row.mode}</Badge>
        ),
      },
      {
        key: 'matchers',
        label: 'Matchers',
        width: '120px',
        render: (row) => (
          <Badge variant="default">
            {row.matchers.length} pattern{row.matchers.length !== 1 ? 's' : ''}
          </Badge>
        ),
      },
      {
        key: '_actions',
        label: '',
        width: '130px',
        render: (row) => (
          <div className="flex gap-2">
            <button
              className="inline-flex items-center px-3 py-1 bg-white border border-gray-200 hover:bg-gray-50 text-gray-700 text-xs font-medium rounded-md transition-colors"
              title="Edit"
              onClick={(e) => {
                e.stopPropagation();
                openEdit(row);
              }}
            >
              Edit
            </button>
            <button
              className="inline-flex items-center px-3 py-1 bg-white border border-gray-200 hover:bg-red-50 text-red-600 text-xs font-medium rounded-md transition-colors"
              title="Delete"
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

  if (loading) {
    return (
      <div className="flex justify-center items-center py-20">
        <LoadingSpinner message="Loading routing rules..." />
      </div>
    );
  }

  return (
    <div className="p-6 sm:p-8 max-w-7xl">
      <div className="flex items-start justify-between mb-6 gap-4">
        <div>
          <h1 className="text-2xl font-semibold text-gray-950">Routing Rules</h1>
          <p className="text-sm text-gray-500 mt-1">
            {rules.length} routing rule{rules.length !== 1 ? 's' : ''} configured
          </p>
        </div>
        <button
          className="inline-flex items-center gap-1.5 px-4 py-2 bg-blue-600 hover:bg-blue-700 text-white text-sm font-medium rounded-md transition-colors"
          onClick={openCreate}
        >
          Create Rule
        </button>
      </div>

      <div className="bg-white rounded-lg border border-gray-200 overflow-hidden">
        <DataTable
          columns={columns}
          data={rules}
          keyField="name"
          searchPlaceholder="Filter routing rules..."
          emptyMessage="No routing rules configured"
        />
      </div>

      <ConfirmDialog
        open={deleteTarget !== null}
        title="Delete Routing Rule"
        message={`Are you sure you want to delete routing rule "${deleteTarget?.name}"? This action cannot be undone.`}
        confirmLabel={deleting ? 'Deleting...' : 'Delete'}
        variant="danger"
        onConfirm={handleDelete}
        onCancel={() => setDeleteTarget(null)}
      />

      {dialogOpen && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 backdrop-blur-sm"
          onClick={() => setDialogOpen(false)}
        >
          <div
            className="bg-white rounded-lg p-6 w-[540px] max-w-[90vw] max-h-[85vh] overflow-y-auto shadow-lg"
            onClick={(e) => e.stopPropagation()}
          >
            <h3 className="text-lg font-semibold text-gray-950 mb-1">
              {editingName ? 'Edit Routing Rule' : 'Create Routing Rule'}
            </h3>
            <p className="text-sm text-gray-500 mb-5">
              Configure path matching patterns for proxy repositories
            </p>

            <div className="space-y-4">
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1.5">Name</label>
                <input
                  type="text"
                  value={form.name}
                  onChange={(e) => setForm({ ...form, name: e.target.value })}
                  disabled={editingName !== null}
                  placeholder="e.g. block-snapshots"
                  className="w-full px-3 py-2 border border-gray-300 rounded-md text-sm text-gray-700 bg-white placeholder:text-gray-400 focus:outline-none focus:border-blue-600 focus:ring-1 focus:ring-blue-600 transition-colors disabled:opacity-60 disabled:cursor-not-allowed"
                />
              </div>

              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1.5">Description</label>
                <input
                  type="text"
                  value={form.description}
                  onChange={(e) => setForm({ ...form, description: e.target.value })}
                  placeholder="Optional description"
                  className="w-full px-3 py-2 border border-gray-300 rounded-md text-sm text-gray-700 bg-white placeholder:text-gray-400 focus:outline-none focus:border-blue-600 focus:ring-1 focus:ring-blue-600 transition-colors"
                />
              </div>

              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1.5">Mode</label>
                <select
                  value={form.mode}
                  onChange={(e) => setForm({ ...form, mode: e.target.value as 'ALLOW' | 'BLOCK' })}
                  className="w-full px-3 py-2 border border-gray-300 rounded-md text-sm text-gray-700 bg-white focus:outline-none focus:border-blue-600 focus:ring-1 focus:ring-blue-600 transition-colors"
                >
                  <option value="BLOCK">BLOCK - Deny matching requests</option>
                  <option value="ALLOW">ALLOW - Only permit matching requests</option>
                </select>
              </div>

              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1.5">
                  Matchers (path patterns)
                </label>
                <div className="space-y-2">
                  {form.matchers.map((matcher, idx) => (
                    <div key={idx} className="flex gap-2">
                      <input
                        type="text"
                        value={matcher}
                        onChange={(e) => updateMatcher(idx, e.target.value)}
                        placeholder="e.g. .*-SNAPSHOT/.*"
                        className="flex-1 px-3 py-2 border border-gray-300 rounded-md text-sm text-gray-700 bg-white placeholder:text-gray-400 focus:outline-none focus:border-blue-600 focus:ring-1 focus:ring-blue-600 transition-colors font-mono"
                      />
                      <button
                        type="button"
                        onClick={() => removeMatcher(idx)}
                        className="inline-flex items-center px-3 py-2 bg-white border border-gray-200 hover:bg-red-50 text-gray-500 hover:text-red-600 text-sm rounded-md transition-colors"
                        title="Remove matcher"
                      >
                        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                          <path strokeLinecap="round" strokeLinejoin="round" d="M6 18L18 6M6 6l12 12" />
                        </svg>
                      </button>
                    </div>
                  ))}
                </div>
                <button
                  type="button"
                  onClick={addMatcher}
                  className="mt-2 inline-flex items-center gap-1 px-3 py-1.5 text-xs font-medium text-blue-600 hover:text-blue-700 bg-blue-50 hover:bg-blue-100 rounded-md transition-colors"
                >
                  + Add Pattern
                </button>
              </div>
            </div>

            <div className="flex gap-2 justify-end mt-6 pt-5 border-t border-gray-200">
              <button
                className="inline-flex items-center px-4 py-2 bg-white border border-gray-200 text-gray-700 text-sm font-medium rounded-md hover:bg-gray-50 transition-colors"
                onClick={() => setDialogOpen(false)}
              >
                Cancel
              </button>
              <button
                className="inline-flex items-center px-4 py-2 bg-blue-600 hover:bg-blue-700 text-white text-sm font-medium rounded-md transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                onClick={handleSave}
                disabled={saving}
              >
                {saving ? 'Saving...' : editingName ? 'Update' : 'Create'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
