import { useState, useMemo } from 'react';
import { useToast } from '../../components/Toast';
import type { Component } from '../../types/api';

function formatBytes(bytes: number): string {
  if (bytes === 0) return '0 B';
  const k = 1024;
  const sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return `${parseFloat((bytes / Math.pow(k, i)).toFixed(1))} ${sizes[i]}`;
}

function formatDate(iso: string | null): string {
  if (!iso) return '-';
  const d = new Date(iso);
  return d.toLocaleDateString('en-US', {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });
}

/** Docker whale icon as inline SVG */
function DockerIcon({ className }: { className?: string }) {
  return (
    <svg className={className} width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5">
      <rect x="1" y="8" width="4" height="3" rx="0.5" />
      <rect x="6" y="8" width="4" height="3" rx="0.5" />
      <rect x="11" y="8" width="4" height="3" rx="0.5" />
      <rect x="6" y="4" width="4" height="3" rx="0.5" />
      <rect x="11" y="4" width="4" height="3" rx="0.5" />
      <rect x="16" y="4" width="4" height="3" rx="0.5" />
      <rect x="16" y="8" width="4" height="3" rx="0.5" />
      <rect x="11" y="0.5" width="4" height="3" rx="0.5" />
      <path d="M0 13c0 0 1 5 8 6s14-2 16-5" />
    </svg>
  );
}

interface DockerImage {
  /** Full image name including namespace, e.g. "library/nginx" or "myapp" */
  imageName: string;
  tags: DockerTag[];
}

interface DockerTag {
  tag: string;
  component: Component;
  totalSize: number;
  pushedAt: string | null;
  digest: string | null;
  layerCount: number;
}

function groupComponentsIntoImages(components: Component[]): DockerImage[] {
  const imageMap = new Map<string, DockerTag[]>();

  for (const comp of components) {
    // Image name = group/name or just name
    const imageName = comp.group ? `${comp.group}/${comp.name}` : comp.name;
    const tag = comp.version || 'latest';

    // Find manifest asset for digest
    const manifestAsset = comp.assets.find(
      (a) => a.contentType?.includes('manifest') || a.path.includes('/manifests/'),
    );
    // Layer assets are blobs
    const layerAssets = comp.assets.filter(
      (a) => a.path.includes('/blobs/') && !a.path.includes('/manifests/'),
    );

    const totalSize = comp.assets.reduce((sum, a) => sum + a.fileSize, 0);
    const pushedAt = manifestAsset?.lastModified || comp.assets[0]?.lastModified || null;
    const digest = manifestAsset?.checksumSha256
      ? `sha256:${manifestAsset.checksumSha256}`
      : null;

    if (!imageMap.has(imageName)) {
      imageMap.set(imageName, []);
    }
    imageMap.get(imageName)!.push({
      tag,
      component: comp,
      totalSize,
      pushedAt,
      digest,
      layerCount: layerAssets.length,
    });
  }

  // Sort images by name, tags by pushed date (newest first)
  const images: DockerImage[] = [];
  for (const [imageName, tags] of imageMap) {
    tags.sort((a, b) => {
      if (!a.pushedAt) return 1;
      if (!b.pushedAt) return -1;
      return new Date(b.pushedAt).getTime() - new Date(a.pushedAt).getTime();
    });
    images.push({ imageName, tags });
  }
  images.sort((a, b) => a.imageName.localeCompare(b.imageName));
  return images;
}

function CopyButton({ text }: { text: string }) {
  const { showToast } = useToast();
  const [copied, setCopied] = useState(false);

  const handleCopy = () => {
    navigator.clipboard.writeText(text).then(() => {
      setCopied(true);
      showToast('success', 'Copied to clipboard');
      setTimeout(() => setCopied(false), 2000);
    });
  };

  return (
    <button
      onClick={(e) => {
        e.stopPropagation();
        handleCopy();
      }}
      className="inline-flex items-center gap-1 px-2 py-1 text-xs text-gray-500 hover:text-blue-600 hover:bg-blue-50 rounded transition-colors"
      title="Copy to clipboard"
    >
      {copied ? (
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
          <polyline points="20 6 9 17 4 12" />
        </svg>
      ) : (
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
          <rect x="9" y="9" width="13" height="13" rx="2" ry="2" />
          <path d="M5 15H4a2 2 0 01-2-2V4a2 2 0 012-2h9a2 2 0 012 2v1" />
        </svg>
      )}
    </button>
  );
}

function TagRow({ tag, repositoryName, registryHost }: { tag: DockerTag; repositoryName: string; registryHost: string }) {
  const [expanded, setExpanded] = useState(false);
  const pullCommand = `docker pull ${registryHost}/${repositoryName}/${tag.component.group ? tag.component.group + '/' : ''}${tag.component.name}:${tag.tag}`;

  return (
    <>
      <tr
        className="hover:bg-gray-50 cursor-pointer transition-colors"
        onClick={() => setExpanded(!expanded)}
      >
        <td className="px-4 py-3">
          <div className="flex items-center gap-2">
            <svg
              width="14"
              height="14"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth="2"
              className={`text-gray-400 transition-transform ${expanded ? 'rotate-90' : ''}`}
            >
              <polyline points="9 18 15 12 9 6" />
            </svg>
            <span className="font-mono text-sm font-medium text-gray-900">{tag.tag}</span>
          </div>
        </td>
        <td className="px-4 py-3">
          {tag.digest ? (
            <span className="font-mono text-xs text-gray-500" title={tag.digest}>
              {tag.digest.substring(0, 19)}...
            </span>
          ) : (
            <span className="text-xs text-gray-400">-</span>
          )}
        </td>
        <td className="px-4 py-3">
          <span className="text-sm text-gray-600 tabular-nums">{formatBytes(tag.totalSize)}</span>
        </td>
        <td className="px-4 py-3">
          <span className="text-sm text-gray-500">{tag.layerCount}</span>
        </td>
        <td className="px-4 py-3">
          <span className="text-xs text-gray-500">{formatDate(tag.pushedAt)}</span>
        </td>
      </tr>
      {expanded && (
        <tr className="bg-gray-50/70">
          <td colSpan={5} className="px-4 py-4">
            <div className="ml-6 space-y-3">
              {/* Pull command */}
              <div>
                <div className="text-xs font-semibold text-gray-500 uppercase tracking-wider mb-1.5">Pull Command</div>
                <div className="flex items-center gap-1 bg-gray-900 rounded-md px-3 py-2 max-w-2xl">
                  <code className="text-xs text-green-400 font-mono flex-1 select-all">{pullCommand}</code>
                  <CopyButton text={pullCommand} />
                </div>
              </div>

              {/* Digest */}
              {tag.digest && (
                <div>
                  <div className="text-xs font-semibold text-gray-500 uppercase tracking-wider mb-1.5">Digest</div>
                  <div className="flex items-center gap-1">
                    <code className="text-xs text-gray-600 font-mono bg-gray-100 px-2 py-1 rounded select-all break-all">
                      {tag.digest}
                    </code>
                    <CopyButton text={tag.digest} />
                  </div>
                </div>
              )}

              {/* Assets / Layers */}
              {tag.component.assets.length > 0 && (
                <div>
                  <div className="text-xs font-semibold text-gray-500 uppercase tracking-wider mb-1.5">
                    Layers & Assets ({tag.component.assets.length})
                  </div>
                  <div className="space-y-1">
                    {tag.component.assets.map((asset) => {
                      const isManifest = asset.contentType?.includes('manifest') || asset.path.includes('/manifests/');
                      const isConfig = asset.contentType?.includes('container.image');
                      return (
                        <div key={asset.id} className="flex items-center gap-3 text-xs bg-white border border-gray-200 rounded px-3 py-2">
                          <span className={`inline-flex items-center px-1.5 py-0.5 rounded text-[10px] font-semibold uppercase tracking-wider ${
                            isManifest
                              ? 'bg-purple-100 text-purple-700'
                              : isConfig
                                ? 'bg-amber-100 text-amber-700'
                                : 'bg-blue-100 text-blue-700'
                          }`}>
                            {isManifest ? 'manifest' : isConfig ? 'config' : 'layer'}
                          </span>
                          <span className="font-mono text-gray-600 truncate flex-1" title={asset.path}>
                            {asset.checksumSha256
                              ? `sha256:${asset.checksumSha256.substring(0, 12)}...`
                              : asset.path.split('/').pop()}
                          </span>
                          <span className="text-gray-500 tabular-nums shrink-0">{formatBytes(asset.fileSize)}</span>
                          <span className="text-gray-400 shrink-0">{asset.contentType || ''}</span>
                        </div>
                      );
                    })}
                  </div>
                </div>
              )}
            </div>
          </td>
        </tr>
      )}
    </>
  );
}

function ImageCard({ image, repositoryName, registryHost }: { image: DockerImage; repositoryName: string; registryHost: string }) {
  const [collapsed, setCollapsed] = useState(false);

  return (
    <div className="bg-white rounded-lg border border-gray-200 overflow-hidden">
      {/* Image header */}
      <div
        className="flex items-center justify-between px-5 py-4 cursor-pointer hover:bg-gray-50 transition-colors"
        onClick={() => setCollapsed(!collapsed)}
      >
        <div className="flex items-center gap-3">
          <DockerIcon className="text-blue-500" />
          <div>
            <div className="font-medium text-gray-900">{image.imageName}</div>
            <div className="text-xs text-gray-500 mt-0.5">
              {image.tags.length} tag{image.tags.length !== 1 ? 's' : ''}
            </div>
          </div>
        </div>
        <svg
          width="16"
          height="16"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth="2"
          className={`text-gray-400 transition-transform ${collapsed ? '' : 'rotate-180'}`}
        >
          <polyline points="6 9 12 15 18 9" />
        </svg>
      </div>

      {/* Tags table */}
      {!collapsed && (
        <div className="border-t border-gray-200">
          <table className="w-full text-sm">
            <thead>
              <tr className="bg-gray-50 border-b border-gray-100">
                <th className="text-left px-4 py-2 text-xs font-semibold text-gray-500 uppercase tracking-wider">Tag</th>
                <th className="text-left px-4 py-2 text-xs font-semibold text-gray-500 uppercase tracking-wider">Digest</th>
                <th className="text-left px-4 py-2 text-xs font-semibold text-gray-500 uppercase tracking-wider">Size</th>
                <th className="text-left px-4 py-2 text-xs font-semibold text-gray-500 uppercase tracking-wider">Layers</th>
                <th className="text-left px-4 py-2 text-xs font-semibold text-gray-500 uppercase tracking-wider">Pushed</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {image.tags.map((tag) => (
                <TagRow key={tag.tag} tag={tag} repositoryName={repositoryName} registryHost={registryHost} />
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

interface DockerBrowseViewProps {
  repositoryName: string;
  components: Component[];
  repositoryUrl?: string;
}

export default function DockerBrowseView({ repositoryName, components, repositoryUrl }: DockerBrowseViewProps) {
  const [filter, setFilter] = useState('');

  // Derive registry host from the repository URL or fall back to window.location.host
  const registryHost = useMemo(() => {
    if (repositoryUrl) {
      try {
        const url = new URL(repositoryUrl);
        return url.host;
      } catch {
        // fall through
      }
    }
    return window.location.host;
  }, [repositoryUrl]);

  const images = useMemo(() => groupComponentsIntoImages(components), [components]);

  const filtered = useMemo(() => {
    if (!filter) return images;
    const lc = filter.toLowerCase();
    return images.filter(
      (img) =>
        img.imageName.toLowerCase().includes(lc) ||
        img.tags.some((t) => t.tag.toLowerCase().includes(lc)),
    );
  }, [images, filter]);

  return (
    <>
      {/* Search */}
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
            placeholder="Filter images or tags..."
            value={filter}
            onChange={(e) => setFilter(e.target.value)}
            className="w-full pl-10 pr-4 py-2 border border-gray-300 rounded-md text-sm text-gray-700 bg-white placeholder:text-gray-400 focus:outline-none focus:border-blue-600 focus:ring-1 focus:ring-blue-600 transition-colors"
          />
        </div>
      </div>

      {/* Summary */}
      <div className="flex items-center gap-4 mb-4 text-xs text-gray-500">
        <span>{images.length} image{images.length !== 1 ? 's' : ''}</span>
        <span className="text-gray-300">|</span>
        <span>{components.length} tag{components.length !== 1 ? 's' : ''} total</span>
      </div>

      {/* Image cards */}
      {filtered.length === 0 ? (
        <div className="bg-white rounded-lg border border-gray-200 p-10 text-center text-sm text-gray-500">
          {filter ? 'No images match your filter' : 'No Docker images pushed yet'}
        </div>
      ) : (
        <div className="flex flex-col gap-4">
          {filtered.map((image) => (
            <ImageCard
              key={image.imageName}
              image={image}
              repositoryName={repositoryName}
              registryHost={registryHost}
            />
          ))}
        </div>
      )}
    </>
  );
}
