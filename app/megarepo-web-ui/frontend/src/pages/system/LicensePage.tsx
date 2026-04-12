import { useState, useEffect, useCallback, useRef } from 'react';
import { api, ApiError } from '../../api/client';
import LoadingSpinner from '../../components/LoadingSpinner';
import Badge from '../../components/Badge';
import ConfirmDialog from '../../components/ConfirmDialog';
import { useToast } from '../../components/Toast';
import type { LicenseStatus } from '../../types/api';

function CheckCircleIcon({ className = '' }: { className?: string }) {
  return (
    <svg className={className} fill="none" viewBox="0 0 24 24" strokeWidth={1.5} stroke="currentColor" width="24" height="24">
      <path strokeLinecap="round" strokeLinejoin="round" d="M9 12.75L11.25 15 15 9.75M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
    </svg>
  );
}

function ExclamationTriangleIcon({ className = '' }: { className?: string }) {
  return (
    <svg className={className} fill="none" viewBox="0 0 24 24" strokeWidth={1.5} stroke="currentColor" width="24" height="24">
      <path strokeLinecap="round" strokeLinejoin="round" d="M12 9v3.75m-9.303 3.376c-.866 1.5.217 3.374 1.948 3.374h14.71c1.73 0 2.813-1.874 1.948-3.374L13.949 3.378c-.866-1.5-3.032-1.5-3.898 0L2.697 16.126zM12 15.75h.007v.008H12v-.008z" />
    </svg>
  );
}

function UsersIcon({ className = '' }: { className?: string }) {
  return (
    <svg className={className} fill="none" viewBox="0 0 24 24" strokeWidth={1.5} stroke="currentColor" width="24" height="24">
      <path strokeLinecap="round" strokeLinejoin="round" d="M15 19.128a9.38 9.38 0 002.625.372 9.337 9.337 0 004.121-.952 4.125 4.125 0 00-7.533-2.493M15 19.128v-.003c0-1.113-.285-2.16-.786-3.07M15 19.128v.106A12.318 12.318 0 018.624 21c-2.331 0-4.512-.645-6.374-1.766l-.001-.109a6.375 6.375 0 0111.964-3.07M12 6.375a3.375 3.375 0 11-6.75 0 3.375 3.375 0 016.75 0zm8.25 2.25a2.625 2.625 0 11-5.25 0 2.625 2.625 0 015.25 0z" />
    </svg>
  );
}

function BuildingOfficeIcon({ className = '' }: { className?: string }) {
  return (
    <svg className={className} fill="none" viewBox="0 0 24 24" strokeWidth={1.5} stroke="currentColor" width="24" height="24">
      <path strokeLinecap="round" strokeLinejoin="round" d="M3.75 21h16.5M4.5 3h15M5.25 3v18m13.5-18v18M9 6.75h1.5m-1.5 3h1.5m-1.5 3h1.5m3-6H15m-1.5 3H15m-1.5 3H15M9 21v-3.375c0-.621.504-1.125 1.125-1.125h3.75c.621 0 1.125.504 1.125 1.125V21" />
    </svg>
  );
}

function ArrowUpTrayIcon({ className = '' }: { className?: string }) {
  return (
    <svg className={className} fill="none" viewBox="0 0 24 24" strokeWidth={1.5} stroke="currentColor" width="48" height="48">
      <path strokeLinecap="round" strokeLinejoin="round" d="M3 16.5v2.25A2.25 2.25 0 005.25 21h13.5A2.25 2.25 0 0021 18.75V16.5m-13.5-9L12 3m0 0l4.5 4.5M12 3v13.5" />
    </svg>
  );
}

export default function LicensePage() {
  const { showToast } = useToast();
  const [license, setLicense] = useState<LicenseStatus | null>(null);
  const [loading, setLoading] = useState(true);
  const [uploading, setUploading] = useState(false);
  const [removing, setRemoving] = useState(false);
  const [showRemoveDialog, setShowRemoveDialog] = useState(false);
  const [dragOver, setDragOver] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);

  const fetchLicense = useCallback(() => {
    setLoading(true);
    api
      .get<LicenseStatus>('/system/license')
      .then(setLicense)
      .catch(() => showToast('error', 'Failed to load license status'))
      .finally(() => setLoading(false));
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    fetchLicense();
  }, [fetchLicense]);

  const uploadFile = async (file: File) => {
    setUploading(true);
    try {
      const buffer = await file.arrayBuffer();
      const bytes = Array.from(new Uint8Array(buffer));
      const result = await api.post<LicenseStatus>('/system/license', bytes);
      setLicense(result);
      showToast('success', 'License uploaded successfully');
    } catch (err) {
      const message = err instanceof ApiError ? err.message : 'Failed to upload license';
      showToast('error', message);
    } finally {
      setUploading(false);
      if (fileInputRef.current) {
        fileInputRef.current.value = '';
      }
    }
  };

  const handleFileChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (file) uploadFile(file);
  };

  const handleDrop = (e: React.DragEvent) => {
    e.preventDefault();
    setDragOver(false);
    const file = e.dataTransfer.files[0];
    if (file) uploadFile(file);
  };

  const handleRemove = async () => {
    setShowRemoveDialog(false);
    setRemoving(true);
    try {
      await api.delete('/system/license');
      fetchLicense();
      showToast('success', 'License removed');
    } catch {
      showToast('error', 'Failed to remove license');
    } finally {
      setRemoving(false);
    }
  };

  if (loading) {
    return (
      <div className="flex justify-center items-center py-20">
        <LoadingSpinner message="Loading license status..." />
      </div>
    );
  }

  const isLicensed = license?.licensed ?? false;

  return (
    <div className="p-6 sm:p-8 max-w-7xl">
      <div className="flex items-start justify-between mb-6 gap-4">
        <div>
          <h1 className="text-2xl font-semibold text-gray-950">License</h1>
          <p className="text-sm text-gray-500 mt-1">Manage your MegaRepo license</p>
        </div>
        {isLicensed && (
          <button
            className="inline-flex items-center px-4 py-2 bg-white border border-gray-200 hover:bg-red-50 text-red-600 text-sm font-medium rounded-md transition-colors disabled:opacity-50"
            onClick={() => setShowRemoveDialog(true)}
            disabled={removing}
          >
            {removing ? 'Removing...' : 'Remove License'}
          </button>
        )}
      </div>

      {/* Status Cards */}
      <div className="grid grid-cols-1 sm:grid-cols-3 gap-6 mb-8">
        <div className="bg-white rounded-lg border border-gray-200 shadow-sm p-5 flex items-center gap-4">
          <div
            className={`w-10 h-10 rounded-lg flex items-center justify-center shrink-0 ${
              isLicensed ? 'bg-green-50' : 'bg-amber-50'
            }`}
          >
            {isLicensed
              ? <CheckCircleIcon className="text-green-600" />
              : <ExclamationTriangleIcon className="text-amber-600" />
            }
          </div>
          <div>
            <div className="text-2xl font-semibold text-gray-950">
              {isLicensed ? 'Licensed' : 'Community'}
            </div>
            <div className="text-sm text-gray-500 mt-0.5">Edition</div>
          </div>
        </div>

        <div className="bg-white rounded-lg border border-gray-200 shadow-sm p-5 flex items-center gap-4">
          <div className="w-10 h-10 rounded-lg flex items-center justify-center shrink-0 bg-blue-50">
            <UsersIcon className="text-blue-600" />
          </div>
          <div>
            <div className="text-2xl font-semibold text-gray-950">{license?.activeUsers ?? 0}</div>
            <div className="text-sm text-gray-500 mt-0.5">Active Users (30 days)</div>
          </div>
        </div>

        <div className="bg-white rounded-lg border border-gray-200 shadow-sm p-5 flex items-center gap-4">
          <div
            className={`w-10 h-10 rounded-lg flex items-center justify-center shrink-0 ${
              isLicensed ? 'bg-purple-50' : 'bg-gray-100'
            }`}
          >
            <BuildingOfficeIcon className={isLicensed ? 'text-purple-600' : 'text-gray-400'} />
          </div>
          <div>
            <div className="text-2xl font-semibold text-gray-950">
              {license?.company ?? 'Not licensed'}
            </div>
            <div className="text-sm text-gray-500 mt-0.5">Licensed To</div>
          </div>
        </div>
      </div>

      {/* License Details */}
      <div className="bg-white rounded-lg border border-gray-200 overflow-hidden mb-6">
        <div className="px-6 py-4 bg-gray-50 border-b border-gray-200">
          <h2 className="text-sm font-semibold text-gray-700">License Details</h2>
        </div>
        <div className="p-6">
          <dl className="divide-y divide-gray-100">
            <div className="flex py-3.5">
              <dt className="w-40 shrink-0 text-sm font-medium text-gray-500">Status</dt>
              <dd className="flex-1">
                <Badge variant={isLicensed ? 'success' : 'warning'}>
                  {isLicensed ? 'Active' : 'Community Edition'}
                </Badge>
              </dd>
            </div>
            <div className="flex py-3.5">
              <dt className="w-40 shrink-0 text-sm font-medium text-gray-500">Company</dt>
              <dd className="flex-1 text-sm text-gray-700">{license?.company ?? '-'}</dd>
            </div>
            <div className="flex py-3.5">
              <dt className="w-40 shrink-0 text-sm font-medium text-gray-500">Email</dt>
              <dd className="flex-1 text-sm text-gray-700">{license?.email ?? '-'}</dd>
            </div>
            <div className="flex py-3.5">
              <dt className="w-40 shrink-0 text-sm font-medium text-gray-500">Issued At</dt>
              <dd className="flex-1 text-sm text-gray-700">
                {license?.issuedAt ? new Date(license.issuedAt).toLocaleDateString() : '-'}
              </dd>
            </div>
            <div className="flex py-3.5">
              <dt className="w-40 shrink-0 text-sm font-medium text-gray-500">Active Users</dt>
              <dd className="flex-1 text-sm text-gray-700">
                {license?.activeUsers ?? 0}
                <span className="text-gray-400 ml-2">in last 30 days</span>
                {!isLicensed && (
                  <span className="text-gray-400 ml-1">(limit: 50 for Community Edition)</span>
                )}
              </dd>
            </div>
            {license?.requiresPurchase && (
              <div className="flex py-3.5">
                <dt className="w-40 shrink-0 text-sm font-medium text-gray-500">Notice</dt>
                <dd className="flex-1 text-sm text-red-600 font-medium">
                  User limit exceeded. Please purchase a business license at bsnsoft.de/megarepo
                </dd>
              </div>
            )}
            <div className="flex py-3.5">
              <dt className="w-40 shrink-0 text-sm font-medium text-gray-500">Message</dt>
              <dd className="flex-1 text-sm text-gray-700">{license?.message ?? '-'}</dd>
            </div>
          </dl>
        </div>
      </div>

      {/* Upload License */}
      <div className="bg-white rounded-lg border border-gray-200 overflow-hidden mb-6">
        <div className="px-6 py-4 bg-gray-50 border-b border-gray-200">
          <h2 className="text-sm font-semibold text-gray-700">
            {isLicensed ? 'Update License' : 'Upload License'}
          </h2>
        </div>
        <div className="p-6">
          <div
            className={`border-2 border-dashed rounded-lg p-10 text-center transition-colors cursor-pointer ${
              dragOver
                ? 'border-blue-400 bg-blue-50'
                : 'border-gray-200 hover:border-gray-300 hover:bg-gray-50'
            } ${uploading ? 'opacity-50 pointer-events-none' : ''}`}
            onDragOver={(e) => {
              e.preventDefault();
              setDragOver(true);
            }}
            onDragLeave={() => setDragOver(false)}
            onDrop={handleDrop}
            onClick={() => fileInputRef.current?.click()}
          >
            <input
              ref={fileInputRef}
              type="file"
              accept=".license"
              className="hidden"
              onChange={handleFileChange}
            />
            {uploading ? (
              <LoadingSpinner message="Uploading license..." />
            ) : (
              <>
                <div className="flex justify-center mb-3">
                  <ArrowUpTrayIcon className="text-gray-300" />
                </div>
                <div className="text-sm font-medium text-gray-700 mb-1">
                  Drop your license file here or click to browse
                </div>
                <div className="text-xs text-gray-400">Accepts .license files</div>
              </>
            )}
          </div>
        </div>
      </div>

      {/* Edition Comparison */}
      <div className="bg-white rounded-lg border border-gray-200 overflow-hidden">
        <div className="px-6 py-4 bg-gray-50 border-b border-gray-200">
          <h2 className="text-sm font-semibold text-gray-700">Edition Comparison</h2>
        </div>
        <div className="p-6">
          <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
            {/* Community */}
            <div className="border border-gray-200 rounded-lg p-6">
              <div className="flex items-center gap-2 mb-4">
                <h3 className="text-base font-semibold text-gray-900">Community Edition</h3>
                <Badge variant="default">Free</Badge>
              </div>
              <ul className="space-y-2.5 text-sm text-gray-600">
                <li className="flex items-start gap-2">
                  <svg className="text-green-500 mt-0.5 shrink-0" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5">
                    <polyline points="20 6 9 17 4 12" />
                  </svg>
                  <span>All repository formats (Maven, npm, PyPI, Raw)</span>
                </li>
                <li className="flex items-start gap-2">
                  <svg className="text-green-500 mt-0.5 shrink-0" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5">
                    <polyline points="20 6 9 17 4 12" />
                  </svg>
                  <span>Hosted, proxy, and group repositories</span>
                </li>
                <li className="flex items-start gap-2">
                  <svg className="text-green-500 mt-0.5 shrink-0" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5">
                    <polyline points="20 6 9 17 4 12" />
                  </svg>
                  <span>Full-text search</span>
                </li>
                <li className="flex items-start gap-2">
                  <svg className="text-green-500 mt-0.5 shrink-0" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5">
                    <polyline points="20 6 9 17 4 12" />
                  </svg>
                  <span>Up to 50 active users</span>
                </li>
                <li className="flex items-start gap-2">
                  <svg className="text-green-500 mt-0.5 shrink-0" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5">
                    <polyline points="20 6 9 17 4 12" />
                  </svg>
                  <span>RBAC and user management</span>
                </li>
                <li className="flex items-start gap-2">
                  <svg className="text-gray-300 mt-0.5 shrink-0" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5">
                    <line x1="18" y1="6" x2="6" y2="18" />
                    <line x1="6" y1="6" x2="18" y2="18" />
                  </svg>
                  <span className="text-gray-400">Unlimited users</span>
                </li>
                <li className="flex items-start gap-2">
                  <svg className="text-gray-300 mt-0.5 shrink-0" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5">
                    <line x1="18" y1="6" x2="6" y2="18" />
                    <line x1="6" y1="6" x2="18" y2="18" />
                  </svg>
                  <span className="text-gray-400">Priority support</span>
                </li>
              </ul>
            </div>

            {/* Business */}
            <div className={`border rounded-lg p-6 ${isLicensed ? 'border-blue-300 bg-blue-50/30' : 'border-gray-200'}`}>
              <div className="flex items-center gap-2 mb-4">
                <h3 className="text-base font-semibold text-gray-900">Business Edition</h3>
                <Badge variant="info">600 EUR/year</Badge>
              </div>
              <ul className="space-y-2.5 text-sm text-gray-600">
                <li className="flex items-start gap-2">
                  <svg className="text-green-500 mt-0.5 shrink-0" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5">
                    <polyline points="20 6 9 17 4 12" />
                  </svg>
                  <span>Everything in Community Edition</span>
                </li>
                <li className="flex items-start gap-2">
                  <svg className="text-green-500 mt-0.5 shrink-0" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5">
                    <polyline points="20 6 9 17 4 12" />
                  </svg>
                  <span>Unlimited active users</span>
                </li>
                <li className="flex items-start gap-2">
                  <svg className="text-green-500 mt-0.5 shrink-0" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5">
                    <polyline points="20 6 9 17 4 12" />
                  </svg>
                  <span>Priority email support</span>
                </li>
                <li className="flex items-start gap-2">
                  <svg className="text-green-500 mt-0.5 shrink-0" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5">
                    <polyline points="20 6 9 17 4 12" />
                  </svg>
                  <span>Flat fee - no per-seat pricing</span>
                </li>
                <li className="flex items-start gap-2">
                  <svg className="text-green-500 mt-0.5 shrink-0" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5">
                    <polyline points="20 6 9 17 4 12" />
                  </svg>
                  <span>LDAP/Active Directory integration</span>
                </li>
              </ul>
              {!isLicensed && (
                <a
                  href="https://bsnsoft.de/megarepo"
                  target="_blank"
                  rel="noopener noreferrer"
                  className="inline-flex items-center justify-center gap-1.5 mt-5 px-4 py-2 bg-blue-600 text-white text-sm font-medium rounded-md hover:bg-blue-700 transition-colors"
                >
                  Get a License
                </a>
              )}
            </div>
          </div>
        </div>
      </div>

      <ConfirmDialog
        open={showRemoveDialog}
        title="Remove License"
        message="Are you sure you want to remove the current license? MegaRepo will revert to the Community Edition with a 50-user limit."
        confirmLabel="Remove License"
        cancelLabel="Keep License"
        variant="danger"
        onConfirm={handleRemove}
        onCancel={() => setShowRemoveDialog(false)}
      />
    </div>
  );
}
