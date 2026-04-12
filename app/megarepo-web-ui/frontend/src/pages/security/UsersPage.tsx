import { useState, useEffect, useMemo } from 'react';
import { api, ApiError, NetworkError } from '../../api/client';
import DataTable, { type Column } from '../../components/DataTable';
import LoadingSpinner from '../../components/LoadingSpinner';
import ErrorState from '../../components/ErrorState';
import Badge from '../../components/Badge';
import ConfirmDialog from '../../components/ConfirmDialog';
import { useToast } from '../../components/Toast';
import type { ApiUser, RoleXO } from '../../types/api';

type UserRow = ApiUser & Record<string, unknown>;

interface CreateUserForm {
  userId: string;
  password: string;
  firstName: string;
  lastName: string;
  emailAddress: string;
  status: 'active' | 'disabled';
  roles: string[];
}

const emptyUserForm: CreateUserForm = {
  userId: '',
  password: '',
  firstName: '',
  lastName: '',
  emailAddress: '',
  status: 'active',
  roles: [],
};

function statusLabel(status: string): string {
  const map: Record<string, string> = {
    active: 'Active',
    disabled: 'Disabled',
    locked: 'Locked',
    CHANGE_PASSWORD: 'Password Change Required',
    changepassword: 'Password Change Required',
  };
  return map[status] || status;
}

function statusVariant(status: string): 'success' | 'warning' | 'danger' | 'default' {
  if (status === 'active') return 'success';
  if (status === 'CHANGE_PASSWORD' || status === 'changepassword') return 'warning';
  if (status === 'disabled' || status === 'locked') return 'danger';
  return 'default';
}

interface EditUserForm {
  firstName: string;
  lastName: string;
  emailAddress: string;
  status: 'active' | 'disabled';
  roles: string[];
}

export default function UsersPage() {
  const { showToast } = useToast();
  const [users, setUsers] = useState<UserRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<UserRow | null>(null);
  const [deleting, setDeleting] = useState(false);
  const [createOpen, setCreateOpen] = useState(false);
  const [createForm, setCreateForm] = useState<CreateUserForm>(emptyUserForm);
  const [saving, setSaving] = useState(false);
  const [availableRoles, setAvailableRoles] = useState<RoleXO[]>([]);
  const [editTarget, setEditTarget] = useState<UserRow | null>(null);
  const [editForm, setEditForm] = useState<EditUserForm | null>(null);
  const [editSaving, setEditSaving] = useState(false);

  function loadUsers() {
    setLoading(true);
    setLoadError(null);
    api
      .get<ApiUser[]>('/security/users')
      .then((data) => setUsers(data as UserRow[]))
      .catch((err) => {
        const msg = err instanceof NetworkError ? err.message
          : err instanceof ApiError ? err.message
          : 'Failed to load users';
        setLoadError(msg);
      })
      .finally(() => setLoading(false));
  }

  useEffect(() => {
    loadUsers();
    api.get<RoleXO[]>('/security/roles').then(setAvailableRoles).catch(() => {});
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  function openCreate() {
    setCreateForm(emptyUserForm);
    setCreateOpen(true);
  }

  function toggleRole(roleId: string) {
    setCreateForm((prev) => ({
      ...prev,
      roles: prev.roles.includes(roleId)
        ? prev.roles.filter((r) => r !== roleId)
        : [...prev.roles, roleId],
    }));
  }

  async function handleCreate() {
    if (!createForm.userId.trim()) {
      showToast('error', 'User ID is required');
      return;
    }
    if (!createForm.password || createForm.password.length < 8) {
      showToast('error', 'Password must be at least 8 characters');
      return;
    }
    if (!createForm.firstName.trim()) {
      showToast('error', 'First name is required');
      return;
    }
    if (!createForm.lastName.trim()) {
      showToast('error', 'Last name is required');
      return;
    }

    setSaving(true);
    try {
      await api.post('/security/users', {
        userId: createForm.userId.trim(),
        password: createForm.password,
        firstName: createForm.firstName.trim(),
        lastName: createForm.lastName.trim(),
        emailAddress: createForm.emailAddress.trim() || `${createForm.userId.trim()}@local`,
        status: createForm.status,
        roles: createForm.roles,
      });
      showToast('success', `User "${createForm.userId}" created`);
      setCreateOpen(false);
      loadUsers();
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : 'Failed to create user';
      showToast('error', message);
    } finally {
      setSaving(false);
    }
  }

  async function handleDelete() {
    if (!deleteTarget) return;
    setDeleting(true);
    try {
      await api.delete(`/security/users/${deleteTarget.userId}`);
      showToast('success', `User "${deleteTarget.userId}" deleted`);
      setDeleteTarget(null);
      loadUsers();
    } catch (err) {
      const msg = err instanceof Error ? err.message : `Failed to delete "${deleteTarget.userId}"`;
      showToast('error', msg);
    } finally {
      setDeleting(false);
    }
  }

  function openEdit(user: UserRow) {
    setEditTarget(user);
    setEditForm({
      firstName: user.firstName,
      lastName: user.lastName,
      emailAddress: user.emailAddress,
      status: user.status as 'active' | 'disabled',
      roles: [...user.roles],
    });
  }

  function toggleEditRole(roleId: string) {
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

  async function handleEdit() {
    if (!editTarget || !editForm) return;
    if (!editForm.firstName.trim()) {
      showToast('error', 'First name is required');
      return;
    }
    if (!editForm.lastName.trim()) {
      showToast('error', 'Last name is required');
      return;
    }

    setEditSaving(true);
    try {
      await api.put(`/security/users/${editTarget.userId}`, {
        userId: editTarget.userId,
        firstName: editForm.firstName.trim(),
        lastName: editForm.lastName.trim(),
        emailAddress: editForm.emailAddress.trim() || `${editTarget.userId}@local`,
        password: 'placeholder', // not used by update endpoint but required by DTO validation
        status: editForm.status,
        roles: editForm.roles,
      });
      showToast('success', `User "${editTarget.userId}" updated`);
      setEditTarget(null);
      setEditForm(null);
      loadUsers();
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : 'Failed to update user';
      showToast('error', message);
    } finally {
      setEditSaving(false);
    }
  }

  const columns: Column<UserRow>[] = useMemo(
    () => [
      {
        key: 'userId',
        label: 'User ID',
        sortable: true,
        render: (row) => <span className="font-medium text-gray-900">{row.userId}</span>,
      },
      {
        key: 'firstName',
        label: 'Name',
        sortable: true,
        render: (row) => (
          <span className="text-gray-700">{`${row.firstName} ${row.lastName}`.trim() || '-'}</span>
        ),
      },
      {
        key: 'emailAddress',
        label: 'Email',
        sortable: true,
      },
      {
        key: 'status',
        label: 'Status',
        sortable: true,
        width: '180px',
        render: (row) => (
          <Badge variant={statusVariant(row.status)}>
            {statusLabel(row.status)}
          </Badge>
        ),
      },
      {
        key: 'source',
        label: 'Source',
        sortable: true,
        width: '100px',
        render: (row) => <Badge variant="default">{row.source}</Badge>,
      },
      {
        key: 'roles',
        label: 'Roles',
        render: (row) => (
          <div className="flex flex-wrap gap-1">
            {row.roles.slice(0, 3).map((role) => (
              <span key={role} className="inline-flex items-center px-2 py-0.5 text-xs font-medium rounded-sm bg-gray-100 text-gray-700">
                {role}
              </span>
            ))}
            {row.roles.length > 3 && (
              <span className="inline-flex items-center px-2 py-0.5 text-xs font-medium rounded-sm bg-gray-100 text-gray-600">
                +{row.roles.length - 3}
              </span>
            )}
          </div>
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
                title="Edit user"
                onClick={(e) => {
                  e.stopPropagation();
                  openEdit(row);
                }}
              >
                Edit
              </button>
              <button
                className="inline-flex items-center px-3 py-1 bg-white border border-gray-200 hover:bg-red-50 text-red-600 text-xs font-medium rounded-md transition-colors"
                title="Delete user"
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
        <LoadingSpinner message="Loading users..." />
      </div>
    );
  }

  if (loadError) {
    return <ErrorState title="Failed to load users" message={loadError} onRetry={loadUsers} />;
  }

  return (
    <div className="p-6 sm:p-8 max-w-7xl">
      <div className="flex items-start justify-between mb-6 gap-4">
        <div>
          <h1 className="text-2xl font-semibold text-gray-950">Users</h1>
          <p className="text-sm text-gray-500 mt-1">
            {users.length} user{users.length !== 1 ? 's' : ''}
          </p>
        </div>
        <button
          className="inline-flex items-center gap-1.5 px-4 py-2 bg-blue-600 hover:bg-blue-700 text-white text-sm font-medium rounded-md transition-colors"
          onClick={openCreate}
        >
          Create User
        </button>
      </div>

      <div className="bg-white rounded-lg border border-gray-200 overflow-hidden">
        <DataTable
          columns={columns}
          data={users}
          keyField="userId"
          searchPlaceholder="Filter users..."
          emptyMessage="No users found"
        />
      </div>

      <ConfirmDialog
        open={deleteTarget !== null}
        title="Delete User"
        message={`Are you sure you want to delete user "${deleteTarget?.userId}"? This action cannot be undone.`}
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
            <h3 className="text-lg font-semibold text-gray-950 mb-1">Create User</h3>
            <p className="text-sm text-gray-500 mb-5">Add a new local user account</p>

            <div className="space-y-4">
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1.5">User ID</label>
                <input
                  type="text"
                  value={createForm.userId}
                  onChange={(e) => setCreateForm({ ...createForm, userId: e.target.value })}
                  placeholder="e.g. jdoe"
                  className="w-full px-3 py-2 border border-gray-300 rounded-md text-sm text-gray-700 bg-white placeholder:text-gray-400 focus:outline-none focus:border-blue-600 focus:ring-1 focus:ring-blue-600 transition-colors"
                />
              </div>

              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1.5">Password</label>
                <input
                  type="password"
                  value={createForm.password}
                  onChange={(e) => setCreateForm({ ...createForm, password: e.target.value })}
                  placeholder="Minimum 8 characters"
                  className="w-full px-3 py-2 border border-gray-300 rounded-md text-sm text-gray-700 bg-white placeholder:text-gray-400 focus:outline-none focus:border-blue-600 focus:ring-1 focus:ring-blue-600 transition-colors"
                />
              </div>

              <div className="grid grid-cols-2 gap-4">
                <div>
                  <label className="block text-sm font-medium text-gray-700 mb-1.5">First Name</label>
                  <input
                    type="text"
                    value={createForm.firstName}
                    onChange={(e) => setCreateForm({ ...createForm, firstName: e.target.value })}
                    placeholder="First name"
                    className="w-full px-3 py-2 border border-gray-300 rounded-md text-sm text-gray-700 bg-white placeholder:text-gray-400 focus:outline-none focus:border-blue-600 focus:ring-1 focus:ring-blue-600 transition-colors"
                  />
                </div>
                <div>
                  <label className="block text-sm font-medium text-gray-700 mb-1.5">Last Name</label>
                  <input
                    type="text"
                    value={createForm.lastName}
                    onChange={(e) => setCreateForm({ ...createForm, lastName: e.target.value })}
                    placeholder="Last name"
                    className="w-full px-3 py-2 border border-gray-300 rounded-md text-sm text-gray-700 bg-white placeholder:text-gray-400 focus:outline-none focus:border-blue-600 focus:ring-1 focus:ring-blue-600 transition-colors"
                  />
                </div>
              </div>

              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1.5">
                  Email <span className="text-gray-400 font-normal">(optional)</span>
                </label>
                <input
                  type="email"
                  value={createForm.emailAddress}
                  onChange={(e) => setCreateForm({ ...createForm, emailAddress: e.target.value })}
                  placeholder="user@example.com"
                  className="w-full px-3 py-2 border border-gray-300 rounded-md text-sm text-gray-700 bg-white placeholder:text-gray-400 focus:outline-none focus:border-blue-600 focus:ring-1 focus:ring-blue-600 transition-colors"
                />
              </div>

              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1.5">Status</label>
                <select
                  value={createForm.status}
                  onChange={(e) => setCreateForm({ ...createForm, status: e.target.value as 'active' | 'disabled' })}
                  className="w-full px-3 py-2 border border-gray-300 rounded-md text-sm text-gray-700 bg-white focus:outline-none focus:border-blue-600 focus:ring-1 focus:ring-blue-600 transition-colors"
                >
                  <option value="active">Active</option>
                  <option value="disabled">Disabled</option>
                </select>
              </div>

              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1.5">Roles</label>
                {availableRoles.length === 0 ? (
                  <p className="text-sm text-gray-400">No roles available</p>
                ) : (
                  <div className="border border-gray-200 rounded-md max-h-40 overflow-y-auto">
                    {availableRoles.map((role) => (
                      <label
                        key={role.id}
                        className="flex items-center gap-2.5 px-3 py-2 hover:bg-gray-50 cursor-pointer border-b border-gray-100 last:border-b-0"
                      >
                        <input
                          type="checkbox"
                          checked={createForm.roles.includes(role.id)}
                          onChange={() => toggleRole(role.id)}
                          className="rounded border-gray-300 text-blue-600 focus:ring-blue-600"
                        />
                        <span className="text-sm text-gray-700">{role.name}</span>
                        {role.description && (
                          <span className="text-xs text-gray-400 ml-auto truncate max-w-[200px]">
                            {role.description}
                          </span>
                        )}
                      </label>
                    ))}
                  </div>
                )}
              </div>
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
            <h3 className="text-lg font-semibold text-gray-950 mb-1">Edit User</h3>
            <p className="text-sm text-gray-500 mb-5">
              Editing <span className="font-medium text-gray-700">{editTarget.userId}</span>
            </p>

            <div className="space-y-4">
              <div className="grid grid-cols-2 gap-4">
                <div>
                  <label className="block text-sm font-medium text-gray-700 mb-1.5">First Name</label>
                  <input
                    type="text"
                    value={editForm.firstName}
                    onChange={(e) => setEditForm({ ...editForm, firstName: e.target.value })}
                    placeholder="First name"
                    style={{ border: '1px solid #d1d5db', borderRadius: '0.375rem', padding: '0.5rem 0.75rem', width: '100%', fontSize: '0.875rem', color: '#374151', backgroundColor: '#fff', outline: 'none' }}
                  />
                </div>
                <div>
                  <label className="block text-sm font-medium text-gray-700 mb-1.5">Last Name</label>
                  <input
                    type="text"
                    value={editForm.lastName}
                    onChange={(e) => setEditForm({ ...editForm, lastName: e.target.value })}
                    placeholder="Last name"
                    style={{ border: '1px solid #d1d5db', borderRadius: '0.375rem', padding: '0.5rem 0.75rem', width: '100%', fontSize: '0.875rem', color: '#374151', backgroundColor: '#fff', outline: 'none' }}
                  />
                </div>
              </div>

              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1.5">Email</label>
                <input
                  type="email"
                  value={editForm.emailAddress}
                  onChange={(e) => setEditForm({ ...editForm, emailAddress: e.target.value })}
                  placeholder="user@example.com"
                  style={{ border: '1px solid #d1d5db', borderRadius: '0.375rem', padding: '0.5rem 0.75rem', width: '100%', fontSize: '0.875rem', color: '#374151', backgroundColor: '#fff', outline: 'none' }}
                />
              </div>

              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1.5">Status</label>
                <select
                  value={editForm.status}
                  onChange={(e) => setEditForm({ ...editForm, status: e.target.value as 'active' | 'disabled' })}
                  style={{ border: '1px solid #d1d5db', borderRadius: '0.375rem', padding: '0.5rem 0.75rem', width: '100%', fontSize: '0.875rem', color: '#374151', backgroundColor: '#fff', outline: 'none' }}
                >
                  <option value="active">Active</option>
                  <option value="disabled">Disabled</option>
                </select>
              </div>

              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1.5">Roles</label>
                {availableRoles.length === 0 ? (
                  <p className="text-sm text-gray-400">No roles available</p>
                ) : (
                  <div className="border border-gray-200 rounded-md max-h-40 overflow-y-auto">
                    {availableRoles.map((role) => (
                      <label
                        key={role.id}
                        className="flex items-center gap-2.5 px-3 py-2 hover:bg-gray-50 cursor-pointer border-b border-gray-100 last:border-b-0"
                      >
                        <input
                          type="checkbox"
                          checked={editForm.roles.includes(role.id)}
                          onChange={() => toggleEditRole(role.id)}
                          className="rounded border-gray-300 text-blue-600 focus:ring-blue-600"
                        />
                        <span className="text-sm text-gray-700">{role.name}</span>
                        {role.description && (
                          <span className="text-xs text-gray-400 ml-auto truncate max-w-[200px]">
                            {role.description}
                          </span>
                        )}
                      </label>
                    ))}
                  </div>
                )}
              </div>
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
                onClick={handleEdit}
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
