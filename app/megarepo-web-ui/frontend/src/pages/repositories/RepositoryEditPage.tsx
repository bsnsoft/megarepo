import { useState, useEffect, type FormEvent } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { api, NetworkError } from '../../api/client';
import { useToast } from '../../components/Toast';
import LoadingSpinner from '../../components/LoadingSpinner';
import FormatBadge from '../../components/FormatBadge';
import TypeBadge from '../../components/TypeBadge';
import type { Repository } from '../../types/api';

export default function RepositoryEditPage() {
  const { name } = useParams<{ name: string }>();
  const navigate = useNavigate();
  const { showToast } = useToast();

  const [repo, setRepo] = useState<Repository | null>(null);
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);

  // Editable fields
  const [online, setOnline] = useState(true);
  const [remoteUrl, setRemoteUrl] = useState('');
  const [attributes, setAttributes] = useState<Record<string, unknown>>({});

  // Proxy config fields
  const [cacheTtlMinutes, setCacheTtlMinutes] = useState('1440');
  const [metadataCacheTtlMinutes, setMetadataCacheTtlMinutes] = useState('5');
  const [proxyUsername, setProxyUsername] = useState('');
  const [proxyPassword, setProxyPassword] = useState('');

  // Group member picker state
  const [availableRepos, setAvailableRepos] = useState<Repository[]>([]);
  const [selectedMembers, setSelectedMembers] = useState<string[]>([]);

  useEffect(() => {
    if (!name) return;
    api
      .get<Repository>(`/repositories/${name}`)
      .then((r) => {
        setRepo(r);
        setOnline(r.online);
        setAttributes(r.attributes);

        // Extract proxy config from attributes for proxy repos
        if (r.type.toLowerCase() === 'proxy') {
          const proxy = r.attributes?.proxy as Record<string, unknown> | undefined;
          if (proxy?.remoteUrl) {
            setRemoteUrl(proxy.remoteUrl as string);
          }
          if (proxy?.cacheTtlMinutes != null) {
            setCacheTtlMinutes(String(proxy.cacheTtlMinutes));
          } else if (proxy?.contentMaxAge != null) {
            setCacheTtlMinutes(String(proxy.contentMaxAge));
          }
          if (proxy?.metadataCacheTtlMinutes != null) {
            setMetadataCacheTtlMinutes(String(proxy.metadataCacheTtlMinutes));
          } else if (proxy?.metadataMaxAge != null) {
            setMetadataCacheTtlMinutes(String(proxy.metadataMaxAge));
          }
          if (proxy?.username) {
            setProxyUsername(proxy.username as string);
          }
          if (proxy?.password) {
            setProxyPassword(proxy.password as string);
          }
        }

        // Extract group members
        if (r.type.toLowerCase() === 'group') {
          const group = r.attributes?.group as Record<string, unknown> | undefined;
          if (group?.memberNames && Array.isArray(group.memberNames)) {
            setSelectedMembers(group.memberNames as string[]);
          }
        }
      })
      .catch(() => {
        showToast('error', `Repository "${name}" not found`);
        navigate('/admin/repositories');
      })
      .finally(() => setLoading(false));
  }, [name]); // eslint-disable-line react-hooks/exhaustive-deps

  // Fetch available repos for group member picker
  useEffect(() => {
    if (!repo || repo.type.toLowerCase() !== 'group') return;
    api
      .get<Repository[]>('/repositories')
      .then((repos) => {
        const eligible = repos.filter(
          (r) =>
            r.format.toLowerCase() === repo.format.toLowerCase() &&
            r.type.toLowerCase() !== 'group' &&
            r.name !== repo.name,
        );
        setAvailableRepos(eligible);
      })
      .catch(() => setAvailableRepos([]));
  }, [repo]);

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    if (!repo) return;
    setSubmitting(true);

    // Build updated attributes
    const updatedAttributes: Record<string, unknown> = { ...attributes };

    if (repo.type.toLowerCase() === 'proxy') {
      const proxyAttrs: Record<string, unknown> = {
        ...((updatedAttributes['proxy'] as Record<string, unknown>) || {}),
        remoteUrl,
        cacheTtlMinutes: parseInt(cacheTtlMinutes, 10) || 1440,
        metadataCacheTtlMinutes: parseInt(metadataCacheTtlMinutes, 10) || 5,
      };
      if (proxyUsername.trim()) {
        proxyAttrs.username = proxyUsername.trim();
        proxyAttrs.password = proxyPassword;
      } else {
        delete proxyAttrs.username;
        delete proxyAttrs.password;
      }
      updatedAttributes['proxy'] = proxyAttrs;
    }

    if (repo.type.toLowerCase() === 'group') {
      updatedAttributes['group'] = { memberNames: selectedMembers };
    }

    try {
      await api.put(`/repositories/${repo.name}`, {
        online,
        attributes: updatedAttributes,
      });
      showToast('success', `Repository "${repo.name}" updated`);
      navigate(`/admin/repositories/${repo.name}`);
    } catch (err: unknown) {
      const msg =
        err instanceof NetworkError
          ? 'Unable to connect to the server. Please check your network.'
          : err instanceof Error
            ? err.message
            : 'Failed to update repository';
      showToast('error', msg);
    } finally {
      setSubmitting(false);
    }
  }

  if (loading) {
    return (
      <div className="flex justify-center items-center py-20">
        <LoadingSpinner message="Loading repository..." />
      </div>
    );
  }

  if (!repo) return null;

  return (
    <div className="p-6 sm:p-8 max-w-7xl">
      <div className="flex items-start justify-between mb-6 gap-4">
        <div>
          <h1 className="text-2xl font-semibold text-gray-950 flex items-center gap-2">
            Edit Repository: {repo.name}
            <FormatBadge format={repo.format} />
            <TypeBadge type={repo.type} />
          </h1>
          <p className="text-sm text-gray-500 mt-1">Update repository settings</p>
        </div>
        <button
          className="inline-flex items-center px-4 py-2 bg-white border border-gray-200 text-gray-700 text-sm font-medium rounded-md hover:bg-gray-50 transition-colors"
          onClick={() => navigate(`/admin/repositories/${repo.name}`)}
        >
          Back
        </button>
      </div>

      <div className="bg-white rounded-lg border border-gray-200 overflow-hidden">
        <div className="p-6">
          <form onSubmit={handleSubmit}>
            {/* Read-only info */}
            <div className="mb-5">
              <label className="block text-sm font-medium text-gray-700 mb-1.5">
                Repository Name
              </label>
              <div className="w-full max-w-sm px-3 py-2 border border-gray-200 rounded-md text-sm text-gray-500 bg-gray-50">
                {repo.name}
              </div>
              <span className="text-xs text-gray-400 mt-1 block">
                Name cannot be changed after creation
              </span>
            </div>

            {/* Online toggle */}
            <div className="mb-5">
              <label className="flex items-center gap-2.5 cursor-pointer">
                <input
                  type="checkbox"
                  checked={online}
                  onChange={(e) => setOnline(e.target.checked)}
                  className="w-4 h-4 rounded border-gray-300 text-blue-600 focus:ring-blue-600"
                />
                <span className="text-sm font-medium text-gray-700">
                  Online - Accept incoming requests
                </span>
              </label>
            </div>

            {/* Proxy: Remote URL + Cache TTL + Auth */}
            {repo.type.toLowerCase() === 'proxy' && (
              <>
                <div className="mb-5">
                  <label
                    htmlFor="remote-url"
                    className="block text-sm font-medium text-gray-700 mb-1.5"
                  >
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
                      <label
                        htmlFor="cache-ttl"
                        className="block text-xs font-medium text-gray-600 mb-1"
                      >
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
                      <label
                        htmlFor="metadata-cache-ttl"
                        className="block text-xs font-medium text-gray-600 mb-1"
                      >
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
                      <label
                        htmlFor="proxy-username"
                        className="block text-xs font-medium text-gray-600 mb-1"
                      >
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
                      <label
                        htmlFor="proxy-password"
                        className="block text-xs font-medium text-gray-600 mb-1"
                      >
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

            {/* Group: Member picker */}
            {repo.type.toLowerCase() === 'group' && (
              <div className="mb-5">
                <label className="block text-sm font-medium text-gray-700 mb-1.5">
                  Group Members
                </label>
                <p className="text-xs text-gray-500 mb-3">
                  Select repositories to include in this group. Only {repo.format} hosted and proxy
                  repositories are shown.
                </p>
                {availableRepos.length === 0 ? (
                  <div className="text-sm text-gray-500 bg-gray-50 border border-gray-200 rounded-md px-4 py-3">
                    No eligible {repo.format} repositories found. Create hosted or proxy
                    repositories first.
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
                              onClick={() =>
                                setSelectedMembers((prev) => [...prev, r.name])
                              }
                            >
                              <span className="text-gray-700">{r.name}</span>
                              <span className="text-gray-400 text-xs">
                                <TypeBadge type={r.type} />
                              </span>
                            </button>
                          ))}
                        {availableRepos.filter((r) => !selectedMembers.includes(r.name)).length ===
                          0 && (
                          <div className="px-3 py-2 text-xs text-gray-400 text-center">
                            All added
                          </div>
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
                              <span className="text-xs text-gray-400 font-mono w-4">
                                {idx + 1}
                              </span>
                              {memberName}
                            </span>
                            <button
                              type="button"
                              className="text-gray-400 hover:text-red-500 transition-colors text-xs"
                              onClick={() =>
                                setSelectedMembers((prev) =>
                                  prev.filter((n) => n !== memberName),
                                )
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

            <div className="flex justify-end gap-3 pt-5 mt-5 border-t border-gray-200">
              <button
                className="inline-flex items-center px-4 py-2 bg-white border border-gray-200 text-gray-700 text-sm font-medium rounded-md hover:bg-gray-50 transition-colors"
                type="button"
                onClick={() => navigate(`/admin/repositories/${repo.name}`)}
              >
                Cancel
              </button>
              <button
                className="inline-flex items-center gap-1.5 px-4 py-2 bg-blue-600 hover:bg-blue-700 text-white text-sm font-medium rounded-md transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                type="submit"
                disabled={submitting}
              >
                {submitting ? 'Saving...' : 'Save Changes'}
              </button>
            </div>
          </form>
        </div>
      </div>
    </div>
  );
}
