import { useState, useEffect, useCallback, useRef } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { api } from '../../api/client';
import LoadingSpinner from '../../components/LoadingSpinner';
import EmptyState from '../../components/EmptyState';
import FormatBadge from '../../components/FormatBadge';
import { useToast } from '../../components/Toast';
import DockerBrowseView from './DockerBrowseView';
import type { Component, PageResponse, Repository } from '../../types/api';

export default function BrowseRepositoryPage() {
  const { repositoryName } = useParams<{ repositoryName: string }>();
  const navigate = useNavigate();
  const { showToast } = useToast();

  const [components, setComponents] = useState<Component[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [searching, setSearching] = useState(false);
  const [continuationToken, setContinuationToken] = useState<string | null>(null);
  const [filter, setFilter] = useState('');
  const [repoFormat, setRepoFormat] = useState<string | null>(null);
  const [repoUrl, setRepoUrl] = useState<string | undefined>(undefined);
  const debounceTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  const fetchComponents = useCallback(
    (filterValue: string, append: boolean = false, token?: string | null) => {
      if (!repositoryName) return;

      const isLoadMore = append && token;
      if (isLoadMore) {
        setLoadingMore(true);
      } else if (filterValue) {
        setSearching(true);
      }

      let url = `/components?repository=${encodeURIComponent(repositoryName)}`;
      if (filterValue) {
        url += `&filter=${encodeURIComponent(filterValue)}`;
      }
      if (isLoadMore) {
        url += `&continuationToken=${encodeURIComponent(token!)}`;
      }

      api
        .get<PageResponse<Component>>(url)
        .then((res) => {
          if (append) {
            setComponents((prev) => [...prev, ...res.items]);
          } else {
            setComponents(res.items);
          }
          setContinuationToken(res.continuationToken);
        })
        .catch(() => {
          if (!append) {
            showToast('error', 'Failed to load components');
          } else {
            showToast('error', 'Failed to load more components');
          }
        })
        .finally(() => {
          setLoadingMore(false);
          setSearching(false);
        });
    },
    [repositoryName, showToast],
  );

  useEffect(() => {
    if (!repositoryName) return;

    // Fetch repository info (for format detection) and components in parallel
    const repoInfoPromise = api
      .get<Repository>(`/repositories/${encodeURIComponent(repositoryName)}`)
      .then((repo) => {
        setRepoFormat(repo.format);
        setRepoUrl(repo.url);
      })
      .catch(() => {
        // Non-critical: if we can't get repo info, just show default view
      });

    const componentsPromise = api
      .get<PageResponse<Component>>(`/components?repository=${encodeURIComponent(repositoryName)}`)
      .then((res) => {
        setComponents(res.items);
        setContinuationToken(res.continuationToken);
      })
      .catch(() => {
        showToast('error', 'Failed to load components');
        navigate('/browse');
      });

    Promise.all([repoInfoPromise, componentsPromise]).finally(() => setLoading(false));
  }, [repositoryName]); // eslint-disable-line react-hooks/exhaustive-deps

  const handleFilterChange = useCallback(
    (value: string) => {
      setFilter(value);

      if (debounceTimer.current) {
        clearTimeout(debounceTimer.current);
      }

      debounceTimer.current = setTimeout(() => {
        fetchComponents(value.trim());
      }, 300);
    },
    [fetchComponents],
  );

  // Clean up debounce timer on unmount
  useEffect(() => {
    return () => {
      if (debounceTimer.current) {
        clearTimeout(debounceTimer.current);
      }
    };
  }, []);

  const loadMore = useCallback(() => {
    if (!continuationToken || loadingMore) return;
    fetchComponents(filter.trim(), true, continuationToken);
  }, [continuationToken, loadingMore, filter, fetchComponents]);

  if (loading) {
    return (
      <div className="flex justify-center items-center py-20">
        <LoadingSpinner message="Loading components..." />
      </div>
    );
  }

  return (
    <div className="p-6 sm:p-8 max-w-7xl">
      <div className="flex items-start justify-between mb-6 gap-4">
        <div>
          <div className="flex items-center gap-3 mb-1">
            <button
              onClick={() => navigate('/browse')}
              className="text-gray-400 hover:text-blue-600 transition-colors"
              title="Back to repositories"
            >
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                <path d="M19 12H5" />
                <path d="M12 19l-7-7 7-7" />
              </svg>
            </button>
            <h1 className="text-2xl font-semibold text-gray-950">{repositoryName}</h1>
          </div>
          <p className="text-sm text-gray-500 mt-1 ml-8">
            {repoFormat === 'docker'
              ? `Docker registry`
              : `${components.length} component${components.length !== 1 ? 's' : ''}`}
          </p>
        </div>
      </div>

      {/* Docker-specific view */}
      {repoFormat === 'docker' ? (
        <DockerBrowseView repositoryName={repositoryName!} components={components} repositoryUrl={repoUrl} />
      ) : (
        <>
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
                placeholder="Search components..."
                value={filter}
                onChange={(e) => handleFilterChange(e.target.value)}
                className="w-full pl-10 pr-4 py-2 border border-gray-300 rounded-md text-sm text-gray-700 bg-white placeholder:text-gray-400 focus:outline-none focus:border-blue-600 focus:ring-1 focus:ring-blue-600 transition-colors"
              />
              {searching && (
                <div className="absolute right-3 top-1/2 -translate-y-1/2">
                  <svg className="animate-spin h-4 w-4 text-gray-400" viewBox="0 0 24 24">
                    <circle
                      className="opacity-25"
                      cx="12"
                      cy="12"
                      r="10"
                      stroke="currentColor"
                      strokeWidth="4"
                      fill="none"
                    />
                    <path
                      className="opacity-75"
                      fill="currentColor"
                      d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z"
                    />
                  </svg>
                </div>
              )}
            </div>
          </div>

          {components.length === 0 ? (
            <div className="bg-white rounded-lg border border-gray-200 overflow-hidden">
              <EmptyState
                title="No components found"
                description={filter ? 'Try a different search term' : 'This repository has no components yet'}
              />
            </div>
          ) : (
            <>
              <div className="bg-white rounded-lg border border-gray-200 overflow-hidden">
                <table className="w-full text-sm">
                  <thead>
                    <tr className="border-b border-gray-200 bg-gray-50">
                      <th className="text-left px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wider">
                        Component
                      </th>
                      <th className="text-left px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wider">
                        Version
                      </th>
                      <th className="text-left px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wider">
                        Format
                      </th>
                      <th className="text-left px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wider">
                        Assets
                      </th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-gray-100">
                    {components.map((component) => (
                      <tr
                        key={component.id}
                        className="hover:bg-gray-50 cursor-pointer transition-colors"
                        onClick={() =>
                          navigate(`/browse/${encodeURIComponent(repositoryName!)}/components/${component.id}`)
                        }
                      >
                        <td className="px-4 py-3">
                          <div className="font-medium text-gray-900">
                            {component.group ? `${component.group} / ` : ''}
                            {component.name}
                          </div>
                        </td>
                        <td className="px-4 py-3">
                          <span className="font-mono text-xs text-gray-600 bg-gray-100 px-1.5 py-0.5 rounded">
                            {component.version || '-'}
                          </span>
                        </td>
                        <td className="px-4 py-3">
                          <FormatBadge format={component.format} />
                        </td>
                        <td className="px-4 py-3">
                          <span className="text-gray-500">{component.assets.length}</span>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>

              {continuationToken && (
                <div className="flex justify-center mt-5">
                  <button
                    onClick={loadMore}
                    disabled={loadingMore}
                    className="inline-flex items-center gap-2 px-4 py-2 bg-white border border-gray-200 text-sm font-medium text-gray-700 rounded-md hover:bg-gray-50 transition-colors disabled:opacity-50"
                  >
                    {loadingMore ? (
                      <>
                        <svg className="animate-spin h-4 w-4" viewBox="0 0 24 24">
                          <circle
                            className="opacity-25"
                            cx="12"
                            cy="12"
                            r="10"
                            stroke="currentColor"
                            strokeWidth="4"
                            fill="none"
                          />
                          <path
                            className="opacity-75"
                            fill="currentColor"
                            d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z"
                          />
                        </svg>
                        Loading...
                      </>
                    ) : (
                      'Load more'
                    )}
                  </button>
                </div>
              )}
            </>
          )}
        </>
      )}
    </div>
  );
}
