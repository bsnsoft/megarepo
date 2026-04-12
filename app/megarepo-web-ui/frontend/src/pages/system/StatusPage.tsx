import { useState, useEffect } from 'react';
import { api } from '../../api/client';
import LoadingSpinner from '../../components/LoadingSpinner';
import Badge from '../../components/Badge';
import { useToast } from '../../components/Toast';
import type { StatusCheck } from '../../types/api';

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

function TagIcon({ className = '' }: { className?: string }) {
  return (
    <svg className={className} fill="none" viewBox="0 0 24 24" strokeWidth={1.5} stroke="currentColor" width="24" height="24">
      <path strokeLinecap="round" strokeLinejoin="round" d="M9.568 3H5.25A2.25 2.25 0 003 5.25v4.318c0 .597.237 1.17.659 1.591l9.581 9.581c.699.699 1.78.872 2.607.33a18.095 18.095 0 005.223-5.223c.542-.827.369-1.908-.33-2.607L11.16 3.66A2.25 2.25 0 009.568 3z" />
      <path strokeLinecap="round" strokeLinejoin="round" d="M6 6h.008v.008H6V6z" />
    </svg>
  );
}

function CubeIcon({ className = '' }: { className?: string }) {
  return (
    <svg className={className} fill="none" viewBox="0 0 24 24" strokeWidth={1.5} stroke="currentColor" width="24" height="24">
      <path strokeLinecap="round" strokeLinejoin="round" d="M21 7.5l-9-5.25L3 7.5m18 0l-9 5.25m9-5.25v9l-9 5.25M3 7.5l9 5.25M3 7.5v9l9 5.25m0-9v9" />
    </svg>
  );
}

export default function StatusPage() {
  const { showToast } = useToast();
  const [status, setStatus] = useState<StatusCheck | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    api
      .get<StatusCheck>('/status/check')
      .then(setStatus)
      .catch(() => showToast('error', 'Failed to load system status'))
      .finally(() => setLoading(false));
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  if (loading) {
    return (
      <div className="flex justify-center items-center py-20">
        <LoadingSpinner message="Loading status..." />
      </div>
    );
  }

  const healthOk = status?.status === 'UP';

  return (
    <div className="p-6 sm:p-8 max-w-7xl">
      <div className="flex items-start justify-between mb-6 gap-4">
        <div>
          <h1 className="text-2xl font-semibold text-gray-950">System Status</h1>
          <p className="text-sm text-gray-500 mt-1">Health and version information</p>
        </div>
      </div>

      <div className="grid grid-cols-1 sm:grid-cols-3 gap-6 mb-8">
        <div className="bg-white rounded-lg border border-gray-200 shadow-sm p-5 flex items-center gap-4">
          <div
            className={`w-10 h-10 rounded-lg flex items-center justify-center shrink-0 ${
              healthOk ? 'bg-green-50' : 'bg-red-50'
            }`}
          >
            {healthOk
              ? <CheckCircleIcon className="text-green-600" />
              : <ExclamationTriangleIcon className="text-red-600" />
            }
          </div>
          <div>
            <div className="text-2xl font-semibold text-gray-950">{healthOk ? 'Healthy' : 'Degraded'}</div>
            <div className="text-sm text-gray-500 mt-0.5">System Health</div>
          </div>
        </div>

        <div className="bg-white rounded-lg border border-gray-200 shadow-sm p-5 flex items-center gap-4">
          <div className="w-10 h-10 rounded-lg flex items-center justify-center shrink-0 bg-blue-50">
            <TagIcon className="text-blue-600" />
          </div>
          <div>
            <div className="text-2xl font-semibold text-gray-950">{status?.version ?? 'Unknown'}</div>
            <div className="text-sm text-gray-500 mt-0.5">Version</div>
          </div>
        </div>

        <div className="bg-white rounded-lg border border-gray-200 shadow-sm p-5 flex items-center gap-4">
          <div className="w-10 h-10 rounded-lg flex items-center justify-center shrink-0 bg-purple-50">
            <CubeIcon className="text-purple-600" />
          </div>
          <div>
            <div className="text-2xl font-semibold text-gray-950">{status?.edition ?? 'Unknown'}</div>
            <div className="text-sm text-gray-500 mt-0.5">Edition</div>
          </div>
        </div>
      </div>

      <div className="bg-white rounded-lg border border-gray-200 overflow-hidden">
        <div className="px-6 py-4 bg-gray-50 border-b border-gray-200">
          <h2 className="text-sm font-semibold text-gray-700">Details</h2>
        </div>
        <div className="p-6">
          <dl className="divide-y divide-gray-100">
            <div className="flex py-3.5">
              <dt className="w-40 shrink-0 text-sm font-medium text-gray-500">Status</dt>
              <dd className="flex-1">
                <Badge variant={healthOk ? 'success' : 'danger'}>
                  {status?.status ?? 'Unknown'}
                </Badge>
              </dd>
            </div>
            <div className="flex py-3.5">
              <dt className="w-40 shrink-0 text-sm font-medium text-gray-500">Version</dt>
              <dd className="flex-1 text-sm text-gray-700">{status?.version ?? 'Unknown'}</dd>
            </div>
            <div className="flex py-3.5">
              <dt className="w-40 shrink-0 text-sm font-medium text-gray-500">Edition</dt>
              <dd className="flex-1 text-sm text-gray-700">{status?.edition ?? 'Unknown'}</dd>
            </div>
          </dl>
        </div>
      </div>
    </div>
  );
}
