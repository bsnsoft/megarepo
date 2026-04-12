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
              {format === 'maven2' && <MavenUploadHint />}
              {format === 'pypi' && <PypiUploadHint />}
              {format === 'npm' && <NpmUploadHint />}
              {format === 'raw' && <RawUploadWidget repo={selectedRepo} />}
              {!format && (
                <p className="text-sm text-gray-500">Select a repository above to see upload instructions.</p>
              )}
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

function MavenUploadHint() {
  return (
    <div className="space-y-4">
      <p className="text-sm text-gray-700">
        Use <code className="text-xs font-mono bg-gray-100 text-gray-700 px-1.5 py-0.5 rounded">mvn deploy</code> to upload Maven artifacts. Configure your <code className="text-xs font-mono bg-gray-100 text-gray-700 px-1.5 py-0.5 rounded">settings.xml</code>:
      </p>
      <pre className="bg-gray-900 text-gray-200 text-xs p-4 rounded-lg overflow-x-auto leading-relaxed">
{`<server>
  <id>megarepo</id>
  <username>admin</username>
  <password>your-password</password>
</server>`}
      </pre>
      <p className="text-xs text-gray-500">
        Then run: <code className="font-mono bg-gray-100 text-gray-700 px-1.5 py-0.5 rounded">mvn deploy -DaltDeploymentRepository=megarepo::default::http://localhost:8080/repository/maven-releases</code>
      </p>
    </div>
  );
}

function PypiUploadHint() {
  return (
    <div className="space-y-4">
      <p className="text-sm text-gray-700">
        Use <code className="text-xs font-mono bg-gray-100 text-gray-700 px-1.5 py-0.5 rounded">twine</code> to upload Python packages:
      </p>
      <pre className="bg-gray-900 text-gray-200 text-xs p-4 rounded-lg overflow-x-auto leading-relaxed">
{`twine upload --repository-url http://localhost:8080/repository/pypi-hosted/ dist/*`}
      </pre>
    </div>
  );
}

function NpmUploadHint() {
  return (
    <div className="space-y-4">
      <p className="text-sm text-gray-700">
        Configure npm registry and publish:
      </p>
      <pre className="bg-gray-900 text-gray-200 text-xs p-4 rounded-lg overflow-x-auto leading-relaxed">
{`npm config set registry http://localhost:8080/repository/npm-hosted/
npm publish`}
      </pre>
    </div>
  );
}

function RawUploadWidget({ repo }: { repo: string }) {
  const { showToast } = useToast();
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [file, setFile] = useState<File | null>(null);
  const [path, setPath] = useState('');
  const [uploading, setUploading] = useState(false);
  const [progress, setProgress] = useState(0);
  const [dragOver, setDragOver] = useState(false);

  const handleFile = useCallback((f: File) => {
    setFile(f);
    if (!path) {
      setPath(f.name);
    }
  }, [path]);

  function handleDrop(e: React.DragEvent) {
    e.preventDefault();
    setDragOver(false);
    const f = e.dataTransfer.files[0];
    if (f) handleFile(f);
  }

  function handleDragOver(e: React.DragEvent) {
    e.preventDefault();
    setDragOver(true);
  }

  function handleDragLeave(e: React.DragEvent) {
    e.preventDefault();
    setDragOver(false);
  }

  function handleFileInput(e: React.ChangeEvent<HTMLInputElement>) {
    const f = e.target.files?.[0];
    if (f) handleFile(f);
  }

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
      if (fileInputRef.current) fileInputRef.current.value = '';
    } catch (err) {
      showToast('error', err instanceof Error ? err.message : 'Upload failed');
    } finally {
      setUploading(false);
    }
  }

  return (
    <div className="space-y-5">
      {/* Drop zone */}
      <div
        onDrop={handleDrop}
        onDragOver={handleDragOver}
        onDragLeave={handleDragLeave}
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
          className="hidden"
          onChange={handleFileInput}
          disabled={uploading}
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
            <p className="text-xs text-gray-500">Any file type accepted</p>
          </div>
        )}
      </div>

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
            className="flex-1 px-3 py-2 border border-gray-300 rounded-md text-sm text-gray-700 bg-white focus:outline-none focus:border-blue-600 focus:ring-1 focus:ring-blue-600 transition-colors disabled:opacity-50"
          />
        </div>
      </div>

      {/* Progress bar */}
      {uploading && (
        <div className="space-y-1">
          <div className="flex justify-between text-xs text-gray-600">
            <span>Uploading...</span>
            <span>{progress}%</span>
          </div>
          <div className="w-full bg-gray-200 rounded-full h-2">
            <div
              className="bg-blue-600 h-2 rounded-full transition-all duration-300"
              style={{ width: `${progress}%` }}
            />
          </div>
        </div>
      )}

      {/* Upload button */}
      <button
        onClick={handleUpload}
        disabled={!file || !path.trim() || uploading}
        className="inline-flex items-center gap-1.5 px-4 py-2 bg-blue-600 hover:bg-blue-700 text-white text-sm font-medium rounded-md transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
      >
        {uploading ? 'Uploading...' : 'Upload'}
      </button>

      {/* CLI hint */}
      <details className="text-xs text-gray-500">
        <summary className="cursor-pointer hover:text-gray-700">Or use the CLI</summary>
        <pre className="mt-2 bg-gray-900 text-gray-200 text-xs p-4 rounded-lg overflow-x-auto leading-relaxed">
{`curl -u admin:password --upload-file myfile.zip \\
  http://localhost:8080/repository/${repo}/path/to/myfile.zip`}
        </pre>
      </details>
    </div>
  );
}

function formatSize(bytes: number): string {
  if (bytes < 1024) return bytes + ' B';
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
  if (bytes < 1024 * 1024 * 1024) return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
  return (bytes / (1024 * 1024 * 1024)).toFixed(1) + ' GB';
}
