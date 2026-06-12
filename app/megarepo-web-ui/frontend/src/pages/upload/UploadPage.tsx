import { useState, useEffect, useRef, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { api } from '../../api/client';
import LoadingSpinner from '../../components/LoadingSpinner';
import EmptyState from '../../components/EmptyState';
import { useToast } from '../../components/Toast';
import type { Repository } from '../../types/api';

export default function UploadPage() {
  const navigate = useNavigate();
  const { showToast } = useToast();
  const [repos, setRepos] = useState<Repository[]>([]);
  const [loading, setLoading] = useState(true);
  const [selectedRepo, setSelectedRepo] = useState('');

  useEffect(() => {
    api
      .get<Repository[]>('/repositories')
      .then((data) => {
        const hosted = data.filter((r) => r.type.toLowerCase() === 'hosted');
        setRepos(hosted);
        if (hosted.length > 0) setSelectedRepo(hosted[0].name);
      })
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

  const selectedRepoObj = repos.find((r) => r.name === selectedRepo);
  const format = selectedRepoObj?.format;
  const isMaven = format === 'maven2' || format === 'maven';

  return (
    <div className="p-6 sm:p-8 max-w-7xl">
      <div className="flex items-start justify-between mb-6 gap-4">
        <div>
          <h1 className="text-2xl font-semibold text-gray-950">Upload</h1>
          <p className="text-sm text-gray-500 mt-1">Upload artifacts to hosted repositories</p>
        </div>
      </div>

      {repos.length === 0 ? (
        <div className="bg-white rounded-lg border border-gray-200 overflow-hidden">
          <EmptyState
            icon={
              <svg className="mx-auto h-12 w-12 text-gray-300" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
                <path strokeLinecap="round" strokeLinejoin="round" d="M12 16.5V9.75m0 0l3 3m-3-3l-3 3M6.75 19.5a4.5 4.5 0 01-1.41-8.775 5.25 5.25 0 0110.233-2.33 3 3 0 013.758 3.848A3.752 3.752 0 0118 19.5H6.75z" />
              </svg>
            }
            title="No Hosted Repositories"
            description="Create a hosted repository to start uploading artifacts."
            action={
              <button
                className="inline-flex items-center gap-1.5 px-4 py-2 bg-blue-600 hover:bg-blue-700 text-white text-sm font-medium rounded-md transition-colors"
                onClick={() => navigate('/admin/repositories/create')}
              >
                Create Repository
              </button>
            }
          />
        </div>
      ) : (
        <div className="space-y-6">
          <div className="bg-white rounded-lg border border-gray-200 overflow-hidden">
            <div className="px-6 py-4 bg-gray-50 border-b border-gray-200">
              <h2 className="text-sm font-semibold text-gray-700">Select Repository</h2>
            </div>
            <div className="p-6">
              <select
                value={selectedRepo}
                onChange={(e) => setSelectedRepo(e.target.value)}
                className="w-full max-w-sm px-3 py-2 border border-gray-300 rounded-md text-sm text-gray-700 bg-white focus:outline-none focus:border-blue-600 focus:ring-1 focus:ring-blue-600 transition-colors"
              >
                {repos.map((r) => (
                  <option key={r.name} value={r.name}>
                    {r.name} ({r.format})
                  </option>
                ))}
              </select>
            </div>
          </div>

          <div className="bg-white rounded-lg border border-gray-200 overflow-hidden">
            <div className="px-6 py-4 bg-gray-50 border-b border-gray-200">
              <h2 className="text-sm font-semibold text-gray-700">
                Upload to {selectedRepo}
                {format && <span className="ml-2 text-xs font-normal text-gray-500">({format})</span>}
              </h2>
            </div>
            <div className="p-6">
              {isMaven && <MavenUploadWidget key={selectedRepo} repo={selectedRepo} />}
              {format === 'pypi' && <PypiUploadWidget key={selectedRepo} repo={selectedRepo} />}
              {format === 'npm' && <NpmUploadWidget key={selectedRepo} repo={selectedRepo} />}
              {format === 'raw' && <RawUploadWidget key={selectedRepo} repo={selectedRepo} />}
              {format === 'docker' && <DockerUploadHint repo={selectedRepo} />}
              {!format && (
                <p className="text-sm text-gray-500">Select a repository above to see upload options.</p>
              )}
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

/** Uploads a FormData payload to the components upload endpoint with progress. */
function uploadComponent(
  repo: string,
  formData: FormData,
  onProgress: (percent: number) => void,
): Promise<void> {
  const token = api.getToken();
  return new Promise<void>((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open('POST', `/api/v1/components/upload?repository=${encodeURIComponent(repo)}`);
    if (token) {
      xhr.setRequestHeader('Authorization', `Bearer ${token}`);
    }
    xhr.upload.addEventListener('progress', (e) => {
      if (e.lengthComputable) {
        onProgress(Math.round((e.loaded / e.total) * 100));
      }
    });
    xhr.addEventListener('load', () => {
      if (xhr.status >= 200 && xhr.status < 300) {
        resolve();
      } else {
        let message = xhr.statusText || `Upload failed (HTTP ${xhr.status})`;
        try {
          const body = JSON.parse(xhr.responseText);
          if (body?.message) message = body.message;
        } catch {
          // not JSON — keep default message
        }
        reject(new Error(message));
      }
    });
    xhr.addEventListener('error', () => reject(new Error('Network error during upload')));
    xhr.addEventListener('abort', () => reject(new Error('Upload cancelled')));
    xhr.send(formData);
  });
}

function UploadProgress({ progress }: { progress: number }) {
  return (
    <div className="space-y-1">
      <div className="flex justify-between text-xs text-gray-600">
        <span>Uploading...</span>
        <span>{progress}%</span>
      </div>
      <div className="w-full bg-gray-200 rounded-full h-2">
        <div className="bg-blue-600 h-2 rounded-full transition-all duration-300" style={{ width: `${progress}%` }} />
      </div>
    </div>
  );
}

function UploadButton({
  onClick,
  disabled,
  uploading,
}: {
  onClick: () => void;
  disabled: boolean;
  uploading: boolean;
}) {
  return (
    <button
      onClick={onClick}
      disabled={disabled || uploading}
      className="inline-flex items-center gap-1.5 px-4 py-2 bg-blue-600 hover:bg-blue-700 text-white text-sm font-medium rounded-md transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
    >
      {uploading ? 'Uploading...' : 'Upload'}
    </button>
  );
}

const inputClass =
  'w-full px-3 py-2 border border-gray-300 rounded-md text-sm text-gray-700 bg-white focus:outline-none focus:border-blue-600 focus:ring-1 focus:ring-blue-600 transition-colors disabled:opacity-50';

function FileDropZone({
  file,
  onFile,
  disabled,
  hint,
  accept,
}: {
  file: File | null;
  onFile: (f: File) => void;
  disabled: boolean;
  hint: string;
  accept?: string;
}) {
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [dragOver, setDragOver] = useState(false);

  return (
    <div
      onDrop={(e) => {
        e.preventDefault();
        setDragOver(false);
        const f = e.dataTransfer.files[0];
        if (f) onFile(f);
      }}
      onDragOver={(e) => {
        e.preventDefault();
        setDragOver(true);
      }}
      onDragLeave={(e) => {
        e.preventDefault();
        setDragOver(false);
      }}
      onClick={() => fileInputRef.current?.click()}
      className={`relative border-2 border-dashed rounded-lg p-8 text-center cursor-pointer transition-colors ${
        dragOver
          ? 'border-blue-400 bg-blue-50'
          : file
            ? 'border-green-300 bg-green-50'
            : 'border-gray-300 hover:border-gray-400 bg-gray-50'
      }`}
    >
      <input
        ref={fileInputRef}
        type="file"
        accept={accept}
        className="hidden"
        onChange={(e) => {
          const f = e.target.files?.[0];
          if (f) onFile(f);
          e.target.value = '';
        }}
        disabled={disabled}
      />
      {file ? (
        <div className="space-y-1">
          <svg className="mx-auto h-10 w-10 text-green-500" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
            <path strokeLinecap="round" strokeLinejoin="round" d="M9 12.75L11.25 15 15 9.75M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
          </svg>
          <p className="text-sm font-medium text-gray-900">{file.name}</p>
          <p className="text-xs text-gray-500">{formatSize(file.size)}</p>
        </div>
      ) : (
        <div className="space-y-1">
          <svg className="mx-auto h-10 w-10 text-gray-400" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
            <path strokeLinecap="round" strokeLinejoin="round" d="M12 16.5V9.75m0 0l3 3m-3-3l-3 3M6.75 19.5a4.5 4.5 0 01-1.41-8.775 5.25 5.25 0 0110.233-2.33 3 3 0 013.758 3.848A3.752 3.752 0 0118 19.5H6.75z" />
          </svg>
          <p className="text-sm text-gray-700">Drag and drop a file here, or click to select</p>
          <p className="text-xs text-gray-500">{hint}</p>
        </div>
      )}
    </div>
  );
}

function CliHint({ children }: { children: string }) {
  return (
    <details className="text-xs text-gray-500">
      <summary className="cursor-pointer hover:text-gray-700">Or use the CLI</summary>
      <pre className="mt-2 bg-gray-900 text-gray-200 text-xs p-4 rounded-lg overflow-x-auto leading-relaxed">{children}</pre>
    </details>
  );
}

// ── Maven ──────────────────────────────────────────────────────────────

interface MavenAsset {
  file: File;
  classifier: string;
  extension: string;
}

function extensionOf(filename: string): string {
  if (filename.endsWith('.tar.gz')) return 'tar.gz';
  const dot = filename.lastIndexOf('.');
  return dot > 0 ? filename.substring(dot + 1) : '';
}

function MavenUploadWidget({ repo }: { repo: string }) {
  const { showToast } = useToast();
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [groupId, setGroupId] = useState('');
  const [artifactId, setArtifactId] = useState('');
  const [version, setVersion] = useState('');
  const [generatePom, setGeneratePom] = useState(true);
  const [assets, setAssets] = useState<MavenAsset[]>([]);
  const [uploading, setUploading] = useState(false);
  const [progress, setProgress] = useState(0);

  const hasPom = assets.some((a) => a.extension === 'pom');

  const addFile = useCallback((f: File) => {
    setAssets((prev) => [...prev, { file: f, classifier: '', extension: extensionOf(f.name) }]);
  }, []);

  function updateAsset(index: number, patch: Partial<MavenAsset>) {
    setAssets((prev) => prev.map((a, i) => (i === index ? { ...a, ...patch } : a)));
  }

  function removeAsset(index: number) {
    setAssets((prev) => prev.filter((_, i) => i !== index));
  }

  const coordinatesProvided = groupId.trim() && artifactId.trim() && version.trim();
  const canUpload = assets.length > 0 && (Boolean(coordinatesProvided) || hasPom);

  async function handleUpload() {
    if (!canUpload) return;
    const formData = new FormData();
    if (groupId.trim()) formData.append('groupId', groupId.trim());
    if (artifactId.trim()) formData.append('artifactId', artifactId.trim());
    if (version.trim()) formData.append('version', version.trim());
    if (generatePom && !hasPom) formData.append('generatePom', 'true');
    assets.forEach((asset, i) => {
      const field = `asset${i}`;
      formData.append(field, asset.file, asset.file.name);
      if (asset.extension.trim()) formData.append(`${field}.extension`, asset.extension.trim());
      if (asset.classifier.trim()) formData.append(`${field}.classifier`, asset.classifier.trim());
    });

    setUploading(true);
    setProgress(0);
    try {
      await uploadComponent(repo, formData, setProgress);
      showToast('success', `Uploaded ${assets.length} file(s) to ${repo}`);
      setAssets([]);
      setGroupId('');
      setArtifactId('');
      setVersion('');
    } catch (err) {
      showToast('error', err instanceof Error ? err.message : 'Upload failed');
    } finally {
      setUploading(false);
    }
  }

  return (
    <div className="space-y-5">
      <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
        <div>
          <label className="block text-sm font-medium text-gray-700 mb-1">Group ID</label>
          <input
            type="text"
            value={groupId}
            onChange={(e) => setGroupId(e.target.value)}
            placeholder="com.example"
            disabled={uploading}
            className={inputClass}
          />
        </div>
        <div>
          <label className="block text-sm font-medium text-gray-700 mb-1">Artifact ID</label>
          <input
            type="text"
            value={artifactId}
            onChange={(e) => setArtifactId(e.target.value)}
            placeholder="my-library"
            disabled={uploading}
            className={inputClass}
          />
        </div>
        <div>
          <label className="block text-sm font-medium text-gray-700 mb-1">Version</label>
          <input
            type="text"
            value={version}
            onChange={(e) => setVersion(e.target.value)}
            placeholder="1.0.0"
            disabled={uploading}
            className={inputClass}
          />
        </div>
      </div>
      {!coordinatesProvided && (
        <p className="text-xs text-gray-500">
          Coordinates can be left empty if you upload a <code className="font-mono bg-gray-100 text-gray-700 px-1 py-0.5 rounded">.pom</code> file — they will be read from the POM.
        </p>
      )}

      {/* Asset list */}
      {assets.length > 0 && (
        <div className="space-y-2">
          {assets.map((asset, i) => (
            <div key={i} className="flex items-center gap-2 bg-gray-50 border border-gray-200 rounded-md px-3 py-2">
              <span className="flex-1 text-sm text-gray-900 truncate" title={asset.file.name}>
                {asset.file.name}
                <span className="ml-2 text-xs text-gray-500">{formatSize(asset.file.size)}</span>
              </span>
              <input
                type="text"
                value={asset.classifier}
                onChange={(e) => updateAsset(i, { classifier: e.target.value })}
                placeholder="classifier"
                disabled={uploading}
                className="w-28 px-2 py-1 border border-gray-300 rounded text-xs text-gray-700 bg-white focus:outline-none focus:border-blue-600"
              />
              <input
                type="text"
                value={asset.extension}
                onChange={(e) => updateAsset(i, { extension: e.target.value })}
                placeholder="extension"
                disabled={uploading}
                className="w-20 px-2 py-1 border border-gray-300 rounded text-xs text-gray-700 bg-white focus:outline-none focus:border-blue-600"
              />
              <button
                onClick={() => removeAsset(i)}
                disabled={uploading}
                className="text-gray-400 hover:text-red-600 transition-colors"
                title="Remove file"
              >
                <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
                  <path strokeLinecap="round" strokeLinejoin="round" d="M6 18L18 6M6 6l12 12" />
                </svg>
              </button>
            </div>
          ))}
        </div>
      )}

      <div className="flex items-center gap-4">
        <input
          ref={fileInputRef}
          type="file"
          multiple
          className="hidden"
          onChange={(e) => {
            Array.from(e.target.files ?? []).forEach(addFile);
            e.target.value = '';
          }}
          disabled={uploading}
        />
        <button
          onClick={() => fileInputRef.current?.click()}
          disabled={uploading}
          className="inline-flex items-center gap-1.5 px-3 py-1.5 border border-gray-300 hover:border-gray-400 text-gray-700 text-sm font-medium rounded-md transition-colors disabled:opacity-50"
        >
          <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
            <path strokeLinecap="round" strokeLinejoin="round" d="M12 4.5v15m7.5-7.5h-15" />
          </svg>
          Add file
        </button>
        <label className="flex items-center gap-2 text-sm text-gray-700">
          <input
            type="checkbox"
            checked={generatePom && !hasPom}
            onChange={(e) => setGeneratePom(e.target.checked)}
            disabled={uploading || hasPom}
            className="rounded border-gray-300 text-blue-600 focus:ring-blue-600"
          />
          Generate POM
          {hasPom && <span className="text-xs text-gray-400">(POM file uploaded)</span>}
        </label>
      </div>

      {uploading && <UploadProgress progress={progress} />}
      <UploadButton onClick={handleUpload} disabled={!canUpload} uploading={uploading} />
      <p className="text-xs text-gray-500">
        The <code className="font-mono bg-gray-100 text-gray-700 px-1 py-0.5 rounded">maven-metadata.xml</code> of the artifact is regenerated automatically after upload.
      </p>

      <CliHint>
        {`mvn deploy -DaltDeploymentRepository=megarepo::default::${window.location.origin}/repository/${repo}\n\n# or deploy a single file:\nmvn deploy:deploy-file -DgroupId=com.example -DartifactId=my-library \\\n  -Dversion=1.0.0 -Dpackaging=jar -Dfile=my-library-1.0.0.jar \\\n  -DrepositoryId=megarepo -Durl=${window.location.origin}/repository/${repo}`}
      </CliHint>
    </div>
  );
}

// ── npm ────────────────────────────────────────────────────────────────

function NpmUploadWidget({ repo }: { repo: string }) {
  const { showToast } = useToast();
  const [file, setFile] = useState<File | null>(null);
  const [uploading, setUploading] = useState(false);
  const [progress, setProgress] = useState(0);

  async function handleUpload() {
    if (!file) return;
    const formData = new FormData();
    formData.append('file', file, file.name);

    setUploading(true);
    setProgress(0);
    try {
      await uploadComponent(repo, formData, setProgress);
      showToast('success', `Uploaded ${file.name} to ${repo}`);
      setFile(null);
    } catch (err) {
      showToast('error', err instanceof Error ? err.message : 'Upload failed');
    } finally {
      setUploading(false);
    }
  }

  return (
    <div className="space-y-5">
      <FileDropZone
        file={file}
        onFile={setFile}
        disabled={uploading}
        hint="npm package tarball (.tgz, created with npm pack)"
        accept=".tgz,.tar.gz"
      />
      <p className="text-xs text-gray-500">
        Package name and version are read from the <code className="font-mono bg-gray-100 text-gray-700 px-1 py-0.5 rounded">package.json</code> inside the tarball.
      </p>
      {uploading && <UploadProgress progress={progress} />}
      <UploadButton onClick={handleUpload} disabled={!file} uploading={uploading} />
      <CliHint>
        {`npm config set registry ${window.location.origin}/repository/${repo}/\nnpm publish`}
      </CliHint>
    </div>
  );
}

// ── PyPI ───────────────────────────────────────────────────────────────

function PypiUploadWidget({ repo }: { repo: string }) {
  const { showToast } = useToast();
  const [file, setFile] = useState<File | null>(null);
  const [name, setName] = useState('');
  const [version, setVersion] = useState('');
  const [uploading, setUploading] = useState(false);
  const [progress, setProgress] = useState(0);

  async function handleUpload() {
    if (!file) return;
    const formData = new FormData();
    formData.append('file', file, file.name);
    if (name.trim()) formData.append('name', name.trim());
    if (version.trim()) formData.append('version', version.trim());

    setUploading(true);
    setProgress(0);
    try {
      await uploadComponent(repo, formData, setProgress);
      showToast('success', `Uploaded ${file.name} to ${repo}`);
      setFile(null);
      setName('');
      setVersion('');
    } catch (err) {
      showToast('error', err instanceof Error ? err.message : 'Upload failed');
    } finally {
      setUploading(false);
    }
  }

  return (
    <div className="space-y-5">
      <FileDropZone
        file={file}
        onFile={setFile}
        disabled={uploading}
        hint="Distribution file (.whl or .tar.gz)"
        accept=".whl,.tar.gz,.zip"
      />
      <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
        <div>
          <label className="block text-sm font-medium text-gray-700 mb-1">
            Package name <span className="font-normal text-gray-400">(optional)</span>
          </label>
          <input
            type="text"
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="auto-detected from filename"
            disabled={uploading}
            className={inputClass}
          />
        </div>
        <div>
          <label className="block text-sm font-medium text-gray-700 mb-1">
            Version <span className="font-normal text-gray-400">(optional)</span>
          </label>
          <input
            type="text"
            value={version}
            onChange={(e) => setVersion(e.target.value)}
            placeholder="auto-detected from filename"
            disabled={uploading}
            className={inputClass}
          />
        </div>
      </div>
      {uploading && <UploadProgress progress={progress} />}
      <UploadButton onClick={handleUpload} disabled={!file} uploading={uploading} />
      <CliHint>
        {`twine upload --repository-url ${window.location.origin}/repository/${repo}/ dist/*`}
      </CliHint>
    </div>
  );
}

// ── Docker ─────────────────────────────────────────────────────────────

function DockerUploadHint({ repo }: { repo: string }) {
  return (
    <div className="space-y-4">
      <p className="text-sm text-gray-700">
        Docker images consist of manifests and layers and must be pushed via the Docker Registry API — a file upload is not applicable. Use{' '}
        <code className="text-xs font-mono bg-gray-100 text-gray-700 px-1.5 py-0.5 rounded">docker push</code>:
      </p>
      <pre className="bg-gray-900 text-gray-200 text-xs p-4 rounded-lg overflow-x-auto leading-relaxed">
{`docker login ${window.location.host}
docker tag my-image:latest ${window.location.host}/my-image:latest
docker push ${window.location.host}/my-image:latest`}
      </pre>
      <p className="text-xs text-gray-500">
        Depending on your setup, the registry may be exposed on a dedicated port or subdomain — see the connector settings of <span className="font-medium">{repo}</span>.
      </p>
    </div>
  );
}

// ── Raw ────────────────────────────────────────────────────────────────

function RawUploadWidget({ repo }: { repo: string }) {
  const { showToast } = useToast();
  const [file, setFile] = useState<File | null>(null);
  const [path, setPath] = useState('');
  const [uploading, setUploading] = useState(false);
  const [progress, setProgress] = useState(0);

  const handleFile = useCallback((f: File) => {
    setFile(f);
    setPath((prev) => (prev ? prev : f.name));
  }, []);

  async function handleUpload() {
    if (!file || !path.trim()) return;

    const cleanPath = path.replace(/^\/+/, '');
    const url = `/repository/${repo}/${cleanPath}`;
    const token = api.getToken();

    setUploading(true);
    setProgress(0);

    try {
      await new Promise<void>((resolve, reject) => {
        const xhr = new XMLHttpRequest();
        xhr.open('PUT', url);
        if (token) {
          xhr.setRequestHeader('Authorization', `Bearer ${token}`);
        }
        xhr.upload.addEventListener('progress', (e) => {
          if (e.lengthComputable) {
            setProgress(Math.round((e.loaded / e.total) * 100));
          }
        });
        xhr.addEventListener('load', () => {
          if (xhr.status >= 200 && xhr.status < 300) {
            resolve();
          } else {
            reject(new Error(xhr.statusText || `Upload failed (HTTP ${xhr.status})`));
          }
        });
        xhr.addEventListener('error', () => reject(new Error('Network error during upload')));
        xhr.addEventListener('abort', () => reject(new Error('Upload cancelled')));
        xhr.send(file);
      });

      showToast('success', `Uploaded ${cleanPath} to ${repo}`);
      setFile(null);
      setPath('');
      setProgress(0);
    } catch (err) {
      showToast('error', err instanceof Error ? err.message : 'Upload failed');
    } finally {
      setUploading(false);
    }
  }

  return (
    <div className="space-y-5">
      <FileDropZone file={file} onFile={handleFile} disabled={uploading} hint="Any file type accepted" />

      {/* Path input */}
      <div>
        <label className="block text-sm font-medium text-gray-700 mb-1">Target path</label>
        <div className="flex items-center gap-2">
          <span className="text-sm text-gray-400 whitespace-nowrap">/repository/{repo}/</span>
          <input
            type="text"
            value={path}
            onChange={(e) => setPath(e.target.value)}
            placeholder="path/to/file.zip"
            disabled={uploading}
            className={`flex-1 ${inputClass}`}
          />
        </div>
      </div>

      {uploading && <UploadProgress progress={progress} />}
      <UploadButton onClick={handleUpload} disabled={!file || !path.trim()} uploading={uploading} />

      <CliHint>
        {`curl -u admin:password --upload-file myfile.zip \\\n  ${window.location.origin}/repository/${repo}/path/to/myfile.zip`}
      </CliHint>
    </div>
  );
}

function formatSize(bytes: number): string {
  if (bytes < 1024) return bytes + ' B';
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
  if (bytes < 1024 * 1024 * 1024) return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
  return (bytes / (1024 * 1024 * 1024)).toFixed(1) + ' GB';
}
