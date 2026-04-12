import { useState, useEffect, useMemo, useCallback } from 'react';
import { api } from '../../api/client';
import DataTable, { type Column } from '../../components/DataTable';
import LoadingSpinner from '../../components/LoadingSpinner';
import Badge from '../../components/Badge';
import { useToast } from '../../components/Toast';
import type { PageResponse, AuditLogXO } from '../../types/api';

type AuditRow = AuditLogXO & Record<string, unknown>;

function formatTimestamp(ts: string): string {
  return new Date(ts).toLocaleString();
}

const actionVariants: Record<string, 'success' | 'info' | 'danger' | 'warning' | 'default'> = {
  UPLOAD: 'success',
  DOWNLOAD: 'info',
  DELETE: 'danger',
  LOGIN: 'warning',
  CREATE: 'success',
  UPDATE: 'info',
};

const ACTION_OPTIONS = ['UPLOAD', 'DOWNLOAD', 'DELETE', 'PROXY_FETCH', 'CACHE_HIT', 'LOGIN', 'CREATE', 'UPDATE'];

function buildQueryParams(filters: {
  user: string;
  action: string;
  from: string;
  to: string;
}): string {
  const params = new URLSearchParams();
  if (filters.user) params.set('user', filters.user);
  if (filters.action) params.set('action', filters.action);
  if (filters.from) params.set('from', new Date(filters.from).toISOString());
  if (filters.to) params.set('to', new Date(filters.to).toISOString());
  return params.toString();
}

export default function AuditLogPage() {
  const { showToast } = useToast();
  const [entries, setEntries] = useState<AuditRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [exporting, setExporting] = useState(false);
  const [filterUser, setFilterUser] = useState('');
  const [filterAction, setFilterAction] = useState('');
  const [filterFrom, setFilterFrom] = useState('');
  const [filterTo, setFilterTo] = useState('');
  const [showFilters, setShowFilters] = useState(false);

  const loadEntries = useCallback(() => {
    setLoading(true);
    const qs = buildQueryParams({ user: filterUser, action: filterAction, from: filterFrom, to: filterTo });
    const path = qs ? `/audit?${qs}` : '/audit';
    api
      .get<PageResponse<AuditLogXO>>(path)
      .then((data) => setEntries(data.items as AuditRow[]))
      .catch(() => showToast('error', 'Failed to load audit log'))
      .finally(() => setLoading(false));
  }, [filterUser, filterAction, filterFrom, filterTo]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    loadEntries();
  }, [loadEntries]);

  async function handleExport(format: 'csv' | 'json') {
    setExporting(true);
    try {
      const qs = buildQueryParams({ user: filterUser, action: filterAction, from: filterFrom, to: filterTo });
      const params = qs ? `format=${format}&${qs}` : `format=${format}`;
      const token = localStorage.getItem('token');
      const res = await fetch(`/api/v1/audit/export?${params}`, {
        headers: token ? { Authorization: `Bearer ${token}` } : {},
      });
      if (!res.ok) {
        throw new Error('Export failed');
      }
      const blob = await res.blob();
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `audit-log.${format}`;
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      URL.revokeObjectURL(url);
      showToast('success', `Audit log exported as ${format.toUpperCase()}`);
    } catch {
      showToast('error', 'Failed to export audit log');
    } finally {
      setExporting(false);
    }
  }

  const hasActiveFilters = filterUser || filterAction || filterFrom || filterTo;

  function clearFilters() {
    setFilterUser('');
    setFilterAction('');
    setFilterFrom('');
    setFilterTo('');
  }

  const columns: Column<AuditRow>[] = useMemo(
    () => [
      {
        key: 'timestamp',
        label: 'Time',
        sortable: true,
        sortType: 'date',
        width: '180px',
        render: (row) => (
          <span className="text-gray-600 text-xs tabular-nums">{formatTimestamp(row.timestamp)}</span>
        ),
      },
      {
        key: 'userId',
        label: 'User',
        sortable: true,
        width: '120px',
        render: (row) => <span className="font-medium text-gray-900">{row.userId}</span>,
      },
      {
        key: 'action',
        label: 'Action',
        sortable: true,
        width: '110px',
        render: (row) => (
          <Badge variant={actionVariants[row.action.toUpperCase()] || 'default'}>{row.action}</Badge>
        ),
      },
      {
        key: 'repository',
        label: 'Repository',
        sortable: true,
        width: '150px',
        render: (row) => <span className="text-gray-700">{row.repository || '-'}</span>,
      },
      {
        key: 'path',
        label: 'Path',
        render: (row) => (
          <code className="text-xs font-mono bg-gray-100 text-gray-600 px-1.5 py-0.5 rounded max-w-xs truncate block">
            {row.path}
          </code>
        ),
      },
      {
        key: 'ipAddress',
        label: 'IP',
        width: '120px',
        render: (row) => <span className="text-gray-500 text-xs tabular-nums">{row.ipAddress}</span>,
      },
    ],
    [],
  );

  return (
    <div className="p-6 sm:p-8 max-w-7xl">
      <div className="flex items-start justify-between mb-6 gap-4">
        <div>
          <h1 className="text-2xl font-semibold text-gray-950">Audit Log</h1>
          <p className="text-sm text-gray-500 mt-1">Activity history and access logs</p>
        </div>
        <div className="flex gap-2 shrink-0">
          <button
            className="inline-flex items-center gap-1.5 px-3 py-2 bg-white border border-gray-200 hover:bg-gray-50 text-gray-700 text-sm font-medium rounded-md transition-colors"
            onClick={() => setShowFilters(!showFilters)}
          >
            <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
              <path strokeLinecap="round" strokeLinejoin="round" d="M12 3c2.755 0 5.455.232 8.083.678.533.09.917.556.917 1.096v1.044a2.25 2.25 0 01-.659 1.591l-5.432 5.432a2.25 2.25 0 00-.659 1.591v2.927a2.25 2.25 0 01-1.244 2.013L9.75 21v-6.568a2.25 2.25 0 00-.659-1.591L3.659 7.409A2.25 2.25 0 013 5.818V4.774c0-.54.384-1.006.917-1.096A48.32 48.32 0 0112 3z" />
            </svg>
            Filters
            {hasActiveFilters && (
              <span className="w-2 h-2 rounded-full bg-blue-500" />
            )}
          </button>
          <div className="relative group">
            <button
              className="inline-flex items-center gap-1.5 px-3 py-2 bg-white border border-gray-200 hover:bg-gray-50 text-gray-700 text-sm font-medium rounded-md transition-colors disabled:opacity-50"
              disabled={exporting}
            >
              <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                <path strokeLinecap="round" strokeLinejoin="round" d="M3 16.5v2.25A2.25 2.25 0 005.25 21h13.5A2.25 2.25 0 0021 18.75V16.5M16.5 12L12 16.5m0 0L7.5 12m4.5 4.5V3" />
              </svg>
              {exporting ? 'Exporting...' : 'Export'}
              <svg className="w-3 h-3 ml-0.5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                <path strokeLinecap="round" strokeLinejoin="round" d="M19.5 8.25l-7.5 7.5-7.5-7.5" />
              </svg>
            </button>
            <div className="absolute right-0 top-full mt-1 bg-white border border-gray-200 rounded-md shadow-lg opacity-0 invisible group-hover:opacity-100 group-hover:visible transition-all z-10 min-w-[120px]">
              <button
                className="w-full text-left px-4 py-2 text-sm text-gray-700 hover:bg-gray-50 rounded-t-md"
                onClick={() => handleExport('csv')}
              >
                Export as CSV
              </button>
              <button
                className="w-full text-left px-4 py-2 text-sm text-gray-700 hover:bg-gray-50 rounded-b-md"
                onClick={() => handleExport('json')}
              >
                Export as JSON
              </button>
            </div>
          </div>
        </div>
      </div>

      {/* Filter Panel */}
      {showFilters && (
        <div className="bg-white rounded-lg border border-gray-200 p-4 mb-4">
          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
            <div className="flex flex-col gap-1">
              <label className="text-xs font-medium text-gray-500">User</label>
              <input
                type="text"
                value={filterUser}
                onChange={(e) => setFilterUser(e.target.value)}
                placeholder="Filter by user..."
                className="px-3 py-1.5 border border-gray-200 rounded-md text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none"
              />
            </div>
            <div className="flex flex-col gap-1">
              <label className="text-xs font-medium text-gray-500">Action</label>
              <select
                value={filterAction}
                onChange={(e) => setFilterAction(e.target.value)}
                className="px-3 py-1.5 border border-gray-200 rounded-md text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none"
              >
                <option value="">All actions</option>
                {ACTION_OPTIONS.map((a) => (
                  <option key={a} value={a}>
                    {a}
                  </option>
                ))}
              </select>
            </div>
            <div className="flex flex-col gap-1">
              <label className="text-xs font-medium text-gray-500">From</label>
              <input
                type="datetime-local"
                value={filterFrom}
                onChange={(e) => setFilterFrom(e.target.value)}
                className="px-3 py-1.5 border border-gray-200 rounded-md text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none"
              />
            </div>
            <div className="flex flex-col gap-1">
              <label className="text-xs font-medium text-gray-500">To</label>
              <input
                type="datetime-local"
                value={filterTo}
                onChange={(e) => setFilterTo(e.target.value)}
                className="px-3 py-1.5 border border-gray-200 rounded-md text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none"
              />
            </div>
          </div>
          <div className="flex justify-end mt-3 gap-2">
            {hasActiveFilters && (
              <button
                className="inline-flex items-center px-3 py-1.5 text-sm text-gray-500 hover:text-gray-700 transition-colors"
                onClick={clearFilters}
              >
                Clear filters
              </button>
            )}
            <button
              className="inline-flex items-center px-4 py-1.5 bg-blue-600 hover:bg-blue-700 text-white text-sm font-medium rounded-md transition-colors"
              onClick={loadEntries}
            >
              Apply
            </button>
          </div>
        </div>
      )}

      {loading ? (
        <div className="flex justify-center items-center py-20">
          <LoadingSpinner message="Loading audit log..." />
        </div>
      ) : (
        <div className="bg-white rounded-lg border border-gray-200 overflow-hidden">
          <DataTable
            columns={columns}
            data={entries}
            keyField="id"
            searchPlaceholder="Filter audit log..."
            emptyMessage="No audit entries"
            defaultSortKey="timestamp"
            defaultSortDir="desc"
          />
        </div>
      )}
    </div>
  );
}
