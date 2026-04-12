import { useState, useEffect, useMemo } from 'react';
import { api, ApiError } from '../../api/client';
import DataTable, { type Column } from '../../components/DataTable';
import LoadingSpinner from '../../components/LoadingSpinner';
import Badge from '../../components/Badge';
import ConfirmDialog from '../../components/ConfirmDialog';
import { useToast } from '../../components/Toast';
import type { BlobStore } from '../../types/api';

type BlobStoreRow = BlobStore & Record<string, unknown>;
type BlobStoreType = 'File' | 'S3';

/* ── Consistent form classes ─────────────────────────────────────── */
const inputClass =
  'w-full px-3 py-2 border border-gray-300 rounded-md text-sm text-gray-700 bg-white placeholder:text-gray-400 focus:outline-none focus:border-blue-600 focus:ring-1 focus:ring-blue-600 transition-colors';
const selectClass =
  'w-full px-3 py-2 border border-gray-300 rounded-md text-sm text-gray-700 bg-white focus:outline-none focus:border-blue-600 focus:ring-1 focus:ring-blue-600 transition-colors';
const labelClass = 'block text-sm font-medium text-gray-700 mb-1.5';

const NAME_PATTERN = /^[a-zA-Z0-9][a-zA-Z0-9._-]*$/;

interface FileForm {
  name: string;
  path: string;
}

interface S3Form {
  name: string;
  bucket: string;
  region: string;
  accessKeyId: string;
  secretAccessKey: string;
  endpoint: string;
  prefix: string;
}

function formatBytes(bytes: number): string {
  if (bytes === 0) return '0 B';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  const i = Math.floor(Math.log(bytes) / Math.log(1024));
  return `${(bytes / Math.pow(1024, i)).toFixed(1)} ${units[i]}`;
}

/** Renders a simple usage bar for a blob store (total size vs available space). */
function StorageBar({ totalSize, available }: { totalSize: number; available: number | null }) {
  if (available == null || available === 0) return null;
  const used = totalSize;
  const total = used + available;
  const pct = Math.min(100, Math.round((used / total) * 100));
  const barColor = pct > 90 ? 'bg-red-500' : pct > 70 ? 'bg-amber-500' : 'bg-blue-500';
  return (
    <div className="flex items-center gap-2 min-w-[140px]">
      <div className="flex-1 h-2 bg-gray-100 rounded-full overflow-hidden">
        <div className={`h-full rounded-full ${barColor}`} style={{ width: `${pct}%` }} />
      </div>
      <span className="text-xs text-gray-500 tabular-nums whitespace-nowrap">{pct}%</span>
    </div>
  );
}

export default function BlobStoresPage() {
  const { showToast } = useToast();
  const [blobStores, setBlobStores] = useState<BlobStoreRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [deleteTarget, setDeleteTarget] = useState<BlobStoreRow | null>(null);
  const [deleting, setDeleting] = useState(false);

  /* ── Create dialog state ─────────────────────────────────────── */
  const [createOpen, setCreateOpen] = useState(false);
  const [storeType, setStoreType] = useState<BlobStoreType>('File');
  const [fileForm, setFileForm] = useState<FileForm>({ name: '', path: '' });
  const [s3Form, setS3Form] = useState<S3Form>({
    name: '',
    bucket: '',
    region: 'us-east-1',
    accessKeyId: '',
    secretAccessKey: '',
    endpoint: '',
    prefix: '',
  });
  const [creating, setCreating] = useState(false);
  const [nameError, setNameError] = useState<string | null>(null);

  function openCreateDialog() {
    setStoreType('File');
    setFileForm({ name: '', path: '' });
    setS3Form({ name: '', bucket: '', region: 'us-east-1', accessKeyId: '', secretAccessKey: '', endpoint: '', prefix: '' });
    setNameError(null);
    setCreateOpen(true);
  }

  function closeCreateDialog() {
    if (creating) return;
    setCreateOpen(false);
  }

  function currentName(): string {
    return storeType === 'File' ? fileForm.name : s3Form.name;
  }

  function validateName(name: string): string | null {
    if (!name) return 'Name is required';
    if (name.length < 2) return 'Name must be at least 2 characters';
    if (name.length > 100) return 'Name must be at most 100 characters';
    if (!NAME_PATTERN.test(name)) return 'Must start with alphanumeric; only letters, digits, dots, hyphens, underscores';
    return null;
  }

  function canSubmit(): boolean {
    const name = currentName();
    if (validateName(name) !== null) return false;
    if (storeType === 'S3') {
      return !!(s3Form.bucket && s3Form.region && s3Form.accessKeyId && s3Form.secretAccessKey);
    }
    return true;
  }

  async function handleCreate() {
    const name = currentName();
    const err = validateName(name);
    if (err) {
      setNameError(err);
      return;
    }
    setCreating(true);
    try {
      if (storeType === 'File') {
        const path = fileForm.path.trim() || `data/blobs/${fileForm.name}`;
        await api.post('/blobstores/file', { name: fileForm.name, path });
      } else {
        await api.post('/blobstores/s3', {
          name: s3Form.name,
          bucket: s3Form.bucket,
          region: s3Form.region,
          accessKeyId: s3Form.accessKeyId,
          secretAccessKey: s3Form.secretAccessKey,
          endpoint: s3Form.endpoint || undefined,
          prefix: s3Form.prefix || undefined,
        });
      }
      showToast('success', `Blob store "${name}" created`);
      setCreateOpen(false);
      loadBlobStores();
    } catch (e) {
      const message = e instanceof ApiError ? e.message : 'Failed to create blob store';
      showToast('error', message);
    } finally {
      setCreating(false);
    }
  }

  function loadBlobStores() {
    setLoading(true);
    api
      .get<BlobStore[]>('/blobstores')
      .then((data) => setBlobStores(data as BlobStoreRow[]))
      .catch(() => showToast('error', 'Failed to load blob stores'))
      .finally(() => setLoading(false));
  }

  useEffect(() => {
    loadBlobStores();
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  async function handleDelete() {
    if (!deleteTarget) return;
    setDeleting(true);
    try {
      await api.delete(`/blobstores/${deleteTarget.name}`);
      showToast('success', `Blob store "${deleteTarget.name}" deleted`);
      setDeleteTarget(null);
      loadBlobStores();
    } catch {
      showToast('error', `Failed to delete "${deleteTarget.name}"`);
    } finally {
      setDeleting(false);
    }
  }

  const columns: Column<BlobStoreRow>[] = useMemo(
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
        render: (row) => <Badge variant="default">{row.type}</Badge>,
      },
      {
        key: 'blobCount',
        label: 'Blob Count',
        sortable: true,
        sortType: 'number',
        width: '120px',
        render: (row) => <span className="text-gray-700 tabular-nums">{row.blobCount.toLocaleString()}</span>,
      },
      {
        key: 'totalSizeInBytes',
        label: 'Total Size',
        sortable: true,
        sortType: 'number',
        width: '120px',
        render: (row) => <span className="text-gray-700 tabular-nums">{formatBytes(row.totalSizeInBytes)}</span>,
      },
      {
        key: 'availableSpaceInBytes',
        label: 'Available',
        sortable: true,
        sortType: 'number',
        width: '120px',
        render: (row) => (
          <span className="text-gray-700 tabular-nums">
            {row.availableSpaceInBytes != null ? formatBytes(row.availableSpaceInBytes) : 'N/A'}
          </span>
        ),
      },
      {
        key: '_usage',
        label: 'Usage',
        width: '180px',
        render: (row) => (
          <StorageBar totalSize={row.totalSizeInBytes} available={row.availableSpaceInBytes} />
        ),
      },
      {
        key: '_actions',
        label: '',
        width: '60px',
        render: (row) => (
          <button
            className="inline-flex items-center px-3 py-1 bg-white border border-gray-200 hover:bg-red-50 text-red-600 text-xs font-medium rounded-md transition-colors"
            title="Delete blob store"
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
    [], // eslint-disable-line react-hooks/exhaustive-deps
  );

  if (loading) {
    return (
      <div className="flex justify-center items-center py-20">
        <LoadingSpinner message="Loading blob stores..." />
      </div>
    );
  }

  return (
    <div className="p-6 sm:p-8 max-w-7xl">
      <div className="flex items-start justify-between mb-6 gap-4">
        <div>
          <h1 className="text-2xl font-semibold text-gray-950">Blob Stores</h1>
          <p className="text-sm text-gray-500 mt-1">
            {blobStores.length} blob store{blobStores.length !== 1 ? 's' : ''} configured
          </p>
        </div>
        <button
          className="inline-flex items-center gap-1.5 px-4 py-2 bg-blue-600 hover:bg-blue-700 text-white text-sm font-medium rounded-md transition-colors"
          onClick={openCreateDialog}
        >
          Create Blob Store
        </button>
      </div>

      <div className="bg-white rounded-lg border border-gray-200 overflow-hidden">
        <DataTable
          columns={columns}
          data={blobStores}
          keyField="name"
          searchPlaceholder="Filter blob stores..."
          emptyMessage="No blob stores configured"
        />
      </div>

      {/* ── Create Blob Store Dialog ──────────────────────────────── */}
      {createOpen && (
        <div
          className="fixed inset-0 bg-black/50 flex items-center justify-center z-50"
          onClick={closeCreateDialog}
        >
          <div
            className="bg-white rounded-lg w-[520px] max-w-[90vw] max-h-[90vh] shadow-lg flex flex-col"
            onClick={(e) => e.stopPropagation()}
          >
            {/* Header */}
            <div className="px-6 py-5 border-b border-gray-200">
              <h3 className="text-lg font-semibold text-gray-950">Create Blob Store</h3>
              <p className="text-sm text-gray-500 mt-1">
                Configure a new blob store for artifact storage
              </p>
            </div>

            {/* Body */}
            <div className="px-6 py-5 flex-1 overflow-y-auto space-y-5">
              {/* Name */}
              <div>
                <label className={labelClass}>Name</label>
                <input
                  type="text"
                  className={`${inputClass} ${nameError ? 'border-red-400 focus:border-red-500 focus:ring-red-500' : ''}`}
                  placeholder="e.g. default, maven-blobs"
                  value={currentName()}
                  onChange={(e) => {
                    const v = e.target.value;
                    setNameError(null);
                    if (storeType === 'File') setFileForm((f) => ({ ...f, name: v }));
                    else setS3Form((f) => ({ ...f, name: v }));
                  }}
                />
                {nameError && <p className="text-xs text-red-500 mt-1">{nameError}</p>}
                <p className="text-xs text-gray-400 mt-1">
                  Alphanumeric, dots, hyphens, underscores. Minimum 2 characters.
                </p>
              </div>

              {/* Type selector */}
              <div>
                <label className={labelClass}>Type</label>
                <select
                  className={selectClass}
                  value={storeType}
                  onChange={(e) => {
                    const t = e.target.value as BlobStoreType;
                    setStoreType(t);
                    setNameError(null);
                    // Sync name between forms
                    if (t === 'S3') setS3Form((f) => ({ ...f, name: fileForm.name }));
                    else setFileForm((f) => ({ ...f, name: s3Form.name }));
                  }}
                >
                  <option value="File">File</option>
                  <option value="S3">S3</option>
                </select>
                <p className="text-xs text-gray-400 mt-1">
                  {storeType === 'File'
                    ? 'Store blobs on the local filesystem'
                    : 'Store blobs in an S3-compatible bucket'}
                </p>
              </div>

              {/* File-specific fields */}
              {storeType === 'File' && (
                <div>
                  <label className={labelClass}>Path</label>
                  <input
                    type="text"
                    className={inputClass}
                    placeholder={fileForm.name ? `data/blobs/${fileForm.name}` : 'data/blobs/<name>'}
                    value={fileForm.path}
                    onChange={(e) => setFileForm((f) => ({ ...f, path: e.target.value }))}
                  />
                  <p className="text-xs text-gray-400 mt-1">
                    Leave blank to auto-generate from name
                  </p>
                </div>
              )}

              {/* S3-specific fields */}
              {storeType === 'S3' && (
                <div className="space-y-4 border-t border-gray-200 pt-4">
                  <h4 className="text-sm font-semibold text-gray-900">S3 Configuration</h4>

                  <div className="grid grid-cols-2 gap-4">
                    <div>
                      <label className={labelClass}>Bucket</label>
                      <input
                        type="text"
                        className={inputClass}
                        placeholder="my-artifact-bucket"
                        value={s3Form.bucket}
                        onChange={(e) => setS3Form((f) => ({ ...f, bucket: e.target.value }))}
                      />
                    </div>
                    <div>
                      <label className={labelClass}>Region</label>
                      <input
                        type="text"
                        className={inputClass}
                        placeholder="us-east-1"
                        value={s3Form.region}
                        onChange={(e) => setS3Form((f) => ({ ...f, region: e.target.value }))}
                      />
                    </div>
                  </div>

                  <div>
                    <label className={labelClass}>Access Key ID</label>
                    <input
                      type="text"
                      className={`${inputClass} font-mono text-xs`}
                      placeholder="AKIAIOSFODNN7EXAMPLE"
                      value={s3Form.accessKeyId}
                      onChange={(e) => setS3Form((f) => ({ ...f, accessKeyId: e.target.value }))}
                    />
                  </div>

                  <div>
                    <label className={labelClass}>Secret Access Key</label>
                    <input
                      type="password"
                      className={`${inputClass} font-mono text-xs`}
                      placeholder="wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
                      value={s3Form.secretAccessKey}
                      onChange={(e) => setS3Form((f) => ({ ...f, secretAccessKey: e.target.value }))}
                    />
                  </div>

                  <div className="grid grid-cols-2 gap-4">
                    <div>
                      <label className={labelClass}>
                        Endpoint <span className="text-gray-400 font-normal">(optional)</span>
                      </label>
                      <input
                        type="text"
                        className={inputClass}
                        placeholder="https://s3.example.com"
                        value={s3Form.endpoint}
                        onChange={(e) => setS3Form((f) => ({ ...f, endpoint: e.target.value }))}
                      />
                      <p className="text-xs text-gray-400 mt-1">For S3-compatible services (MinIO, etc.)</p>
                    </div>
                    <div>
                      <label className={labelClass}>
                        Prefix <span className="text-gray-400 font-normal">(optional)</span>
                      </label>
                      <input
                        type="text"
                        className={inputClass}
                        placeholder="megarepo/"
                        value={s3Form.prefix}
                        onChange={(e) => setS3Form((f) => ({ ...f, prefix: e.target.value }))}
                      />
                      <p className="text-xs text-gray-400 mt-1">Key prefix inside the bucket</p>
                    </div>
                  </div>
                </div>
              )}
            </div>

            {/* Footer */}
            <div className="px-6 py-4 border-t border-gray-200 flex gap-2 justify-end">
              <button
                className="inline-flex items-center px-4 py-2 bg-white border border-gray-200 text-gray-700 text-sm font-medium rounded-md hover:bg-gray-50 transition-colors"
                onClick={closeCreateDialog}
              >
                Cancel
              </button>
              <button
                className="inline-flex items-center px-4 py-2 bg-blue-600 hover:bg-blue-700 text-white text-sm font-medium rounded-md transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                onClick={handleCreate}
                disabled={creating || !canSubmit()}
              >
                {creating ? 'Creating...' : 'Create Blob Store'}
              </button>
            </div>
          </div>
        </div>
      )}

      <ConfirmDialog
        open={deleteTarget !== null}
        title="Delete Blob Store"
        message={`Are you sure you want to delete blob store "${deleteTarget?.name}"? This will permanently remove all data. This action cannot be undone.`}
        confirmLabel={deleting ? 'Deleting...' : 'Delete'}
        variant="danger"
        onConfirm={handleDelete}
        onCancel={() => setDeleteTarget(null)}
      />
    </div>
  );
}
