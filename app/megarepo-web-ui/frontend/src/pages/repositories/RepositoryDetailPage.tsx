import { useState, useEffect } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { api } from '../../api/client';
import LoadingSpinner from '../../components/LoadingSpinner';
import FormatBadge from '../../components/FormatBadge';
import TypeBadge from '../../components/TypeBadge';
import StatusDot from '../../components/StatusDot';
import ConfirmDialog from '../../components/ConfirmDialog';
import { useToast } from '../../components/Toast';
import { useAuth } from '../../auth/AuthContext';
import type { Repository } from '../../types/api';

function GroupMembersSection({ repo }: { repo: Repository }) {
  const { showToast } = useToast();

  const [members, setMembers] = useState<string[]>([]);
  const [availableRepos, setAvailableRepos] = useState<Repository[]>([]);
  const [editing, setEditing] = useState(false);
  const [editMembers, setEditMembers] = useState<string[]>([]);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    api.get<string[]>(`/repositories/${repo.name}/members`).then(setMembers).catch(() => {});
  }, [repo.name]);

  function startEdit() {
    setEditMembers([...members]);
    setEditing(true);
    // Fetch available repos for the picker
    api.get<Repository[]>('/repositories').then((repos) => {
      const eligible = repos.filter(
        (r) =>
          r.format.toLowerCase() === repo.format.toLowerCase() &&
          r.type.toLowerCase() !== 'group' &&
          r.name !== repo.name,
      );
      setAvailableRepos(eligible);
    }).catch(() => {});
  }

  async function saveMembers() {
    setSaving(true);
    try {
      const updated = await api.put<string[]>(`/repositories/${repo.name}/members`, editMembers);
      setMembers(updated);
      setEditing(false);
      showToast('success', 'Group members updated');
    } catch {
      showToast('error', 'Failed to update group members');
    } finally {
      setSaving(false);
    }
  }

  if (editing) {
    return (
      <div className="bg-white rounded-lg border border-gray-200 overflow-hidden">
        <div className="px-6 py-4 bg-gray-50 border-b border-gray-200 flex items-center justify-between">
          <h2 className="text-sm font-semibold text-gray-700">Group Members</h2>
          <div className="flex gap-2">
            <button
              className="inline-flex items-center px-3 py-1.5 bg-white border border-gray-200 text-gray-600 text-xs font-medium rounded-md hover:bg-gray-50 transition-colors"
              onClick={() => setEditing(false)}
            >
              Cancel
            </button>
            <button
              className="inline-flex items-center px-3 py-1.5 bg-blue-600 hover:bg-blue-700 text-white text-xs font-medium rounded-md transition-colors disabled:opacity-50"
              onClick={saveMembers}
              disabled={saving}
            >
              {saving ? 'Saving...' : 'Save Members'}
            </button>
          </div>
        </div>
        <div className="p-6">
          <div className="flex gap-4">
            {/* Available repos */}
            <div className="flex-1">
              <div className="text-xs font-medium text-gray-500 mb-1.5">Available</div>
              <div className="border border-gray-200 rounded-md max-h-64 overflow-y-auto">
                {availableRepos
                  .filter((r) => !editMembers.includes(r.name))
                  .map((r) => (
                    <button
                      key={r.name}
                      type="button"
                      className="w-full flex items-center justify-between px-3 py-2 text-sm text-left hover:bg-blue-50 border-b border-gray-100 last:border-b-0 transition-colors"
                      onClick={() => setEditMembers((prev) => [...prev, r.name])}
                    >
                      <span className="text-gray-700">{r.name}</span>
                      <TypeBadge type={r.type} />
                    </button>
                  ))}
                {availableRepos.filter((r) => !editMembers.includes(r.name)).length === 0 && (
                  <div className="px-3 py-2 text-xs text-gray-400 text-center">All added</div>
                )}
              </div>
            </div>
            {/* Selected members */}
            <div className="flex-1">
              <div className="text-xs font-medium text-gray-500 mb-1.5">
                Members ({editMembers.length})
              </div>
              <div className="border border-gray-200 rounded-md max-h-64 overflow-y-auto">
                {editMembers.map((memberName, idx) => (
                  <div
                    key={memberName}
                    className="flex items-center justify-between px-3 py-2 text-sm border-b border-gray-100 last:border-b-0 bg-blue-50/50"
                  >
                    <span className="flex items-center gap-2 text-gray-700">
                      <span className="text-xs text-gray-400 font-mono w-4">{idx + 1}</span>
                      {memberName}
                    </span>
                    <button
                      type="button"
                      className="text-gray-400 hover:text-red-500 transition-colors text-xs"
                      onClick={() => setEditMembers((prev) => prev.filter((n) => n !== memberName))}
                    >
                      Remove
                    </button>
                  </div>
                ))}
                {editMembers.length === 0 && (
                  <div className="px-3 py-2 text-xs text-gray-400 text-center">No members selected</div>
                )}
              </div>
            </div>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="bg-white rounded-lg border border-gray-200 overflow-hidden">
      <div className="px-6 py-4 bg-gray-50 border-b border-gray-200 flex items-center justify-between">
        <h2 className="text-sm font-semibold text-gray-700">Group Members</h2>
        <button
          className="inline-flex items-center px-3 py-1.5 bg-white border border-gray-200 text-gray-600 text-xs font-medium rounded-md hover:bg-gray-50 transition-colors"
          onClick={startEdit}
        >
          Edit Members
        </button>
      </div>
      <div className="p-6">
        {members.length === 0 ? (
          <p className="text-sm text-gray-500">No members configured. Click "Edit Members" to add repositories to this group.</p>
        ) : (
          <div className="space-y-1">
            {members.map((memberName, idx) => (
              <div
                key={memberName}
                className="flex items-center gap-3 px-3 py-2 bg-gray-50 rounded-md"
              >
                <span className="text-xs text-gray-400 font-mono w-4">{idx + 1}</span>
                <span className="text-sm text-gray-700 font-medium">{memberName}</span>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}

function formatBytes(bytes: number): string {
  if (bytes === 0) return '0 B';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  const i = Math.floor(Math.log(bytes) / Math.log(1024));
  const value = bytes / Math.pow(1024, i);
  return `${value < 10 ? value.toFixed(1) : Math.round(value)} ${units[i]}`;
}

export default function RepositoryDetailPage() {
  const { name } = useParams<{ name: string }>();
  const navigate = useNavigate();
  const { showToast } = useToast();
  /**
   * Only Delete is administrator-only here — `DELETE /api/v1/repositories/*`.
   * Reading the repository, editing it and rewriting a group's members are
   * `authenticated()` and stay where they are. As everywhere on this side of
   * the wire the hiding is cosmetic; the server enforces the rule itself.
   */
  const { isAdmin } = useAuth();

  const [repo, setRepo] = useState<Repository | null>(null);
  const [loading, setLoading] = useState(true);
  const [showDelete, setShowDelete] = useState(false);
  const [deleting, setDeleting] = useState(false);

  useEffect(() => {
    if (!name) return;
    api
      .get<Repository>(`/repositories/${name}`)
      .then(setRepo)
      .catch(() => {
        showToast('error', `Repository "${name}" not found`);
        navigate('/admin/repositories');
      })
      .finally(() => setLoading(false));
  }, [name]); // eslint-disable-line react-hooks/exhaustive-deps

  async function handleDelete() {
    if (!repo) return;
    setDeleting(true);
    try {
      await api.delete(`/repositories/${repo.name}`);
      showToast('success', `Repository "${repo.name}" deleted`);
      navigate('/admin/repositories');
    } catch {
      showToast('error', 'Failed to delete repository');
    } finally {
      setDeleting(false);
    }
  }

  if (loading) {
    return (
      <div className="flex justify-center items-center py-20">
        <LoadingSpinner message="Loading repository..." />
      </div>
    );
  }

  if (!repo) return null;

  return (
    <div className="p-6 sm:p-8 max-w-7xl">
      <div className="flex items-start justify-between mb-6 gap-4">
        <div>
          <h1 className="text-2xl font-semibold text-gray-950">{repo.name}</h1>
          <p className="text-sm text-gray-500 mt-1">Repository configuration and details</p>
        </div>
        <div className="flex gap-2 shrink-0">
          <button
            className="inline-flex items-center px-4 py-2 bg-white border border-gray-200 text-gray-700 text-sm font-medium rounded-md hover:bg-gray-50 transition-colors"
            onClick={() => navigate('/admin/repositories')}
          >
            Back
          </button>
          <button
            className="inline-flex items-center px-4 py-2 bg-blue-600 hover:bg-blue-700 text-white text-sm font-medium rounded-md transition-colors"
            onClick={() => navigate(`/admin/repositories/${repo.name}/edit`)}
          >
            Edit
          </button>
          {isAdmin && (
            <button
              className="inline-flex items-center px-4 py-2 bg-white border border-gray-200 hover:bg-red-50 text-red-600 text-sm font-medium rounded-md transition-colors"
              onClick={() => setShowDelete(true)}
            >
              Delete
            </button>
          )}
        </div>
      </div>

      <div className="space-y-5">
        <div className="bg-white rounded-lg border border-gray-200 overflow-hidden">
          <div className="px-6 py-4 bg-gray-50 border-b border-gray-200">
            <h2 className="text-sm font-semibold text-gray-700">General</h2>
          </div>
          <div className="p-6">
            <dl className="divide-y divide-gray-100">
              <div className="flex py-3.5">
                <dt className="w-40 shrink-0 text-sm font-medium text-gray-500">Name</dt>
                <dd className="flex-1 text-sm font-medium text-gray-900">{repo.name}</dd>
              </div>
              <div className="flex py-3.5">
                <dt className="w-40 shrink-0 text-sm font-medium text-gray-500">Format</dt>
                <dd className="flex-1"><FormatBadge format={repo.format} /></dd>
              </div>
              <div className="flex py-3.5">
                <dt className="w-40 shrink-0 text-sm font-medium text-gray-500">Type</dt>
                <dd className="flex-1"><TypeBadge type={repo.type} /></dd>
              </div>
              <div className="flex py-3.5">
                <dt className="w-40 shrink-0 text-sm font-medium text-gray-500">Status</dt>
                <dd className="flex-1">
                  <StatusDot
                    status={repo.online ? 'online' : 'offline'}
                    label={repo.online ? 'Online' : 'Offline'}
                  />
                </dd>
              </div>
              <div className="flex py-3.5">
                <dt className="w-40 shrink-0 text-sm font-medium text-gray-500">URL</dt>
                <dd className="flex-1">
                  <code className="text-xs font-mono bg-gray-100 text-gray-600 px-1.5 py-0.5 rounded">{repo.url}</code>
                </dd>
              </div>
            </dl>
          </div>
        </div>

        <div className="bg-white rounded-lg border border-gray-200 overflow-hidden">
          <div className="px-6 py-4 bg-gray-50 border-b border-gray-200">
            <h2 className="text-sm font-semibold text-gray-700">Storage Statistics</h2>
          </div>
          <div className="p-6">
            <div className="grid grid-cols-3 gap-6">
              <div className="text-center p-4 bg-gray-50 rounded-lg">
                <div className="text-2xl font-semibold text-gray-900 tabular-nums">
                  {repo.componentCount.toLocaleString()}
                </div>
                <div className="text-xs font-medium text-gray-500 mt-1">Components</div>
              </div>
              <div className="text-center p-4 bg-gray-50 rounded-lg">
                <div className="text-2xl font-semibold text-gray-900 tabular-nums">
                  {repo.assetCount.toLocaleString()}
                </div>
                <div className="text-xs font-medium text-gray-500 mt-1">Assets</div>
              </div>
              <div className="text-center p-4 bg-gray-50 rounded-lg">
                <div className="text-2xl font-semibold text-gray-900 tabular-nums">
                  {formatBytes(repo.totalSize)}
                </div>
                <div className="text-xs font-medium text-gray-500 mt-1">Total Size</div>
              </div>
            </div>
          </div>
        </div>

        {repo.type.toLowerCase() === 'group' && <GroupMembersSection repo={repo} />}

        {repo.attributes && Object.entries(repo.attributes).filter(([key]) => key !== 'group').length > 0 && (
          <div className="bg-white rounded-lg border border-gray-200 overflow-hidden">
            <div className="px-6 py-4 bg-gray-50 border-b border-gray-200">
              <h2 className="text-sm font-semibold text-gray-700">Configuration</h2>
            </div>
            <div className="p-6">
              <dl className="divide-y divide-gray-100">
                {Object.entries(repo.attributes).filter(([key]) => key !== 'group').map(([key, value]) => (
                  <div className="flex py-3.5" key={key}>
                    <dt className="w-40 shrink-0 text-sm font-medium text-gray-500">{key}</dt>
                    <dd className="flex-1 text-sm text-gray-700">
                      <code className="text-xs font-mono bg-gray-100 text-gray-600 px-1.5 py-0.5 rounded">
                        {typeof value === 'object' ? JSON.stringify(value) : String(value)}
                      </code>
                    </dd>
                  </div>
                ))}
              </dl>
            </div>
          </div>
        )}
      </div>

      <ConfirmDialog
        open={showDelete}
        title="Delete Repository"
        message={`Are you sure you want to delete "${repo.name}"? This will permanently remove the repository and all its contents.`}
        confirmLabel={deleting ? 'Deleting...' : 'Delete'}
        variant="danger"
        onConfirm={handleDelete}
        onCancel={() => setShowDelete(false)}
      />
    </div>
  );
}
