import { useState, useEffect } from 'react';
import { api } from '../../api/client';
import LoadingSpinner from '../../components/LoadingSpinner';
import Badge from '../../components/Badge';
import EmptyState from '../../components/EmptyState';
import ConfirmDialog from '../../components/ConfirmDialog';
import { useToast } from '../../components/Toast';
import type { SslCertificateXO } from '../../types/api';

export default function SslCertificatesPage() {
  const { showToast } = useToast();
  const [certs, setCerts] = useState<SslCertificateXO[]>([]);
  const [loading, setLoading] = useState(true);
  const [deleteTarget, setDeleteTarget] = useState<SslCertificateXO | null>(null);
  const [deleting, setDeleting] = useState(false);

  function loadCerts() {
    api
      .get<SslCertificateXO[]>('/security/ssl')
      .then(setCerts)
      .catch(() => {
        setCerts([]);
      })
      .finally(() => setLoading(false));
  }

  useEffect(() => {
    loadCerts();
  }, []);

  async function handleDelete() {
    if (!deleteTarget) return;
    setDeleting(true);
    try {
      await api.delete(`/security/ssl/${deleteTarget.id}`);
      showToast('success', `Certificate "${deleteTarget.subjectCommonName}" deleted`);
      setDeleteTarget(null);
      loadCerts();
    } catch {
      showToast('error', `Failed to delete certificate`);
    } finally {
      setDeleting(false);
    }
  }

  function isExpired(expiresOn: string): boolean {
    return new Date(expiresOn) < new Date();
  }

  function isExpiringSoon(expiresOn: string): boolean {
    const thirtyDays = 30 * 24 * 60 * 60 * 1000;
    return new Date(expiresOn).getTime() - Date.now() < thirtyDays;
  }

  if (loading) {
    return (
      <div className="flex justify-center items-center py-20">
        <LoadingSpinner message="Loading certificates..." />
      </div>
    );
  }

  return (
    <div className="p-6 sm:p-8 max-w-7xl">
      <div className="flex items-start justify-between mb-6 gap-4">
        <div>
          <h1 className="text-2xl font-semibold text-gray-950">SSL Certificates</h1>
          <p className="text-sm text-gray-500 mt-1">Manage trusted SSL certificates for proxy repositories</p>
        </div>
      </div>

      {certs.length === 0 ? (
        <div className="bg-white rounded-lg border border-gray-200 overflow-hidden">
          <EmptyState
            icon={
              <svg className="mx-auto h-12 w-12 text-gray-300" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
                <path strokeLinecap="round" strokeLinejoin="round" d="M16.5 10.5V6.75a4.5 4.5 0 10-9 0v3.75m-.75 11.25h10.5a2.25 2.25 0 002.25-2.25v-6.75a2.25 2.25 0 00-2.25-2.25H6.75a2.25 2.25 0 00-2.25 2.25v6.75a2.25 2.25 0 002.25 2.25z" />
              </svg>
            }
            title="No SSL Certificates"
            description="SSL certificate management will be available in a future release."
          />
        </div>
      ) : (
        <div className="bg-white rounded-lg border border-gray-200 overflow-x-auto">
          <table className="w-full min-w-[600px]">
            <thead className="bg-gray-50 border-b border-gray-200">
              <tr>
                <th className="text-left px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wider">Subject</th>
                <th className="text-left px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wider">Issuer</th>
                <th className="text-left px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wider">Fingerprint</th>
                <th className="text-left px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wider">Expires</th>
                <th className="text-left px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wider"></th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {certs.map((cert) => (
                <tr key={cert.id} className="hover:bg-gray-50 transition-colors">
                  <td className="px-4 py-3 text-sm">
                    <div>
                      <span className="font-medium text-gray-900">{cert.subjectCommonName}</span>
                    </div>
                    <div className="text-xs text-gray-500 mt-0.5">{cert.subjectOrganization}</div>
                  </td>
                  <td className="px-4 py-3 text-sm">
                    <div className="text-gray-700">{cert.issuerCommonName}</div>
                    <div className="text-xs text-gray-500 mt-0.5">{cert.issuerOrganization}</div>
                  </td>
                  <td className="px-4 py-3 text-sm">
                    <code className="text-xs font-mono bg-gray-100 text-gray-600 px-1.5 py-0.5 rounded">
                      {cert.fingerprint.substring(0, 20)}...
                    </code>
                  </td>
                  <td className="px-4 py-3 text-sm">
                    <div className="flex items-center gap-2">
                      <span className="text-gray-700 text-xs tabular-nums">
                        {new Date(cert.expiresOn).toLocaleDateString()}
                      </span>
                      {isExpired(cert.expiresOn) ? (
                        <Badge variant="danger">Expired</Badge>
                      ) : isExpiringSoon(cert.expiresOn) ? (
                        <Badge variant="warning">Expiring Soon</Badge>
                      ) : (
                        <Badge variant="success">Valid</Badge>
                      )}
                    </div>
                  </td>
                  <td className="px-4 py-3 text-sm">
                    <button
                      className="inline-flex items-center px-3 py-1 bg-white border border-gray-200 hover:bg-red-50 text-red-600 text-xs font-medium rounded-md transition-colors"
                      onClick={() => setDeleteTarget(cert)}
                    >
                      Delete
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <ConfirmDialog
        open={deleteTarget !== null}
        title="Delete Certificate"
        message={`Are you sure you want to delete the certificate for "${deleteTarget?.subjectCommonName}"?`}
        confirmLabel={deleting ? 'Deleting...' : 'Delete'}
        variant="danger"
        onConfirm={handleDelete}
        onCancel={() => setDeleteTarget(null)}
      />
    </div>
  );
}
