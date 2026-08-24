import { useState, useEffect } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { api } from '../../api/client';
import LoadingSpinner from '../../components/LoadingSpinner';
import FormatBadge from '../../components/FormatBadge';
import ConfirmDialog from '../../components/ConfirmDialog';
import { useToast } from '../../components/Toast';
import { useAuth } from '../../auth/AuthContext';
import type { Component, Asset } from '../../types/api';

function formatBytes(bytes: number): string {
  if (bytes === 0) return '0 B';
  const k = 1024;
  const sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return `${parseFloat((bytes / Math.pow(k, i)).toFixed(1))} ${sizes[i]}`;
}

function formatDate(iso: string | null): string {
  if (!iso) return '-';
  const d = new Date(iso);
  return d.toLocaleDateString('en-US', {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });
}

function fileName(path: string): string {
  const parts = path.split('/');
  return parts[parts.length - 1] || path;
}

function ChecksumRow({ label, value }: { label: string; value: string | null }) {
  if (!value) return null;
  return (
    <div className="flex items-start gap-3 py-1.5">
      <span className="text-xs font-semibold text-gray-500 uppercase tracking-wider w-16 shrink-0 pt-0.5">
        {label}
      </span>
      <code className="text-xs text-gray-600 font-mono break-all bg-gray-50 px-2 py-1 rounded select-all">
        {value}
      </code>
    </div>
  );
}

function AssetCard({
  asset,
  onDelete,
  canDelete,
}: {
  asset: Asset;
  onDelete: (asset: Asset) => void;
  /**
   * Whether to offer the delete control. `DELETE /api/v1/assets/*` is
   * administrator-only; download and the metadata below are not, which is the
   * whole reason this card stays on a page any logged-in account can open.
   */
  canDelete: boolean;
}) {
  const [expanded, setExpanded] = useState(false);
  const hasChecksums = asset.checksumMd5 || asset.checksumSha1 || asset.checksumSha256 || asset.checksumSha512;

  return (
    <div className="border border-gray-200 rounded-lg bg-white overflow-hidden">
      <div className="flex items-center justify-between px-5 py-4 gap-4">
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2 mb-1">
            <svg className="text-gray-400 shrink-0" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <path d="M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8z" />
              <polyline points="14 2 14 8 20 8" />
            </svg>
            <span className="font-medium text-sm text-gray-900 truncate">{fileName(asset.path)}</span>
          </div>
          <div className="text-xs text-gray-400 font-mono truncate ml-6">{asset.path}</div>
        </div>

        <div className="flex items-center gap-4 shrink-0">
          <div className="text-right">
            <div className="text-xs font-medium text-gray-700 tabular-nums">{formatBytes(asset.fileSize)}</div>
            <div className="text-[11px] text-gray-400">{asset.contentType || 'unknown'}</div>
          </div>
          <a
            href={asset.downloadUrl}
            className="inline-flex items-center gap-1.5 px-3 py-1.5 bg-blue-600 hover:bg-blue-700 text-white text-xs font-medium rounded-md transition-colors"
            title="Download"
          >
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <path d="M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4" />
              <polyline points="7 10 12 15 17 10" />
              <line x1="12" y1="15" x2="12" y2="3" />
            </svg>
            Download
          </a>
          {hasChecksums && (
            <button
              onClick={() => setExpanded(!expanded)}
              className="inline-flex items-center gap-1 px-2.5 py-1.5 text-xs font-medium text-gray-600 bg-gray-100 hover:bg-gray-200 rounded-md transition-colors"
              title={expanded ? 'Hide checksums' : 'Show checksums'}
            >
              <svg
                width="14"
                height="14"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                strokeWidth="2"
                className={`transition-transform ${expanded ? 'rotate-180' : ''}`}
              >
                <polyline points="6 9 12 15 18 9" />
              </svg>
              Checksums
            </button>
          )}
          {canDelete && (
            <button
              onClick={() => onDelete(asset)}
              className="inline-flex items-center gap-1.5 px-2.5 py-1.5 text-xs font-medium text-red-600 bg-white border border-gray-200 hover:bg-red-50 rounded-md transition-colors"
              title="Delete asset"
            >
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                <polyline points="3 6 5 6 21 6" />
                <path d="M19 6v14a2 2 0 01-2 2H7a2 2 0 01-2-2V6m3 0V4a2 2 0 012-2h4a2 2 0 012 2v2" />
              </svg>
              Delete
            </button>
          )}
        </div>
      </div>

      {/* Metadata row */}
      <div className="flex items-center gap-6 px-5 py-2.5 bg-gray-50 border-t border-gray-100 text-xs text-gray-500">
        <span>
          Modified: <strong className="text-gray-600">{formatDate(asset.lastModified)}</strong>
        </span>
        {asset.lastDownloaded && (
          <span>
            Last downloaded: <strong className="text-gray-600">{formatDate(asset.lastDownloaded)}</strong>
          </span>
        )}
      </div>

      {/* Checksum panel */}
      {expanded && hasChecksums && (
        <div className="px-5 py-3 border-t border-gray-200 bg-gray-50/40">
          <ChecksumRow label="MD5" value={asset.checksumMd5} />
          <ChecksumRow label="SHA-1" value={asset.checksumSha1} />
          <ChecksumRow label="SHA-256" value={asset.checksumSha256} />
          <ChecksumRow label="SHA-512" value={asset.checksumSha512} />
        </div>
      )}
    </div>
  );
}

export default function ComponentDetailPage() {
  const { repositoryName, componentId } = useParams<{ repositoryName: string; componentId: string }>();
  const navigate = useNavigate();
  const { showToast } = useToast();
  /**
   * This page is the ordinary user's: browsing a component and downloading its
   * assets is what a read-only account is for, so the route stays open and the
   * two destructive controls are hidden instead. `DELETE /api/v1/components/*`
   * and `DELETE /api/v1/assets/*` are administrator-only in `SecurityConfig`,
   * and that is the check that counts — this only keeps a non-administrator
   * from being offered a button whose one outcome is a permission error.
   */
  const { isAdmin } = useAuth();

  const [component, setComponent] = useState<Component | null>(null);
  const [loading, setLoading] = useState(true);
  const [deleteComponentOpen, setDeleteComponentOpen] = useState(false);
  const [deleteAssetTarget, setDeleteAssetTarget] = useState<Asset | null>(null);
  const [working, setWorking] = useState(false);

  function reload() {
    if (!componentId) return;
    api
      .get<Component>(`/components/${componentId}`)
      .then(setComponent)
      .catch(() => {
        showToast('error', 'Failed to load component');
        navigate(repositoryName ? `/browse/${encodeURIComponent(repositoryName)}` : '/browse');
      });
  }

  useEffect(() => {
    if (!componentId) return;
    api
      .get<Component>(`/components/${componentId}`)
      .then(setComponent)
      .catch(() => {
        showToast('error', 'Failed to load component');
        navigate(repositoryName ? `/browse/${encodeURIComponent(repositoryName)}` : '/browse');
      })
      .finally(() => setLoading(false));
  }, [componentId]); // eslint-disable-line react-hooks/exhaustive-deps

  function handleDeleteComponent() {
    if (!componentId) return;
    setWorking(true);
    api
      .delete(`/components/${componentId}`)
      .then(() => {
        showToast('success', 'Component deleted');
        navigate(`/browse/${encodeURIComponent(repositoryName || component?.repository || '')}`);
      })
      .catch(() => showToast('error', 'Failed to delete component'))
      .finally(() => {
        setWorking(false);
        setDeleteComponentOpen(false);
      });
  }

  function handleDeleteAsset() {
    if (!deleteAssetTarget) return;
    setWorking(true);
    api
      .delete(`/assets/${deleteAssetTarget.id}`)
      .then(() => {
        showToast('success', 'Asset deleted');
        reload();
      })
      .catch(() => showToast('error', 'Failed to delete asset'))
      .finally(() => {
        setWorking(false);
        setDeleteAssetTarget(null);
      });
  }

  if (loading) {
    return (
      <div className="flex justify-center items-center py-20">
        <LoadingSpinner message="Loading component..." />
      </div>
    );
  }

  if (!component) return null;

  return (
    <div className="p-6 sm:p-8 max-w-7xl">
      {/* Header */}
      <div className="mb-6">
        <div className="flex items-center gap-3 mb-3">
          <button
            onClick={() => navigate(`/browse/${encodeURIComponent(repositoryName || component.repository)}`)}
            className="text-gray-400 hover:text-blue-600 transition-colors"
            title="Back to repository"
          >
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <path d="M19 12H5" />
              <path d="M12 19l-7-7 7-7" />
            </svg>
          </button>
          <nav className="flex items-center gap-1.5 text-sm text-gray-500">
            <button
              onClick={() => navigate('/browse')}
              className="hover:text-blue-600 transition-colors"
            >
              Browse
            </button>
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <polyline points="9 18 15 12 9 6" />
            </svg>
            <button
              onClick={() => navigate(`/browse/${encodeURIComponent(repositoryName || component.repository)}`)}
              className="hover:text-blue-600 transition-colors"
            >
              {repositoryName || component.repository}
            </button>
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <polyline points="9 18 15 12 9 6" />
            </svg>
            <span className="text-gray-900 font-medium">{component.name}</span>
          </nav>
        </div>
        <div className="flex items-start justify-between gap-4 ml-8">
          <h1 className="text-2xl font-semibold text-gray-950">
            {component.group ? `${component.group} / ` : ''}
            {component.name}
          </h1>
          {isAdmin && (
            <button
              onClick={() => setDeleteComponentOpen(true)}
              className="inline-flex items-center gap-1.5 px-3 py-1.5 bg-white border border-gray-200 hover:bg-red-50 text-red-600 text-sm font-medium rounded-md transition-colors shrink-0"
              title="Delete component"
            >
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                <polyline points="3 6 5 6 21 6" />
                <path d="M19 6v14a2 2 0 01-2 2H7a2 2 0 01-2-2V6m3 0V4a2 2 0 012-2h4a2 2 0 012 2v2" />
              </svg>
              Delete component
            </button>
          )}
        </div>
      </div>

      {/* Component info card */}
      <div className="bg-white rounded-lg border border-gray-200 p-5 mb-6">
        <div className="grid grid-cols-2 md:grid-cols-4 gap-5">
          <div>
            <div className="text-xs font-semibold text-gray-400 uppercase tracking-wider mb-1">Format</div>
            <FormatBadge format={component.format} />
          </div>
          <div>
            <div className="text-xs font-semibold text-gray-400 uppercase tracking-wider mb-1">Version</div>
            <span className="font-mono text-sm text-gray-700 bg-gray-100 px-2 py-0.5 rounded">
              {component.version || '-'}
            </span>
          </div>
          <div>
            <div className="text-xs font-semibold text-gray-400 uppercase tracking-wider mb-1">Group</div>
            <span className="text-sm text-gray-700">{component.group || '-'}</span>
          </div>
          <div>
            <div className="text-xs font-semibold text-gray-400 uppercase tracking-wider mb-1">Repository</div>
            <span className="text-sm text-gray-700">{component.repository}</span>
          </div>
        </div>
      </div>

      {/* Assets */}
      <div className="mb-4">
        <h2 className="text-base font-semibold text-gray-900">
          Assets
          <span className="ml-2 text-sm font-normal text-gray-400">
            ({component.assets.length})
          </span>
        </h2>
      </div>

      {component.assets.length === 0 ? (
        <div className="bg-white rounded-lg border border-gray-200 overflow-hidden">
          <div className="p-10 text-center text-sm text-gray-500">No assets found for this component</div>
        </div>
      ) : (
        <div className="flex flex-col gap-3">
          {component.assets.map((asset) => (
            <AssetCard
              key={asset.id}
              asset={asset}
              onDelete={setDeleteAssetTarget}
              canDelete={isAdmin}
            />
          ))}
        </div>
      )}

      <ConfirmDialog
        open={deleteComponentOpen}
        title="Delete Component"
        message={`Are you sure you want to delete "${component.name}${
          component.version ? ' ' + component.version : ''
        }"? This permanently removes the component and all of its assets. This action cannot be undone.`}
        confirmLabel={working ? 'Deleting...' : 'Delete'}
        variant="danger"
        onConfirm={handleDeleteComponent}
        onCancel={() => setDeleteComponentOpen(false)}
      />

      <ConfirmDialog
        open={deleteAssetTarget !== null}
        title="Delete Asset"
        message={`Are you sure you want to delete "${
          deleteAssetTarget ? fileName(deleteAssetTarget.path) : ''
        }"? This permanently removes the asset and its stored file. This action cannot be undone.`}
        confirmLabel={working ? 'Deleting...' : 'Delete'}
        variant="danger"
        onConfirm={handleDeleteAsset}
        onCancel={() => setDeleteAssetTarget(null)}
      />
    </div>
  );
}
