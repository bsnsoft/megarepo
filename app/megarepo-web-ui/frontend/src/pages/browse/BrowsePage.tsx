import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { api } from '../../api/client';
import LoadingSpinner from '../../components/LoadingSpinner';
import EmptyState from '../../components/EmptyState';
import FormatBadge from '../../components/FormatBadge';
import TypeBadge from '../../components/TypeBadge';
import { useToast } from '../../components/Toast';
import type { Repository } from '../../types/api';

export default function BrowsePage() {
  const navigate = useNavigate();
  const { showToast } = useToast();
  const [repos, setRepos] = useState<Repository[]>([]);
  const [loading, setLoading] = useState(true);
  const [filter, setFilter] = useState('');

  useEffect(() => {
    api
      .get<Repository[]>('/repositories')
      .then(setRepos)
      .catch(() => showToast('error', 'Failed to load repositories'))
      .finally(() => setLoading(false));
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  if (loading) {
    return (
      <div className="flex justify-center items-center py-20">
        <LoadingSpinner message="Loading repositories..." />
      </div>
    );
  }

  const filtered = repos.filter(
    (r) =>
      r.name.toLowerCase().includes(filter.toLowerCase()) ||
      r.format.toLowerCase().includes(filter.toLowerCase()),
  );

  return (
    <div className="p-6 sm:p-8 max-w-7xl">
      <div className="flex items-start justify-between mb-6 gap-4">
        <div>
          <h1 className="text-2xl font-semibold text-gray-950">Browse</h1>
          <p className="text-sm text-gray-500 mt-1">Explore repository contents</p>
        </div>
      </div>

      <div className="mb-5">
        <div className="relative max-w-md">
          <svg
            className="absolute left-3.5 top-1/2 -translate-y-1/2 text-gray-400"
            width="16"
            height="16"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="2"
          >
            <circle cx="11" cy="11" r="8" />
            <path d="M21 21l-4.35-4.35" />
          </svg>
          <input
            type="text"
            placeholder="Filter repositories..."
            value={filter}
            onChange={(e) => setFilter(e.target.value)}
            className="w-full pl-10 pr-4 py-2 border border-gray-300 rounded-md text-sm text-gray-700 bg-white placeholder:text-gray-400 focus:outline-none focus:border-blue-600 focus:ring-1 focus:ring-blue-600 transition-colors"
          />
        </div>
      </div>

      {filtered.length === 0 ? (
        <div className="bg-white rounded-lg border border-gray-200 overflow-hidden">
          <EmptyState
            title="No repositories found"
            description={filter ? 'Try a different filter' : 'Create a repository to start browsing artifacts.'}
            action={
              !filter ? (
                <button
                  className="inline-flex items-center gap-1.5 px-4 py-2 bg-blue-600 hover:bg-blue-700 text-white text-sm font-medium rounded-md transition-colors"
                  onClick={() => navigate('/admin/repositories/create')}
                >
                  Create Repository
                </button>
              ) : undefined
            }
          />
        </div>
      ) : (
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
          {filtered.map((repo) => (
            <button
              key={repo.name}
              className="bg-white border border-gray-200 rounded-lg p-5 text-left cursor-pointer transition-all hover:border-blue-300 hover:shadow-md w-full group"
              onClick={() => navigate(`/browse/${encodeURIComponent(repo.name)}`)}
            >
              <div className="flex gap-1.5 mb-3">
                <FormatBadge format={repo.format} />
                <TypeBadge type={repo.type} />
              </div>
              <div className="font-medium text-sm text-gray-900 mb-1 group-hover:text-blue-600 transition-colors">
                {repo.name}
              </div>
              <div className="font-mono text-[11px] text-gray-400 truncate">{repo.url}</div>
              <div className="flex items-center gap-1.5 mt-3">
                <span className={`inline-block w-2 h-2 rounded-full ${repo.online ? 'bg-green-500' : 'bg-red-500'}`} />
                <span className="text-xs text-gray-500">{repo.online ? 'Online' : 'Offline'}</span>
              </div>
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
