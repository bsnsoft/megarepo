import { useState, useEffect, useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import { api, ApiError, NetworkError } from '../../api/client';
import DataTable, { type Column } from '../../components/DataTable';
import FormatBadge from '../../components/FormatBadge';
import TypeBadge from '../../components/TypeBadge';
import StatusDot from '../../components/StatusDot';
import LoadingSpinner from '../../components/LoadingSpinner';
import ErrorState from '../../components/ErrorState';
import ConfirmDialog from '../../components/ConfirmDialog';
import NexusMigrationDialog from '../../components/NexusMigrationDialog';
import ImportPresetDialog from '../../components/ImportPresetDialog';
import { useToast } from '../../components/Toast';
import type { Repository } from '../../types/api';

type RepoRow = Repository & Record<string, unknown>;

function CopyButton({ text }: { text: string }) {
  const { showToast } = useToast();

  function handleCopy(e: React.MouseEvent) {
    e.stopPropagation();
    // Repository URLs are stored as server-relative paths ("/repository/<name>").
    // Copy the absolute URL (scheme + host) so it is usable as-is in client tools.
    const absolute = text.startsWith('/') ? `${window.location.origin}${text}` : text;
    navigator.clipboard.writeText(absolute).then(
      () => showToast('success', 'URL copied to clipboard'),
      () => showToast('error', 'Failed to copy'),
    );
  }

  return (
    <button
      className="inline-flex items-center p-1 border-none bg-transparent text-gray-400 cursor-pointer rounded transition-colors hover:text-blue-600 hover:bg-gray-100"
      onClick={handleCopy}
      title="Copy URL"
    >
      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
        <rect x="9" y="9" width="13" height="13" rx="2" ry="2" />
        <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1" />
      </svg>
    </button>
  );
}

function formatBytes(bytes: number): string {
  if (bytes === 0) return '0 B';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  const i = Math.floor(Math.log(bytes) / Math.log(1024));
  const value = bytes / Math.pow(1024, i);
  return `${value < 10 ? value.toFixed(1) : Math.round(value)} ${units[i]}`;
}

export default function RepositoryListPage() {
  const navigate = useNavigate();
  const { showToast } = useToast();
  const [repos, setRepos] = useState<RepoRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<RepoRow | null>(null);
  const [deleting, setDeleting] = useState(false);
  const [migrateOpen, setMigrateOpen] = useState(false);
  const [importOpen, setImportOpen] = useState(false);

  function loadRepos() {
    setLoading(true);
    setLoadError(null);
    api
      .get<Repository[]>('/repositories')
      .then((data) => setRepos(data as RepoRow[]))
      .catch((err) => {
        const msg = err instanceof NetworkError ? err.message
          : err instanceof ApiError ? err.message
          : 'Failed to load repositories';
        setLoadError(msg);
      })
      .finally(() => setLoading(false));
  }

  useEffect(() => {
    loadRepos();
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  function handleExport() {
    const token = localStorage.getItem('token');
    const headers: Record<string, string> = {};
    if (token) {
      headers['Authorization'] = `Bearer ${token}`;
    }
    fetch('/api/v1/admin/export-repos', { headers })
      .then((res) => {
        if (!res.ok) throw new Error('Export failed');
        return res.text();
      })
      .then((yaml) => {
        const blob = new Blob([yaml], { type: 'text/yaml' });
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = 'megarepo-repositories.yml';
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        URL.revokeObjectURL(url);
        showToast('success', 'Repositories exported');
      })
      .catch(() => showToast('error', 'Failed to export repositories'));
  }

  async function handleDelete() {
    if (!deleteTarget) return;
    setDeleting(true);
    try {
      await api.delete(`/repositories/${deleteTarget.name}`);
      showToast('success', `Repository "${deleteTarget.name}" deleted`);
      setDeleteTarget(null);
      loadRepos();
    } catch (err) {
      const msg = err instanceof Error ? err.message : `Failed to delete "${deleteTarget.name}"`;
      showToast('error', msg);
    } finally {
      setDeleting(false);
    }
  }

  const columns: Column<RepoRow>[] = useMemo(
    () => [
      {
        key: 'name',
        label: 'Name',
        sortable: true,
        render: (row) => <span className="font-medium text-gray-900">{row.name}</span>,
      },
      {
        key: 'type',
        label: 'Type',
        sortable: true,
        width: '100px',
        render: (row) => <TypeBadge type={row.type} />,
      },
      {
        key: 'format',
        label: 'Format',
        sortable: true,
        width: '100px',
        render: (row) => <FormatBadge format={row.format} />,
      },
      {
        key: 'online',
        label: 'Status',
        sortable: true,
        width: '110px',
        render: (row) => (
          <StatusDot status={row.online ? 'online' : 'offline'} label={row.online ? 'Online' : 'Offline'} />
        ),
      },
      {
        key: 'componentCount',
        label: 'Components',
        sortable: true,
        width: '110px',
        render: (row) => (
          <span className="text-sm tabular-nums text-gray-600">{row.componentCount.toLocaleString()}</span>
        ),
      },
      {
        key: 'totalSize',
        label: 'Size',
        sortable: true,
        width: '100px',
        render: (row) => (
          <span className="text-sm tabular-nums text-gray-600">{formatBytes(row.totalSize)}</span>
        ),
      },
      {
        key: 'url',
        label: 'URL',
        render: (row) => (
          <span className="inline-flex items-center gap-1.5">
            <code className="font-mono text-xs text-gray-500 bg-gray-100 px-1.5 py-0.5 rounded">{row.url}</code>
            <CopyButton text={row.url} />
          </span>
        ),
      },
      {
        key: '_actions',
        label: '',
        width: '60px',
        render: (row) => (
          <button
            className="inline-flex items-center px-3 py-1 bg-white border border-gray-200 hover:bg-red-50 text-red-600 text-xs font-medium rounded-md transition-colors"
            title="Delete repository"
            onClick={(e) => {
              e.stopPropagation();
              setDeleteTarget(row);
            }}
          >
            Delete
          </button>
        ),
      },
    ],
    [],
  );

  if (loading) {
    return (
      <div className="flex justify-center items-center py-20">
        <LoadingSpinner message="Loading repositories..." />
      </div>
    );
  }

  if (loadError) {
    return <ErrorState title="Failed to load repositories" message={loadError} onRetry={loadRepos} />;
  }

  return (
    <div className="p-6 sm:p-8 max-w-7xl">
      <div className="flex items-start justify-between mb-6 gap-4">
        <div>
          <h1 className="text-2xl font-semibold text-gray-950">Repositories</h1>
          <p className="text-sm text-gray-500 mt-1">
            {repos.length} repositor{repos.length === 1 ? 'y' : 'ies'} configured
          </p>
        </div>
      </div>

      <div className="bg-white rounded-lg border border-gray-200 overflow-hidden">
        <DataTable
          columns={columns}
          data={repos}
          keyField="name"
          onRowClick={(row) => navigate(`/admin/repositories/${row.name}`)}
          searchPlaceholder="Filter repositories..."
          emptyMessage="No repositories configured yet"
          actions={
            <div className="flex gap-2">
              <button
                className="inline-flex items-center gap-1.5 px-4 py-2 bg-white border border-gray-200 hover:bg-gray-50 text-gray-700 text-sm font-medium rounded-md transition-colors"
                onClick={() => setMigrateOpen(true)}
              >
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                  <path d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-4l-4 4m0 0l-4-4m4 4V4" />
                </svg>
                Bootstrap from Nexus
              </button>
              <button
                className="inline-flex items-center gap-1.5 px-4 py-2 bg-white border border-gray-200 hover:bg-gray-50 text-gray-700 text-sm font-medium rounded-md transition-colors"
                onClick={() => setImportOpen(true)}
              >
                Import Preset
              </button>
              <button
                className="inline-flex items-center gap-1.5 px-4 py-2 bg-white border border-gray-200 hover:bg-gray-50 text-gray-700 text-sm font-medium rounded-md transition-colors"
                onClick={handleExport}
              >
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                  <path d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m4-8l-4-4m0 0l-4 4m4-4v12" />
                </svg>
                Export YAML
              </button>
              <button
                className="inline-flex items-center gap-1.5 px-4 py-2 bg-blue-600 hover:bg-blue-700 text-white text-sm font-medium rounded-md transition-colors"
                onClick={() => navigate('/admin/repositories/create')}
              >
                Create Repository
              </button>
            </div>
          }
        />
      </div>

      <ConfirmDialog
        open={deleteTarget !== null}
        title="Delete Repository"
        message={`Are you sure you want to delete "${deleteTarget?.name}"? This will permanently remove the repository and all its contents. This action cannot be undone.`}
        confirmLabel={deleting ? 'Deleting...' : 'Delete'}
        variant="danger"
        onConfirm={handleDelete}
        onCancel={() => setDeleteTarget(null)}
      />

      <NexusMigrationDialog
        open={migrateOpen}
        onClose={() => setMigrateOpen(false)}
        onComplete={loadRepos}
      />
      <ImportPresetDialog
        open={importOpen}
        onClose={() => setImportOpen(false)}
        onImported={loadRepos}
      />
    </div>
  );
}
