import { useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { api, NetworkError } from '../../api/client';
import { useToast } from '../../components/Toast';
import FormatBadge from '../../components/FormatBadge';
import TypeBadge from '../../components/TypeBadge';
import type { CreateRepositoryRequest, Repository, RepositoryFormat, RepositoryType, BlobStore } from '../../types/api';
import { useEffect } from 'react';

const FORMATS: { value: RepositoryFormat; label: string; description: string }[] = [
  { value: 'maven2', label: 'Maven', description: 'Java / JVM artifacts' },
  { value: 'pypi', label: 'PyPI', description: 'Python packages' },
  { value: 'npm', label: 'npm', description: 'JavaScript packages' },
  { value: 'raw', label: 'Raw', description: 'Generic file storage' },
];

const TYPES: { value: RepositoryType; label: string; description: string }[] = [
  { value: 'hosted', label: 'Hosted', description: 'Store your own artifacts' },
  { value: 'proxy', label: 'Proxy', description: 'Cache remote artifacts' },
  { value: 'group', label: 'Group', description: 'Combine multiple repositories' },
];

export default function RepositoryCreatePage() {
  const navigate = useNavigate();
  const { showToast } = useToast();

  const [step, setStep] = useState<'recipe' | 'form'>('recipe');
  const [selectedFormat, setSelectedFormat] = useState<RepositoryFormat>('maven2');
  const [selectedType, setSelectedType] = useState<RepositoryType>('hosted');

  const [name, setName] = useState('');
  const [online, setOnline] = useState(true);
  const [blobStoreName, setBlobStoreName] = useState('default');
  const [remoteUrl, setRemoteUrl] = useState('');
  const [blobStores, setBlobStores] = useState<BlobStore[]>([]);
  const [submitting, setSubmitting] = useState(false);

  // Proxy config
  const [cacheTtlMinutes, setCacheTtlMinutes] = useState('1440');
  const [metadataCacheTtlMinutes, setMetadataCacheTtlMinutes] = useState('5');
  const [proxyUsername, setProxyUsername] = useState('');
  const [proxyPassword, setProxyPassword] = useState('');

  // Group member picker state
  const [availableRepos, setAvailableRepos] = useState<Repository[]>([]);
  const [selectedMembers, setSelectedMembers] = useState<string[]>([]);

  useEffect(() => {
    api.get<BlobStore[]>('/blobstores').then(setBlobStores).catch(() => {});
  }, []);

  // Fetch available repositories when group type is selected (filter by same format, exclude group repos)
  useEffect(() => {
    if (step === 'form' && selectedType === 'group') {
      api.get<Repository[]>('/repositories').then((repos) => {
        const eligible = repos.filter(
          (r) => r.format.toLowerCase() === selectedFormat.toLowerCase() && r.type.toLowerCase() !== 'group',
        );
        setAvailableRepos(eligible);
      }).catch(() => {
        setAvailableRepos([]);
      });
    }
  }, [step, selectedType, selectedFormat]);

  function handleRecipeSelect(format: RepositoryFormat, type: RepositoryType) {
    setSelectedFormat(format);
    setSelectedType(type);
    setSelectedMembers([]);
    setStep('form');
    if (type === 'proxy') {
      const defaults: Record<string, string> = {
        maven2: 'https://repo1.maven.org/maven2/',
        pypi: 'https://pypi.org/simple/',
        npm: 'https://registry.npmjs.org/',
        raw: '',
      };
      setRemoteUrl(defaults[format] || '');
    }
  }

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    setSubmitting(true);

    const attributes: Record<string, unknown> = {};
    if (selectedType === 'proxy' && remoteUrl) {
      attributes['remoteUrl'] = remoteUrl;
      const proxyAttrs: Record<string, unknown> = {
        remoteUrl,
        cacheTtlMinutes: parseInt(cacheTtlMinutes, 10) || 1440,
        metadataCacheTtlMinutes: parseInt(metadataCacheTtlMinutes, 10) || 5,
      };
      if (proxyUsername.trim()) {
        proxyAttrs.username = proxyUsername.trim();
        proxyAttrs.password = proxyPassword;
      }
      attributes['proxy'] = proxyAttrs;
    }
    if (selectedType === 'group') {
      attributes['group'] = { memberNames: selectedMembers };
    }

    const req: CreateRepositoryRequest = {
      name,
      format: selectedFormat,
      type: selectedType.toUpperCase(),
      online,
      blobStoreName,
      attributes,
    };

    try {
      await api.post('/repositories', req);
      showToast('success', `Repository "${name}" created`);
      navigate('/admin/repositories');
    } catch (err: unknown) {
      const msg = err instanceof NetworkError
        ? 'Unable to connect to the server. Please check your network.'
        : err instanceof Error ? err.message
        : 'Failed to create repository';
      showToast('error', msg);
    } finally {
      setSubmitting(false);
    }
  }

  if (step === 'recipe') {
    return (
      <div className="p-6 sm:p-8 max-w-7xl">
        <div className="flex items-start justify-between mb-6 gap-4">
          <div>
            <h1 className="text-2xl font-semibold text-gray-950">Create Repository</h1>
            <p className="text-sm text-gray-500 mt-1">Select a recipe to get started</p>
          </div>
          <button
            className="inline-flex items-center px-4 py-2 bg-white border border-gray-200 text-gray-700 text-sm font-medium rounded-md hover:bg-gray-50 transition-colors"
            onClick={() => navigate('/admin/repositories')}
          >
            Cancel
          </button>
        </div>

        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-4">
          {FORMATS.map((format) =>
            TYPES.map((type) => (
              <button
                key={`${format.value}-${type.value}`}
                className="bg-white border border-gray-200 rounded-lg p-5 text-left cursor-pointer transition-all hover:border-blue-300 hover:shadow-md w-full group"
                onClick={() => handleRecipeSelect(format.value, type.value)}
              >
                <div className="flex gap-1.5 mb-2.5">
                  <FormatBadge format={format.value} />
                  <TypeBadge type={type.value} />
                </div>
                <div className="font-medium text-sm text-gray-900 mb-1 group-hover:text-blue-600 transition-colors">
                  {format.label} ({type.label})
                </div>
                <div className="text-xs text-gray-500">{type.description}</div>
              </button>
            )),
          )}
        </div>
      </div>
    );
  }

  return (
    <div className="p-6 sm:p-8 max-w-7xl">
      <div className="flex items-start justify-between mb-6 gap-4">
        <div>
          <h1 className="text-2xl font-semibold text-gray-950 flex items-center gap-2">
            Create Repository
            <FormatBadge format={selectedFormat} />
            <TypeBadge type={selectedType} />
          </h1>
          <p className="text-sm text-gray-500 mt-1">Configure your new repository</p>
        </div>
        <button
          className="inline-flex items-center px-4 py-2 bg-white border border-gray-200 text-gray-700 text-sm font-medium rounded-md hover:bg-gray-50 transition-colors"
          onClick={() => setStep('recipe')}
        >
          Back to Recipes
        </button>
      </div>

      <div className="bg-white rounded-lg border border-gray-200 overflow-hidden">
        <div className="p-6">
          <form onSubmit={handleSubmit}>
            <div className="mb-5">
              <label htmlFor="repo-name" className="block text-sm font-medium text-gray-700 mb-1.5">
                Repository Name
              </label>
              <input
                id="repo-name"
                type="text"
                value={name}
                onChange={(e) => setName(e.target.value)}
                placeholder="e.g., maven-releases"
                pattern="[a-zA-Z0-9_-]+"
                required
                autoFocus
                className="w-full max-w-sm px-3 py-2 border border-gray-300 rounded-md text-sm text-gray-700 bg-white placeholder:text-gray-400 focus:outline-none focus:border-blue-600 focus:ring-1 focus:ring-blue-600 transition-colors"
              />
              <span className="text-xs text-gray-500 mt-1 block">Only letters, numbers, hyphens, and underscores</span>
            </div>

            <div className="mb-5">
              <label htmlFor="blob-store" className="block text-sm font-medium text-gray-700 mb-1.5">
                Blob Store
              </label>
              <select
                id="blob-store"
                value={blobStoreName}
                onChange={(e) => setBlobStoreName(e.target.value)}
                className="w-full max-w-sm px-3 py-2 border border-gray-300 rounded-md text-sm text-gray-700 bg-white focus:outline-none focus:border-blue-600 focus:ring-1 focus:ring-blue-600 transition-colors"
              >
                {blobStores.length === 0 && <option value="default">default</option>}
                {blobStores.map((bs) => (
                  <option key={bs.name} value={bs.name}>
                    {bs.name} ({bs.type})
                  </option>
                ))}
              </select>
            </div>

            {selectedType === 'proxy' && (
              <>
                <div className="mb-5">
                  <label htmlFor="remote-url" className="block text-sm font-medium text-gray-700 mb-1.5">
                    Remote URL
                  </label>
                  <input
                    id="remote-url"
                    type="url"
                    value={remoteUrl}
                    onChange={(e) => setRemoteUrl(e.target.value)}
                    placeholder="https://repo1.maven.org/maven2/"
                    required
                    className="w-full max-w-lg px-3 py-2 border border-gray-300 rounded-md text-sm text-gray-700 bg-white placeholder:text-gray-400 focus:outline-none focus:border-blue-600 focus:ring-1 focus:ring-blue-600 transition-colors"
                  />
                </div>

                {/* Cache TTL settings */}
                <div className="mb-5 border border-gray-200 rounded-md p-4">
                  <h3 className="text-sm font-medium text-gray-700 mb-3">Cache Settings</h3>
                  <div className="grid grid-cols-1 sm:grid-cols-2 gap-4 max-w-lg">
                    <div>
                      <label htmlFor="cache-ttl" className="block text-xs font-medium text-gray-600 mb-1">
                        Content Cache TTL (minutes)
                      </label>
                      <input
                        id="cache-ttl"
                        type="number"
                        min="0"
                        value={cacheTtlMinutes}
                        onChange={(e) => setCacheTtlMinutes(e.target.value)}
                        className="w-full px-3 py-2 border border-gray-300 rounded-md text-sm text-gray-700 bg-white focus:outline-none focus:border-blue-600 focus:ring-1 focus:ring-blue-600 transition-colors"
                      />
                      <span className="text-xs text-gray-400 mt-0.5 block">
                        Default: 1440 (24 hours). How long cached artifacts are fresh.
                      </span>
                    </div>
                    <div>
                      <label htmlFor="metadata-cache-ttl" className="block text-xs font-medium text-gray-600 mb-1">
                        Metadata Cache TTL (minutes)
                      </label>
                      <input
                        id="metadata-cache-ttl"
                        type="number"
                        min="0"
                        value={metadataCacheTtlMinutes}
                        onChange={(e) => setMetadataCacheTtlMinutes(e.target.value)}
                        className="w-full px-3 py-2 border border-gray-300 rounded-md text-sm text-gray-700 bg-white focus:outline-none focus:border-blue-600 focus:ring-1 focus:ring-blue-600 transition-colors"
                      />
                      <span className="text-xs text-gray-400 mt-0.5 block">
                        Default: 5 min. For index/metadata files.
                      </span>
                    </div>
                  </div>
                </div>

                {/* Upstream authentication */}
                <div className="mb-5 border border-gray-200 rounded-md p-4">
                  <h3 className="text-sm font-medium text-gray-700 mb-1">Upstream Authentication</h3>
                  <p className="text-xs text-gray-400 mb-3">
                    Credentials for private upstream registries (e.g., private npm, PyPI, Artifactory).
                  </p>
                  <div className="grid grid-cols-1 sm:grid-cols-2 gap-4 max-w-lg">
                    <div>
                      <label htmlFor="proxy-username" className="block text-xs font-medium text-gray-600 mb-1">
                        Username
                      </label>
                      <input
                        id="proxy-username"
                        type="text"
                        value={proxyUsername}
                        onChange={(e) => setProxyUsername(e.target.value)}
                        placeholder="Optional"
                        autoComplete="off"
                        className="w-full px-3 py-2 border border-gray-300 rounded-md text-sm text-gray-700 bg-white placeholder:text-gray-400 focus:outline-none focus:border-blue-600 focus:ring-1 focus:ring-blue-600 transition-colors"
                      />
                    </div>
                    <div>
                      <label htmlFor="proxy-password" className="block text-xs font-medium text-gray-600 mb-1">
                        Password
                      </label>
                      <input
                        id="proxy-password"
                        type="password"
                        value={proxyPassword}
                        onChange={(e) => setProxyPassword(e.target.value)}
                        placeholder="Optional"
                        autoComplete="new-password"
                        className="w-full px-3 py-2 border border-gray-300 rounded-md text-sm text-gray-700 bg-white placeholder:text-gray-400 focus:outline-none focus:border-blue-600 focus:ring-1 focus:ring-blue-600 transition-colors"
                      />
                    </div>
                  </div>
                </div>
              </>
            )}

            {selectedType === 'group' && (
              <div className="mb-5">
                <label className="block text-sm font-medium text-gray-700 mb-1.5">
                  Group Members
                </label>
                <p className="text-xs text-gray-500 mb-3">
                  Select repositories to include in this group. Only {selectedFormat} hosted and proxy repositories are shown.
                </p>
                {availableRepos.length === 0 ? (
                  <div className="text-sm text-gray-500 bg-gray-50 border border-gray-200 rounded-md px-4 py-3">
                    No eligible {selectedFormat} repositories found. Create hosted or proxy repositories first.
                  </div>
                ) : (
                  <div className="flex gap-4 max-w-2xl">
                    {/* Available repos */}
                    <div className="flex-1">
                      <div className="text-xs font-medium text-gray-500 mb-1.5">Available</div>
                      <div className="border border-gray-200 rounded-md max-h-48 overflow-y-auto">
                        {availableRepos
                          .filter((r) => !selectedMembers.includes(r.name))
                          .map((r) => (
                            <button
                              key={r.name}
                              type="button"
                              className="w-full flex items-center justify-between px-3 py-2 text-sm text-left hover:bg-blue-50 border-b border-gray-100 last:border-b-0 transition-colors"
                              onClick={() => setSelectedMembers((prev) => [...prev, r.name])}
                            >
                              <span className="text-gray-700">{r.name}</span>
                              <span className="text-gray-400 text-xs">
                                <TypeBadge type={r.type} />
                              </span>
                            </button>
                          ))}
                        {availableRepos.filter((r) => !selectedMembers.includes(r.name)).length === 0 && (
                          <div className="px-3 py-2 text-xs text-gray-400 text-center">All added</div>
                        )}
                      </div>
                    </div>
                    {/* Selected members (ordered) */}
                    <div className="flex-1">
                      <div className="text-xs font-medium text-gray-500 mb-1.5">
                        Members ({selectedMembers.length})
                      </div>
                      <div className="border border-gray-200 rounded-md max-h-48 overflow-y-auto">
                        {selectedMembers.map((memberName, idx) => (
                          <div
                            key={memberName}
                            className="flex items-center justify-between px-3 py-2 text-sm border-b border-gray-100 last:border-b-0 bg-blue-50/50"
                          >
                            <span className="flex items-center gap-2 text-gray-700">
                              <span className="text-xs text-gray-400 font-mono w-4">{idx + 1}</span>
                              {memberName}
                            </span>
                            <button
                              type="button"
                              className="text-gray-400 hover:text-red-500 transition-colors text-xs"
                              onClick={() =>
                                setSelectedMembers((prev) => prev.filter((n) => n !== memberName))
                              }
                            >
                              Remove
                            </button>
                          </div>
                        ))}
                        {selectedMembers.length === 0 && (
                          <div className="px-3 py-2 text-xs text-gray-400 text-center">
                            Click repositories on the left to add them
                          </div>
                        )}
                      </div>
                    </div>
                  </div>
                )}
              </div>
            )}

            <div className="mb-5">
              <label className="flex items-center gap-2.5 cursor-pointer">
                <input
                  type="checkbox"
                  checked={online}
                  onChange={(e) => setOnline(e.target.checked)}
                  className="w-4 h-4 rounded border-gray-300 text-blue-600 focus:ring-blue-600"
                />
                <span className="text-sm font-medium text-gray-700">Online - Accept incoming requests</span>
              </label>
            </div>

            <div className="flex justify-end gap-3 pt-5 mt-5 border-t border-gray-200">
              <button
                className="inline-flex items-center px-4 py-2 bg-white border border-gray-200 text-gray-700 text-sm font-medium rounded-md hover:bg-gray-50 transition-colors"
                type="button"
                onClick={() => navigate('/admin/repositories')}
              >
                Cancel
              </button>
              <button
                className="inline-flex items-center gap-1.5 px-4 py-2 bg-blue-600 hover:bg-blue-700 text-white text-sm font-medium rounded-md transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                type="submit"
                disabled={submitting}
              >
                {submitting ? 'Creating...' : 'Create Repository'}
              </button>
            </div>
          </form>
        </div>
      </div>
    </div>
  );
}
