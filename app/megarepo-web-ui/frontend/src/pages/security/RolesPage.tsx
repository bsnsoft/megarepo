import { useState, useEffect, useMemo } from 'react';
import { api } from '../../api/client';
import DataTable, { type Column } from '../../components/DataTable';
import LoadingSpinner from '../../components/LoadingSpinner';
import Badge from '../../components/Badge';
import ConfirmDialog from '../../components/ConfirmDialog';
import { useToast } from '../../components/Toast';
import type { RoleXO } from '../../types/api';

type RoleRow = RoleXO & Record<string, unknown>;

interface CreateRoleForm {
  id: string;
  name: string;
  description: string;
  privileges: string[];
  roles: string[];
}

const emptyRoleForm: CreateRoleForm = {
  id: '',
  name: '',
  description: '',
  privileges: [''],
  roles: [],
};

interface EditRoleForm {
  name: string;
  description: string;
  privileges: string[];
  roles: string[];
}

export default function RolesPage() {
  const { showToast } = useToast();
  const [roles, setRoles] = useState<RoleRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [deleteTarget, setDeleteTarget] = useState<RoleRow | null>(null);
  const [deleting, setDeleting] = useState(false);
  const [createOpen, setCreateOpen] = useState(false);
  const [createForm, setCreateForm] = useState<CreateRoleForm>(emptyRoleForm);
  const [saving, setSaving] = useState(false);
  const [existingRoles, setExistingRoles] = useState<RoleRow[]>([]);
  const [editTarget, setEditTarget] = useState<RoleRow | null>(null);
  const [editForm, setEditForm] = useState<EditRoleForm | null>(null);
  const [editSaving, setEditSaving] = useState(false);

  function loadRoles() {
    setLoading(true);
    api
      .get<RoleXO[]>('/security/roles')
      .then((data) => setRoles(data as RoleRow[]))
      .catch(() => showToast('error', 'Failed to load roles'))
      .finally(() => setLoading(false));
  }

  useEffect(() => {
    loadRoles();
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  // Keep a copy for nested role selection in the dialog
  useEffect(() => {
    setExistingRoles(roles);
  }, [roles]);

  function openCreate() {
    setCreateForm(emptyRoleForm);
    setCreateOpen(true);
  }

  function addPrivilege() {
    setCreateForm({ ...createForm, privileges: [...createForm.privileges, ''] });
  }

  function removePrivilege(index: number) {
    const updated = createForm.privileges.filter((_, i) => i !== index);
    setCreateForm({ ...createForm, privileges: updated.length > 0 ? updated : [''] });
  }

  function updatePrivilege(index: number, value: string) {
    const updated = [...createForm.privileges];
    updated[index] = value;
    setCreateForm({ ...createForm, privileges: updated });
  }

  function toggleNestedRole(roleId: string) {
    setCreateForm((prev) => ({
      ...prev,
      roles: prev.roles.includes(roleId)
        ? prev.roles.filter((r) => r !== roleId)
        : [...prev.roles, roleId],
    }));
  }

  async function handleCreate() {
    if (!createForm.id.trim()) {
      showToast('error', 'Role ID is required');
      return;
    }
    if (!createForm.name.trim()) {
      showToast('error', 'Role name is required');
      return;
    }
    const cleanPrivileges = createForm.privileges.filter((p) => p.trim() !== '');

    setSaving(true);
    try {
      await api.post('/security/roles', {
        id: createForm.id.trim(),
        name: createForm.name.trim(),
        description: createForm.description.trim(),
        privileges: cleanPrivileges,
        roles: createForm.roles,
      });
      showToast('success', `Role "${createForm.name}" created`);
      setCreateOpen(false);
      loadRoles();
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : 'Failed to create role';
      showToast('error', message);
    } finally {
      setSaving(false);
    }
  }

  async function handleDelete() {
    if (!deleteTarget) return;
    setDeleting(true);
    try {
      await api.delete(`/security/roles/${deleteTarget.id}`);
      showToast('success', `Role "${deleteTarget.name}" deleted`);
      setDeleteTarget(null);
      loadRoles();
    } catch {
      showToast('error', `Failed to delete "${deleteTarget.name}"`);
    } finally {
      setDeleting(false);
    }
  }

  function openEditRole(role: RoleRow) {
    setEditTarget(role);
    setEditForm({
      name: role.name,
      description: role.description || '',
      privileges: role.privileges.length > 0 ? [...role.privileges] : [''],
      roles: [...role.roles],
    });
  }

  function addEditPrivilege() {
    setEditForm((prev) => prev ? { ...prev, privileges: [...prev.privileges, ''] } : prev);
  }

  function removeEditPrivilege(index: number) {
    setEditForm((prev) => {
      if (!prev) return prev;
      const updated = prev.privileges.filter((_, i) => i !== index);
      return { ...prev, privileges: updated.length > 0 ? updated : [''] };
    });
  }

  function updateEditPrivilege(index: number, value: string) {
    setEditForm((prev) => {
      if (!prev) return prev;
      const updated = [...prev.privileges];
      updated[index] = value;
      return { ...prev, privileges: updated };
    });
  }

  function toggleEditNestedRole(roleId: string) {
    setEditForm((prev) =>
      prev
        ? {
            ...prev,
            roles: prev.roles.includes(roleId)
              ? prev.roles.filter((r) => r !== roleId)
              : [...prev.roles, roleId],
          }
        : prev,
    );
  }

  async function handleEditRole() {
    if (!editTarget || !editForm) return;
    if (!editForm.name.trim()) {
      showToast('error', 'Role name is required');
      return;
    }
    const cleanPrivileges = editForm.privileges.filter((p) => p.trim() !== '');

    setEditSaving(true);
    try {
      await api.put(`/security/roles/${editTarget.id}`, {
        id: editTarget.id,
        name: editForm.name.trim(),
        description: editForm.description.trim(),
        privileges: cleanPrivileges,
        roles: editForm.roles,
      });
      showToast('success', `Role "${editForm.name}" updated`);
      setEditTarget(null);
      setEditForm(null);
      loadRoles();
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : 'Failed to update role';
      showToast('error', message);
    } finally {
      setEditSaving(false);
    }
  }

  const columns: Column<RoleRow>[] = useMemo(
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
        key: 'source',
        label: 'Source',
        sortable: true,
        width: '100px',
        render: (row) => <Badge variant="default">{row.source}</Badge>,
      },
      {
        key: 'privileges',
        label: 'Privileges',
        width: '120px',
        render: (row) => (
          <span className="inline-flex items-center px-2 py-0.5 text-xs font-medium rounded-sm bg-gray-100 text-gray-600">
            {row.privileges.length} privilege{row.privileges.length !== 1 ? 's' : ''}
          </span>
        ),
      },
      {
        key: '_actions',
        label: '',
        width: '130px',
        render: (row) =>
          !row.readOnly ? (
            <div className="flex gap-1.5">
              <button
                className="inline-flex items-center px-3 py-1 bg-white border border-gray-200 hover:bg-blue-50 text-blue-600 text-xs font-medium rounded-md transition-colors"
                title="Edit role"
                onClick={(e) => {
                  e.stopPropagation();
                  openEditRole(row);
                }}
              >
                Edit
              </button>
              <button
                className="inline-flex items-center px-3 py-1 bg-white border border-gray-200 hover:bg-red-50 text-red-600 text-xs font-medium rounded-md transition-colors"
                title="Delete role"
                onClick={(e) => {
                  e.stopPropagation();
                  setDeleteTarget(row);
                }}
              >
                Delete
              </button>
            </div>
          ) : null,
      },
    ],
    [], // eslint-disable-line react-hooks/exhaustive-deps
  );

  if (loading) {
    return (
      <div className="flex justify-center items-center py-20">
        <LoadingSpinner message="Loading roles..." />
      </div>
    );
  }

  return (
    <div className="p-6 sm:p-8 max-w-7xl">
      <div className="flex items-start justify-between mb-6 gap-4">
        <div>
          <h1 className="text-2xl font-semibold text-gray-950">Roles</h1>
          <p className="text-sm text-gray-500 mt-1">
            {roles.length} role{roles.length !== 1 ? 's' : ''}
          </p>
        </div>
        <button
          className="inline-flex items-center gap-1.5 px-4 py-2 bg-blue-600 hover:bg-blue-700 text-white text-sm font-medium rounded-md transition-colors"
          onClick={openCreate}
        >
          Create Role
        </button>
      </div>

      <div className="bg-white rounded-lg border border-gray-200 overflow-hidden">
        <DataTable
          columns={columns}
          data={roles}
          keyField="id"
          searchPlaceholder="Filter roles..."
          emptyMessage="No roles configured"
        />
      </div>

      <ConfirmDialog
        open={deleteTarget !== null}
        title="Delete Role"
        message={`Are you sure you want to delete role "${deleteTarget?.name}"? This action cannot be undone.`}
        confirmLabel={deleting ? 'Deleting...' : 'Delete'}
        variant="danger"
        onConfirm={handleDelete}
        onCancel={() => setDeleteTarget(null)}
      />

      {createOpen && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 backdrop-blur-sm"
          onClick={() => setCreateOpen(false)}
        >
          <div
            className="bg-white rounded-lg p-6 w-[540px] max-w-[90vw] max-h-[85vh] overflow-y-auto shadow-lg"
            onClick={(e) => e.stopPropagation()}
          >
            <h3 className="text-lg font-semibold text-gray-950 mb-1">Create Role</h3>
            <p className="text-sm text-gray-500 mb-5">Define a new role with privileges</p>

            <div className="space-y-4">
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1.5">Role ID</label>
                <input
                  type="text"
                  value={createForm.id}
                  onChange={(e) => setCreateForm({ ...createForm, id: e.target.value })}
                  placeholder="e.g. deploy-user"
                  className="w-full px-3 py-2 border border-gray-300 rounded-md text-sm text-gray-700 bg-white placeholder:text-gray-400 focus:outline-none focus:border-blue-600 focus:ring-1 focus:ring-blue-600 transition-colors"
                />
              </div>

              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1.5">Name</label>
                <input
                  type="text"
                  value={createForm.name}
                  onChange={(e) => setCreateForm({ ...createForm, name: e.target.value })}
                  placeholder="e.g. Deploy User"
                  className="w-full px-3 py-2 border border-gray-300 rounded-md text-sm text-gray-700 bg-white placeholder:text-gray-400 focus:outline-none focus:border-blue-600 focus:ring-1 focus:ring-blue-600 transition-colors"
                />
              </div>

              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1.5">Description</label>
                <input
                  type="text"
                  value={createForm.description}
                  onChange={(e) => setCreateForm({ ...createForm, description: e.target.value })}
                  placeholder="Optional description"
                  className="w-full px-3 py-2 border border-gray-300 rounded-md text-sm text-gray-700 bg-white placeholder:text-gray-400 focus:outline-none focus:border-blue-600 focus:ring-1 focus:ring-blue-600 transition-colors"
                />
              </div>

              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1.5">Privileges</label>
                <div className="space-y-2">
                  {createForm.privileges.map((privilege, idx) => (
                    <div key={idx} className="flex gap-2">
                      <input
                        type="text"
                        value={privilege}
                        onChange={(e) => updatePrivilege(idx, e.target.value)}
                        placeholder="e.g. admin-all, maven-deploy"
                        className="flex-1 px-3 py-2 border border-gray-300 rounded-md text-sm text-gray-700 bg-white placeholder:text-gray-400 focus:outline-none focus:border-blue-600 focus:ring-1 focus:ring-blue-600 transition-colors font-mono"
                      />
                      <button
                        type="button"
                        onClick={() => removePrivilege(idx)}
                        className="inline-flex items-center px-3 py-2 bg-white border border-gray-200 hover:bg-red-50 text-gray-500 hover:text-red-600 text-sm rounded-md transition-colors"
                        title="Remove privilege"
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
                  onClick={addPrivilege}
                  className="mt-2 inline-flex items-center gap-1 px-3 py-1.5 text-xs font-medium text-blue-600 hover:text-blue-700 bg-blue-50 hover:bg-blue-100 rounded-md transition-colors"
                >
                  + Add Privilege
                </button>
              </div>

              {existingRoles.length > 0 && (
                <div>
                  <label className="block text-sm font-medium text-gray-700 mb-1.5">
                    Contained Roles <span className="text-gray-400 font-normal">(optional)</span>
                  </label>
                  <div className="border border-gray-200 rounded-md max-h-32 overflow-y-auto">
                    {existingRoles.map((role) => (
                      <label
                        key={role.id}
                        className="flex items-center gap-2.5 px-3 py-2 hover:bg-gray-50 cursor-pointer border-b border-gray-100 last:border-b-0"
                      >
                        <input
                          type="checkbox"
                          checked={createForm.roles.includes(role.id)}
                          onChange={() => toggleNestedRole(role.id)}
                          className="rounded border-gray-300 text-blue-600 focus:ring-blue-600"
                        />
                        <span className="text-sm text-gray-700">{role.name}</span>
                      </label>
                    ))}
                  </div>
                </div>
              )}
            </div>

            <div className="flex gap-2 justify-end mt-6 pt-5 border-t border-gray-200">
              <button
                className="inline-flex items-center px-4 py-2 bg-white border border-gray-200 text-gray-700 text-sm font-medium rounded-md hover:bg-gray-50 transition-colors"
                onClick={() => setCreateOpen(false)}
              >
                Cancel
              </button>
              <button
                className="inline-flex items-center px-4 py-2 bg-blue-600 hover:bg-blue-700 text-white text-sm font-medium rounded-md transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                onClick={handleCreate}
                disabled={saving}
              >
                {saving ? 'Creating...' : 'Create'}
              </button>
            </div>
          </div>
        </div>
      )}

      {editTarget && editForm && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 backdrop-blur-sm"
          onClick={() => { setEditTarget(null); setEditForm(null); }}
        >
          <div
            className="bg-white rounded-lg p-6 w-[540px] max-w-[90vw] max-h-[85vh] overflow-y-auto shadow-lg"
            onClick={(e) => e.stopPropagation()}
          >
            <h3 className="text-lg font-semibold text-gray-950 mb-1">Edit Role</h3>
            <p className="text-sm text-gray-500 mb-5">
              Editing <span className="font-medium text-gray-700">{editTarget.id}</span>
            </p>

            <div className="space-y-4">
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1.5">Name</label>
                <input
                  type="text"
                  value={editForm.name}
                  onChange={(e) => setEditForm({ ...editForm, name: e.target.value })}
                  placeholder="e.g. Deploy User"
                  style={{ border: '1px solid #d1d5db', borderRadius: '0.375rem', padding: '0.5rem 0.75rem', width: '100%', fontSize: '0.875rem', color: '#374151', backgroundColor: '#fff', outline: 'none' }}
                />
              </div>

              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1.5">Description</label>
                <input
                  type="text"
                  value={editForm.description}
                  onChange={(e) => setEditForm({ ...editForm, description: e.target.value })}
                  placeholder="Optional description"
                  style={{ border: '1px solid #d1d5db', borderRadius: '0.375rem', padding: '0.5rem 0.75rem', width: '100%', fontSize: '0.875rem', color: '#374151', backgroundColor: '#fff', outline: 'none' }}
                />
              </div>

              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1.5">Privileges</label>
                <div className="space-y-2">
                  {editForm.privileges.map((privilege, idx) => (
                    <div key={idx} className="flex gap-2">
                      <input
                        type="text"
                        value={privilege}
                        onChange={(e) => updateEditPrivilege(idx, e.target.value)}
                        placeholder="e.g. admin-all, maven-deploy"
                        style={{ border: '1px solid #d1d5db', borderRadius: '0.375rem', padding: '0.5rem 0.75rem', flex: 1, fontSize: '0.875rem', color: '#374151', backgroundColor: '#fff', outline: 'none', fontFamily: 'monospace' }}
                      />
                      <button
                        type="button"
                        onClick={() => removeEditPrivilege(idx)}
                        className="inline-flex items-center px-3 py-2 bg-white border border-gray-200 hover:bg-red-50 text-gray-500 hover:text-red-600 text-sm rounded-md transition-colors"
                        title="Remove privilege"
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
                  onClick={addEditPrivilege}
                  className="mt-2 inline-flex items-center gap-1 px-3 py-1.5 text-xs font-medium text-blue-600 hover:text-blue-700 bg-blue-50 hover:bg-blue-100 rounded-md transition-colors"
                >
                  + Add Privilege
                </button>
              </div>

              {existingRoles.filter((r) => r.id !== editTarget.id).length > 0 && (
                <div>
                  <label className="block text-sm font-medium text-gray-700 mb-1.5">
                    Contained Roles <span className="text-gray-400 font-normal">(optional)</span>
                  </label>
                  <div className="border border-gray-200 rounded-md max-h-32 overflow-y-auto">
                    {existingRoles
                      .filter((r) => r.id !== editTarget.id)
                      .map((role) => (
                        <label
                          key={role.id}
                          className="flex items-center gap-2.5 px-3 py-2 hover:bg-gray-50 cursor-pointer border-b border-gray-100 last:border-b-0"
                        >
                          <input
                            type="checkbox"
                            checked={editForm.roles.includes(role.id)}
                            onChange={() => toggleEditNestedRole(role.id)}
                            className="rounded border-gray-300 text-blue-600 focus:ring-blue-600"
                          />
                          <span className="text-sm text-gray-700">{role.name}</span>
                        </label>
                      ))}
                  </div>
                </div>
              )}
            </div>

            <div className="flex gap-2 justify-end mt-6 pt-5 border-t border-gray-200">
              <button
                className="inline-flex items-center px-4 py-2 bg-white border border-gray-200 text-gray-700 text-sm font-medium rounded-md hover:bg-gray-50 transition-colors"
                onClick={() => { setEditTarget(null); setEditForm(null); }}
              >
                Cancel
              </button>
              <button
                className="inline-flex items-center px-4 py-2 bg-blue-600 hover:bg-blue-700 text-white text-sm font-medium rounded-md transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                onClick={handleEditRole}
                disabled={editSaving}
              >
                {editSaving ? 'Saving...' : 'Save Changes'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
