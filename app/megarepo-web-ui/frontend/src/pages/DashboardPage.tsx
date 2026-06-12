import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { api } from '../api/client';
import LoadingSpinner from '../components/LoadingSpinner';
import type { SystemMetrics, Repository, StatusCheck, LicenseStatus } from '../types/api';

function formatBytes(bytes: number): string {
  if (bytes === 0) return '0 B';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  const i = Math.floor(Math.log(bytes) / Math.log(1024));
  return `${(bytes / Math.pow(1024, i)).toFixed(1)} ${units[i]}`;
}

/* ── SVG Icons ─────────────────────────────────────────────────────── */

function ArchiveBoxIcon({ className = '' }: { className?: string }) {
  return (
    <svg className={className} fill="none" viewBox="0 0 24 24" strokeWidth={1.5} stroke="currentColor" width="20" height="20">
      <path strokeLinecap="round" strokeLinejoin="round" d="M20.25 7.5l-.625 10.632a2.25 2.25 0 01-2.247 2.118H6.622a2.25 2.25 0 01-2.247-2.118L3.75 7.5M10 11.25h4M3.375 7.5h17.25c.621 0 1.125-.504 1.125-1.125v-1.5c0-.621-.504-1.125-1.125-1.125H3.375c-.621 0-1.125.504-1.125 1.125v1.5c0 .621.504 1.125 1.125 1.125z" />
    </svg>
  );
}

function CubeIcon({ className = '' }: { className?: string }) {
  return (
    <svg className={className} fill="none" viewBox="0 0 24 24" strokeWidth={1.5} stroke="currentColor" width="20" height="20">
      <path strokeLinecap="round" strokeLinejoin="round" d="M21 7.5l-9-5.25L3 7.5m18 0l-9 5.25m9-5.25v9l-9 5.25M3 7.5l9 5.25M3 7.5v9l9 5.25m0-9v9" />
    </svg>
  );
}

function ServerIcon({ className = '' }: { className?: string }) {
  return (
    <svg className={className} fill="none" viewBox="0 0 24 24" strokeWidth={1.5} stroke="currentColor" width="20" height="20">
      <path strokeLinecap="round" strokeLinejoin="round" d="M21.75 17.25v-.228a4.5 4.5 0 00-.12-1.03l-2.268-9.64a3.375 3.375 0 00-3.285-2.602H7.923a3.375 3.375 0 00-3.285 2.602l-2.268 9.64a4.5 4.5 0 00-.12 1.03v.228m19.5 0a3 3 0 01-3 3H5.25a3 3 0 01-3-3m19.5 0a3 3 0 00-3-3H5.25a3 3 0 00-3 3m16.5 0h.008v.008h-.008v-.008zm-3 0h.008v.008h-.008v-.008z" />
    </svg>
  );
}

function CheckCircleIcon({ className = '' }: { className?: string }) {
  return (
    <svg className={className} fill="none" viewBox="0 0 24 24" strokeWidth={1.5} stroke="currentColor" width="20" height="20">
      <path strokeLinecap="round" strokeLinejoin="round" d="M9 12.75L11.25 15 15 9.75M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
    </svg>
  );
}

function ExclamationTriangleIcon({ className = '' }: { className?: string }) {
  return (
    <svg className={className} fill="none" viewBox="0 0 24 24" strokeWidth={1.5} stroke="currentColor" width="20" height="20">
      <path strokeLinecap="round" strokeLinejoin="round" d="M12 9v3.75m-9.303 3.376c-.866 1.5.217 3.374 1.948 3.374h14.71c1.73 0 2.813-1.874 1.948-3.374L13.949 3.378c-.866-1.5-3.032-1.5-3.898 0L2.697 16.126zM12 15.75h.007v.008H12v-.008z" />
    </svg>
  );
}

function FolderOpenIcon({ className = '' }: { className?: string }) {
  return (
    <svg className={className} fill="none" viewBox="0 0 24 24" strokeWidth={1.5} stroke="currentColor" width="20" height="20">
      <path strokeLinecap="round" strokeLinejoin="round" d="M3.75 9.776c.112-.017.227-.026.344-.026h15.812c.117 0 .232.009.344.026m-16.5 0a2.25 2.25 0 00-1.883 2.542l.857 6a2.25 2.25 0 002.227 1.932H19.05a2.25 2.25 0 002.227-1.932l.857-6a2.25 2.25 0 00-1.883-2.542m-16.5 0V6A2.25 2.25 0 016 3.75h3.879a1.5 1.5 0 011.06.44l2.122 2.12a1.5 1.5 0 001.06.44H18A2.25 2.25 0 0120.25 9v.776" />
    </svg>
  );
}

function MagnifyingGlassIcon({ className = '' }: { className?: string }) {
  return (
    <svg className={className} fill="none" viewBox="0 0 24 24" strokeWidth={1.5} stroke="currentColor" width="20" height="20">
      <path strokeLinecap="round" strokeLinejoin="round" d="M21 21l-5.197-5.197m0 0A7.5 7.5 0 105.196 5.196a7.5 7.5 0 0010.607 10.607z" />
    </svg>
  );
}

function PlusIcon({ className = '' }: { className?: string }) {
  return (
    <svg className={className} fill="none" viewBox="0 0 24 24" strokeWidth={1.5} stroke="currentColor" width="20" height="20">
      <path strokeLinecap="round" strokeLinejoin="round" d="M12 4.5v15m7.5-7.5h-15" />
    </svg>
  );
}

function ChevronRightIcon() {
  return (
    <svg className="text-gray-400 shrink-0" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
      <path strokeLinecap="round" strokeLinejoin="round" d="M8.25 4.5l7.5 7.5-7.5 7.5" />
    </svg>
  );
}

/* ── Stat Card ─────────────────────────────────────────────────────── */

interface StatCardProps {
  icon: React.ReactNode;
  iconBgClass: string;
  label: string;
  value: string | number;
  sub?: string;
}

function StatCard({ icon, iconBgClass, label, value, sub }: StatCardProps) {
  return (
    <div className="bg-white rounded-xl border border-gray-200 shadow-sm p-5 flex items-center gap-4">
      <div className={`w-10 h-10 rounded-lg flex items-center justify-center shrink-0 ${iconBgClass}`}>
        {icon}
      </div>
      <div className="min-w-0">
        <div className="text-2xl font-semibold text-gray-950 leading-tight">{value}</div>
        <div className="text-sm text-gray-500 mt-0.5">{label}</div>
        {sub && <div className="text-xs text-gray-400 mt-0.5">{sub}</div>}
      </div>
    </div>
  );
}

/* ── Quick Action ──────────────────────────────────────────────────── */

interface QuickActionProps {
  icon: React.ReactNode;
  title: string;
  description: string;
  onClick: () => void;
}

function QuickAction({ icon, title, description, onClick }: QuickActionProps) {
  return (
    <button
      className="flex items-center gap-4 bg-white border border-gray-200 rounded-lg px-5 py-4 cursor-pointer transition-all text-left w-full hover:border-blue-300 hover:shadow-md group"
      onClick={onClick}
    >
      <div className="w-10 h-10 flex items-center justify-center bg-gray-100 rounded-lg shrink-0 text-gray-500 group-hover:bg-blue-50 group-hover:text-blue-600 transition-colors">
        {icon}
      </div>
      <div className="flex-1 min-w-0">
        <div className="font-medium text-sm text-gray-900">{title}</div>
        <div className="text-xs text-gray-500 mt-0.5">{description}</div>
      </div>
      <ChevronRightIcon />
    </button>
  );
}

/* ── Dashboard Page ────────────────────────────────────────────────── */

export default function DashboardPage() {
  const navigate = useNavigate();
  const [metrics, setMetrics] = useState<SystemMetrics | null>(null);
  const [repos, setRepos] = useState<Repository[]>([]);
  const [status, setStatus] = useState<StatusCheck | null>(null);
  const [license, setLicense] = useState<LicenseStatus | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    Promise.all([
      api.get<SystemMetrics>('/metrics').catch(() => null),
      api.get<Repository[]>('/repositories').catch(() => []),
      api.get<StatusCheck>('/status/check').catch(() => null),
      api.get<LicenseStatus>('/system/license').catch(() => null),
    ]).then(([m, r, s, l]) => {
      setMetrics(m);
      setRepos(r);
      setStatus(s);
      setLicense(l);
      setLoading(false);
    });
  }, []);

  if (loading) {
    return (
      <div className="flex justify-center items-center py-20">
        <LoadingSpinner message="Loading dashboard..." />
      </div>
    );
  }

  const totals = metrics?.totals;
  const healthOk = status?.status === 'UP';

  return (
    <div className="p-6 sm:p-8 max-w-7xl">
      <div className="flex items-start justify-between mb-6 gap-4">
        <div>
          <h1 className="text-2xl font-semibold text-gray-950">Dashboard</h1>
          <p className="text-sm text-gray-500 mt-1">Overview of your MegaRepo instance</p>
        </div>
      </div>

      {license?.licensed && (
        <div className="bg-emerald-50 text-emerald-800 border border-emerald-200 rounded-lg px-5 py-3 text-sm font-medium mb-6">
          Licensed to {license.company}
        </div>
      )}

      {license?.requiresPurchase && (
        <div className="bg-amber-50 text-amber-800 border border-amber-200 rounded-lg px-5 py-3 text-sm font-medium mb-6 leading-relaxed">
          MegaRepo Community Edition -- {license.activeUsers} active users detected (limit: 50).{' '}
          <a href="https://bsnsoft.de/megarepo" target="_blank" rel="noopener noreferrer" className="font-semibold underline text-amber-800">
            Purchase a license -- 600 EUR/year per company
          </a>
        </div>
      )}

      <div className="grid grid-cols-1 sm:grid-cols-2 xl:grid-cols-4 gap-6 mb-8">
        <StatCard
          icon={<ArchiveBoxIcon className="text-blue-600" />}
          iconBgClass="bg-blue-50"
          label="Repositories"
          value={totals?.repositories ?? repos.length}
        />
        <StatCard
          icon={<CubeIcon className="text-purple-600" />}
          iconBgClass="bg-purple-50"
          label="Components"
          value={totals?.components ?? 0}
        />
        <StatCard
          icon={<ServerIcon className="text-amber-600" />}
          iconBgClass="bg-amber-50"
          label="Storage Used"
          value={totals ? formatBytes(totals.totalBlobSizeBytes) : '0 B'}
        />
        <StatCard
          icon={healthOk ? <CheckCircleIcon className="text-green-600" /> : <ExclamationTriangleIcon className="text-red-600" />}
          iconBgClass={healthOk ? 'bg-green-50' : 'bg-red-50'}
          label="System Health"
          value={healthOk ? 'Healthy' : 'Degraded'}
          sub={status ? `v${status.version}` : undefined}
        />
      </div>

      <h2 className="text-base font-semibold text-gray-900 mb-4">Quick Actions</h2>
      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        <QuickAction
          icon={<FolderOpenIcon className="w-5 h-5" />}
          title="Browse Repositories"
          description="Explore repository contents and assets"
          onClick={() => navigate('/browse')}
        />
        <QuickAction
          icon={<MagnifyingGlassIcon className="w-5 h-5" />}
          title="Search Components"
          description="Find components across all repositories"
          onClick={() => navigate('/search')}
        />
        <QuickAction
          icon={<PlusIcon className="w-5 h-5" />}
          title="Create Repository"
          description="Set up a new hosted, proxy, or group repository"
          onClick={() => navigate('/admin/repositories/create')}
        />
      </div>

      {repos.length > 0 && (
        <>
          <div className="flex items-center justify-between mt-8 mb-4">
            <h2 className="text-base font-semibold text-gray-900">Repositories</h2>
            <button
              className="inline-flex items-center px-3 py-1.5 bg-white border border-gray-200 text-gray-700 text-xs font-medium rounded-md hover:bg-gray-50 transition-colors"
              onClick={() => navigate('/admin/repositories')}
            >
              View All
            </button>
          </div>
          <div className="bg-white rounded-lg border border-gray-200 overflow-hidden overflow-x-auto">
            <table className="w-full border-collapse min-w-[600px]">
              <thead>
                <tr>
                  <th className="text-left px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wider bg-gray-50 border-b border-gray-200">Name</th>
                  <th className="text-left px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wider bg-gray-50 border-b border-gray-200">Format</th>
                  <th className="text-left px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wider bg-gray-50 border-b border-gray-200">Type</th>
                  <th className="text-left px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wider bg-gray-50 border-b border-gray-200">Status</th>
                  <th className="text-left px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wider bg-gray-50 border-b border-gray-200">URL</th>
                </tr>
              </thead>
              <tbody>
                {repos.slice(0, 8).map((repo) => (
                  <tr
                    key={repo.name}
                    className="cursor-pointer hover:bg-gray-50 border-b border-gray-100 last:border-b-0 transition-colors"
                    onClick={() => navigate(`/admin/repositories/${repo.name}`)}
                  >
                    <td className="px-4 py-3 text-sm">
                      <span className="font-medium text-gray-900">{repo.name}</span>
                    </td>
                    <td className="px-4 py-3 text-sm">
                      <span className={`badge badge-${formatBadgeVariant(repo.format)}`}>
                        {formatLabel(repo.format)}
                      </span>
                    </td>
                    <td className="px-4 py-3 text-sm">
                      <span className={`badge badge-${typeBadgeVariant(repo.type)}`}>
                        {repo.type.charAt(0).toUpperCase() + repo.type.slice(1)}
                      </span>
                    </td>
                    <td className="px-4 py-3 text-sm">
                      <span className="inline-flex items-center gap-1.5">
                        <span
                          className={`inline-block w-2 h-2 rounded-full ${repo.online ? 'bg-green-500' : 'bg-red-500'}`}
                        />
                        <span className="text-sm text-gray-700">{repo.online ? 'Online' : 'Offline'}</span>
                      </span>
                    </td>
                    <td className="px-4 py-3 text-xs font-mono text-gray-400 max-w-xs truncate">{repo.url}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}
    </div>
  );
}

function formatBadgeVariant(format: string): string {
  const map: Record<string, string> = { maven2: 'primary', pypi: 'success', npm: 'danger', nuget: 'info', raw: 'info' };
  return map[format] || 'default';
}

function formatLabel(format: string): string {
  if (format === 'maven2') return 'Maven';
  if (format === 'nuget') return 'NuGet';
  return format.charAt(0).toUpperCase() + format.slice(1);
}

function typeBadgeVariant(type: string): string {
  const map: Record<string, string> = { hosted: 'info', proxy: 'warning', group: 'success' };
  return map[type] || 'default';
}
