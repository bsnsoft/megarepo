import { useState, useEffect } from 'react';
import { useSearchParams } from 'react-router-dom';
import { api } from '../../api/client';
import LoadingSpinner from '../../components/LoadingSpinner';
import EmptyState from '../../components/EmptyState';
import FormatBadge from '../../components/FormatBadge';
import { useToast } from '../../components/Toast';
import type { PageResponse, Component } from '../../types/api';

type SearchTab = 'keyword' | 'maven' | 'npm' | 'pypi';

interface MavenFields {
  groupId: string;
  artifactId: string;
  version: string;
}

interface NpmFields {
  scope: string;
  packageName: string;
}

interface PypiFields {
  packageName: string;
  classifier: string;
}

export default function SearchPage() {
  const { showToast } = useToast();
  const [searchParams, setSearchParams] = useSearchParams();
  const initialQuery = searchParams.get('q') || '';
  const [query, setQuery] = useState(initialQuery);
  const [results, setResults] = useState<Component[]>([]);
  const [loading, setLoading] = useState(false);
  const [searched, setSearched] = useState(false);
  const [activeTab, setActiveTab] = useState<SearchTab>('keyword');

  const [mavenFields, setMavenFields] = useState<MavenFields>({ groupId: '', artifactId: '', version: '' });
  const [npmFields, setNpmFields] = useState<NpmFields>({ scope: '', packageName: '' });
  const [pypiFields, setPypiFields] = useState<PypiFields>({ packageName: '', classifier: '' });

  useEffect(() => {
    if (initialQuery) {
      doSearch(initialQuery);
    }
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  function buildQuery(): string {
    if (activeTab === 'keyword') return query;

    if (activeTab === 'maven') {
      const parts: string[] = [];
      if (mavenFields.groupId.trim()) parts.push(`group:${mavenFields.groupId.trim()}`);
      if (mavenFields.artifactId.trim()) parts.push(`name:${mavenFields.artifactId.trim()}`);
      if (mavenFields.version.trim()) parts.push(`version:${mavenFields.version.trim()}`);
      return parts.join(' ');
    }

    if (activeTab === 'npm') {
      const parts: string[] = [];
      if (npmFields.scope.trim()) {
        const scope = npmFields.scope.trim().startsWith('@')
          ? npmFields.scope.trim()
          : `@${npmFields.scope.trim()}`;
        parts.push(`group:${scope}`);
      }
      if (npmFields.packageName.trim()) parts.push(`name:${npmFields.packageName.trim()}`);
      return parts.join(' ');
    }

    if (activeTab === 'pypi') {
      const parts: string[] = [];
      if (pypiFields.packageName.trim()) parts.push(`name:${pypiFields.packageName.trim()}`);
      if (pypiFields.classifier.trim()) parts.push(`classifier:${pypiFields.classifier.trim()}`);
      return parts.join(' ');
    }

    return query;
  }

  function getFormatParam(): string | null {
    switch (activeTab) {
      case 'maven': return 'maven2';
      case 'npm': return 'npm';
      case 'pypi': return 'pypi';
      default: return null;
    }
  }

  async function doSearch(q: string, format?: string | null) {
    if (!q.trim()) return;
    setLoading(true);
    setSearched(true);
    setSearchParams({ q });
    try {
      let url = `/search?q=${encodeURIComponent(q)}`;
      if (format) url += `&format=${encodeURIComponent(format)}`;
      const res = await api.get<PageResponse<Component>>(url);
      setResults(res.items);
    } catch {
      setResults([]);
      showToast('error', 'Search failed');
    } finally {
      setLoading(false);
    }
  }

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    const q = buildQuery();
    if (!q.trim()) return;
    doSearch(q, getFormatParam());
  }

  const tabs: { key: SearchTab; label: string }[] = [
    { key: 'keyword', label: 'Keyword' },
    { key: 'maven', label: 'Maven' },
    { key: 'npm', label: 'npm' },
    { key: 'pypi', label: 'PyPI' },
  ];

  const inputClass =
    'w-full px-3 py-2 border border-gray-300 rounded-md text-sm text-gray-700 bg-white placeholder:text-gray-400 focus:outline-none focus:border-blue-600 focus:ring-1 focus:ring-blue-600 transition-colors';

  const labelClass = 'block text-xs font-medium text-gray-600 mb-1';

  return (
    <div className="p-6 sm:p-8 max-w-7xl">
      <div className="flex items-start justify-between mb-6 gap-4">
        <div>
          <h1 className="text-2xl font-semibold text-gray-950">Search</h1>
          <p className="text-sm text-gray-500 mt-1">Find components across all repositories</p>
        </div>
      </div>

      <div className="bg-white rounded-lg border border-gray-200 overflow-hidden mb-6">
        <div className="flex border-b border-gray-200 overflow-x-auto">
          {tabs.map((tab) => (
            <button
              key={tab.key}
              className={`px-4 py-2.5 text-sm font-medium transition-colors relative whitespace-nowrap ${
                activeTab === tab.key
                  ? 'text-blue-600 border-b-2 border-blue-600'
                  : 'text-gray-500 hover:text-gray-700 border-b-2 border-transparent hover:border-gray-300'
              }`}
              onClick={() => setActiveTab(tab.key)}
            >
              {tab.label}
            </button>
          ))}
        </div>
        <div className="p-5">
          <form onSubmit={handleSubmit}>
            {activeTab === 'keyword' && (
              <div className="flex gap-0">
                <div className="relative flex-1">
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
                    value={query}
                    onChange={(e) => setQuery(e.target.value)}
                    placeholder="Search by component name, group, or keyword..."
                    className="w-full pl-10 pr-4 py-2 border border-gray-300 border-r-0 rounded-l-md text-sm text-gray-700 bg-white placeholder:text-gray-400 focus:outline-none focus:border-blue-600 focus:ring-1 focus:ring-blue-600 transition-colors"
                    autoFocus
                  />
                </div>
                <button
                  type="submit"
                  className="inline-flex items-center gap-1.5 px-4 py-2 bg-blue-600 hover:bg-blue-700 text-white text-sm font-medium rounded-r-md border border-blue-600 transition-colors disabled:opacity-50"
                  disabled={loading}
                >
                  Search
                </button>
              </div>
            )}

            {activeTab === 'maven' && (
              <div className="space-y-3">
                <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
                  <div>
                    <label className={labelClass}>Group ID</label>
                    <input
                      type="text"
                      value={mavenFields.groupId}
                      onChange={(e) => setMavenFields({ ...mavenFields, groupId: e.target.value })}
                      placeholder="com.google.guava"
                      className={inputClass}
                      autoFocus
                    />
                  </div>
                  <div>
                    <label className={labelClass}>Artifact ID</label>
                    <input
                      type="text"
                      value={mavenFields.artifactId}
                      onChange={(e) => setMavenFields({ ...mavenFields, artifactId: e.target.value })}
                      placeholder="guava"
                      className={inputClass}
                    />
                  </div>
                  <div>
                    <label className={labelClass}>Version</label>
                    <input
                      type="text"
                      value={mavenFields.version}
                      onChange={(e) => setMavenFields({ ...mavenFields, version: e.target.value })}
                      placeholder="33.0.0-jre"
                      className={inputClass}
                    />
                  </div>
                </div>
                <div className="flex justify-end">
                  <button
                    type="submit"
                    className="inline-flex items-center gap-1.5 px-4 py-2 bg-blue-600 hover:bg-blue-700 text-white text-sm font-medium rounded-md border border-blue-600 transition-colors disabled:opacity-50"
                    disabled={loading}
                  >
                    Search Maven
                  </button>
                </div>
              </div>
            )}

            {activeTab === 'npm' && (
              <div className="space-y-3">
                <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                  <div>
                    <label className={labelClass}>Scope</label>
                    <input
                      type="text"
                      value={npmFields.scope}
                      onChange={(e) => setNpmFields({ ...npmFields, scope: e.target.value })}
                      placeholder="@angular"
                      className={inputClass}
                      autoFocus
                    />
                  </div>
                  <div>
                    <label className={labelClass}>Package Name</label>
                    <input
                      type="text"
                      value={npmFields.packageName}
                      onChange={(e) => setNpmFields({ ...npmFields, packageName: e.target.value })}
                      placeholder="core"
                      className={inputClass}
                    />
                  </div>
                </div>
                <div className="flex justify-end">
                  <button
                    type="submit"
                    className="inline-flex items-center gap-1.5 px-4 py-2 bg-blue-600 hover:bg-blue-700 text-white text-sm font-medium rounded-md border border-blue-600 transition-colors disabled:opacity-50"
                    disabled={loading}
                  >
                    Search npm
                  </button>
                </div>
              </div>
            )}

            {activeTab === 'pypi' && (
              <div className="space-y-3">
                <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                  <div>
                    <label className={labelClass}>Package Name</label>
                    <input
                      type="text"
                      value={pypiFields.packageName}
                      onChange={(e) => setPypiFields({ ...pypiFields, packageName: e.target.value })}
                      placeholder="requests"
                      className={inputClass}
                      autoFocus
                    />
                  </div>
                  <div>
                    <label className={labelClass}>Classifier</label>
                    <input
                      type="text"
                      value={pypiFields.classifier}
                      onChange={(e) => setPypiFields({ ...pypiFields, classifier: e.target.value })}
                      placeholder="Programming Language :: Python :: 3"
                      className={inputClass}
                    />
                  </div>
                </div>
                <div className="flex justify-end">
                  <button
                    type="submit"
                    className="inline-flex items-center gap-1.5 px-4 py-2 bg-blue-600 hover:bg-blue-700 text-white text-sm font-medium rounded-md border border-blue-600 transition-colors disabled:opacity-50"
                    disabled={loading}
                  >
                    Search PyPI
                  </button>
                </div>
              </div>
            )}
          </form>
        </div>
      </div>

      {loading && (
        <div className="flex justify-center items-center py-16">
          <LoadingSpinner message="Searching..." />
        </div>
      )}

      {!loading && searched && results.length === 0 && (
        <div className="bg-white rounded-lg border border-gray-200 overflow-hidden">
          <EmptyState
            icon={
              <svg className="mx-auto h-12 w-12 text-gray-300" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
                <path strokeLinecap="round" strokeLinejoin="round" d="M21 21l-5.197-5.197m0 0A7.5 7.5 0 105.196 5.196a7.5 7.5 0 0010.607 10.607z" />
              </svg>
            }
            title="No results found"
            description={`No components matching "${searchParams.get('q')}" were found`}
          />
        </div>
      )}

      {!loading && results.length > 0 && (
        <div className="bg-white rounded-lg border border-gray-200 overflow-hidden">
          <div className="px-4 py-3 bg-gray-50 border-b border-gray-200 text-sm font-medium text-gray-700">
            {results.length} result{results.length !== 1 ? 's' : ''}
          </div>
          <div className="overflow-x-auto">
          <table className="w-full min-w-[500px]">
            <thead>
              <tr>
                <th className="text-left px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wider bg-gray-50 border-b border-gray-200">
                  Component
                </th>
                <th className="text-left px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wider bg-gray-50 border-b border-gray-200">
                  Version
                </th>
                <th className="text-left px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wider bg-gray-50 border-b border-gray-200">
                  Repository
                </th>
                <th className="text-left px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wider bg-gray-50 border-b border-gray-200">
                  Format
                </th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {results.map((comp) => (
                <tr key={comp.id} className="hover:bg-gray-50 transition-colors">
                  <td className="px-4 py-3 text-sm">
                    <div>
                      <span className="font-medium text-gray-900">{comp.name}</span>
                    </div>
                    {comp.group && <div className="text-xs text-gray-500 mt-0.5">{comp.group}</div>}
                  </td>
                  <td className="px-4 py-3 text-sm">
                    <code className="text-xs font-mono bg-gray-100 text-gray-600 px-1.5 py-0.5 rounded">
                      {comp.version}
                    </code>
                  </td>
                  <td className="px-4 py-3 text-sm text-gray-700">{comp.repository}</td>
                  <td className="px-4 py-3 text-sm">
                    <FormatBadge format={comp.format} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          </div>
        </div>
      )}
    </div>
  );
}
