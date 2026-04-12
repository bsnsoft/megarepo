import { useState } from 'react';
import { api, ApiError } from '../api/client';
import { useToast } from './Toast';
import FormatBadge from './FormatBadge';
import TypeBadge from './TypeBadge';

interface RepoPreview {
  name: string;
  format: string;
  type: string;
  remoteUrl: string | null;
  groupMembers: string[] | null;
  alreadyExists: boolean;
}

interface SkippedRepo {
  name: string;
  format: string;
  type: string;
  reason: string;
}

interface MigrationPreview {
  importable: RepoPreview[];
  skipped: SkippedRepo[];
}

interface MigrationResult {
  created: number;
  skippedExisting: number;
  skippedUnsupported: number;
  errors: string[];
}

interface NexusMigrationDialogProps {
  open: boolean;
  onClose: () => void;
  onComplete: () => void;
}

type Step = 'credentials' | 'preview' | 'executing' | 'done';

export default function NexusMigrationDialog({ open, onClose, onComplete }: NexusMigrationDialogProps) {
  const { showToast } = useToast();
  const [step, setStep] = useState<Step>('credentials');
  const [nexusUrl, setNexusUrl] = useState('');
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [preview, setPreview] = useState<MigrationPreview | null>(null);
  const [result, setResult] = useState<MigrationResult | null>(null);

  function reset() {
    setStep('credentials');
    setNexusUrl('');
    setUsername('');
    setPassword('');
    setLoading(false);
    setError(null);
    setPreview(null);
    setResult(null);
  }

  function handleClose() {
    reset();
    onClose();
  }

  async function handlePreview() {
    setLoading(true);
    setError(null);
    try {
      const data = await api.post<MigrationPreview>('/admin/migrate/nexus/preview', {
        nexusUrl,
        username,
        password,
      });
      setPreview(data);
      setStep('preview');
    } catch (err) {
      const msg = err instanceof ApiError ? err.message : 'Failed to connect to Nexus';
      setError(msg);
    } finally {
      setLoading(false);
    }
  }

  async function handleExecute() {
    setStep('executing');
    setError(null);
    try {
      const data = await api.post<MigrationResult>('/admin/migrate/nexus/execute', {
        nexusUrl,
        username,
        password,
      });
      setResult(data);
      setStep('done');
      if (data.created > 0) {
        showToast('success', `Successfully imported ${data.created} repositor${data.created === 1 ? 'y' : 'ies'} from Nexus`);
      }
      onComplete();
    } catch (err) {
      const msg = err instanceof ApiError ? err.message : 'Migration failed';
      setError(msg);
      setStep('preview');
    }
  }

  if (!open) return null;

  const importableCount = preview?.importable.filter((r) => !r.alreadyExists).length ?? 0;

  return (
    <div
      className="fixed inset-0 bg-black/40 backdrop-blur-[2px] flex items-center justify-center z-[1000] animate-[fadeIn_0.15s_ease]"
      onClick={handleClose}
    >
      <div
        className="bg-white rounded-lg w-[640px] max-w-[90vw] max-h-[80vh] shadow-lg animate-[slideUp_0.15s_ease] flex flex-col"
        onClick={(e) => e.stopPropagation()}
      >
        {/* Header */}
        <div className="px-6 py-4 border-b border-gray-200 flex items-center justify-between shrink-0">
          <div>
            <h3 className="text-base font-semibold text-gray-900">Bootstrap from Nexus</h3>
            <p className="text-xs text-gray-500 mt-0.5">Clone repository structure from a running Nexus instance</p>
          </div>
          <button
            className="p-1 text-gray-400 hover:text-gray-600 transition-colors"
            onClick={handleClose}
          >
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <path d="M18 6L6 18M6 6l12 12" />
            </svg>
          </button>
        </div>

        {/* Body */}
        <div className="px-6 py-4 overflow-y-auto flex-1">
          {step === 'credentials' && (
            <div className="space-y-4">
              <p className="text-sm text-gray-600">
                Enter your Nexus instance URL and admin credentials. MegaRepo will read the repository
                configuration and recreate matching repositories locally.
              </p>

              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1">Nexus URL</label>
                <input
                  type="url"
                  className="w-full px-3 py-2 border border-gray-300 rounded-md text-sm focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
                  placeholder="https://nexus.example.com"
                  value={nexusUrl}
                  onChange={(e) => setNexusUrl(e.target.value)}
                />
              </div>

              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className="block text-sm font-medium text-gray-700 mb-1">Username</label>
                  <input
                    type="text"
                    className="w-full px-3 py-2 border border-gray-300 rounded-md text-sm focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
                    placeholder="admin"
                    value={username}
                    onChange={(e) => setUsername(e.target.value)}
                  />
                </div>
                <div>
                  <label className="block text-sm font-medium text-gray-700 mb-1">Password</label>
                  <input
                    type="password"
                    className="w-full px-3 py-2 border border-gray-300 rounded-md text-sm focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
                    placeholder="Password"
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                  />
                </div>
              </div>

              {error && (
                <div className="px-3 py-2 bg-red-50 border border-red-200 rounded-md text-sm text-red-700">
                  {error}
                </div>
              )}
            </div>
          )}

          {step === 'preview' && preview && (
            <div className="space-y-4">
              {importableCount > 0 && (
                <div>
                  <h4 className="text-sm font-medium text-gray-900 mb-2">
                    Repositories to import ({importableCount})
                  </h4>
                  <div className="border border-gray-200 rounded-md overflow-hidden">
                    <table className="w-full text-sm">
                      <thead>
                        <tr className="bg-gray-50 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                          <th className="px-3 py-2">Name</th>
                          <th className="px-3 py-2">Format</th>
                          <th className="px-3 py-2">Type</th>
                          <th className="px-3 py-2">Details</th>
                        </tr>
                      </thead>
                      <tbody className="divide-y divide-gray-100">
                        {preview.importable
                          .filter((r) => !r.alreadyExists)
                          .map((repo) => (
                            <tr key={repo.name} className="hover:bg-gray-50">
                              <td className="px-3 py-2 font-medium text-gray-900">{repo.name}</td>
                              <td className="px-3 py-2"><FormatBadge format={repo.format} /></td>
                              <td className="px-3 py-2"><TypeBadge type={repo.type} /></td>
                              <td className="px-3 py-2 text-gray-500 text-xs truncate max-w-[180px]">
                                {repo.type === 'PROXY' && repo.remoteUrl && (
                                  <span title={repo.remoteUrl}>{repo.remoteUrl}</span>
                                )}
                                {repo.type === 'GROUP' && repo.groupMembers && (
                                  <span title={repo.groupMembers.join(', ')}>
                                    {repo.groupMembers.length} member{repo.groupMembers.length !== 1 ? 's' : ''}
                                  </span>
                                )}
                              </td>
                            </tr>
                          ))}
                      </tbody>
                    </table>
                  </div>
                </div>
              )}

              {preview.importable.some((r) => r.alreadyExists) && (
                <div>
                  <h4 className="text-sm font-medium text-amber-700 mb-2">
                    Already existing ({preview.importable.filter((r) => r.alreadyExists).length})
                  </h4>
                  <div className="px-3 py-2 bg-amber-50 border border-amber-200 rounded-md text-sm text-amber-700">
                    {preview.importable
                      .filter((r) => r.alreadyExists)
                      .map((r) => r.name)
                      .join(', ')}
                    {' '} -- these will be skipped.
                  </div>
                </div>
              )}

              {preview.skipped.length > 0 && (
                <div>
                  <h4 className="text-sm font-medium text-gray-500 mb-2">
                    Unsupported formats ({preview.skipped.length})
                  </h4>
                  <div className="px-3 py-2 bg-gray-50 border border-gray-200 rounded-md text-sm text-gray-600">
                    {preview.skipped.map((s) => (
                      <div key={s.name} className="flex justify-between py-0.5">
                        <span>{s.name} ({s.format})</span>
                        <span className="text-gray-400 text-xs">{s.reason}</span>
                      </div>
                    ))}
                  </div>
                </div>
              )}

              {importableCount === 0 && (
                <div className="px-3 py-2 bg-gray-50 border border-gray-200 rounded-md text-sm text-gray-600">
                  No new repositories to import. All supported repositories already exist in MegaRepo.
                </div>
              )}

              {error && (
                <div className="px-3 py-2 bg-red-50 border border-red-200 rounded-md text-sm text-red-700">
                  {error}
                </div>
              )}
            </div>
          )}

          {step === 'executing' && (
            <div className="flex flex-col items-center justify-center py-8">
              <svg className="animate-spin h-8 w-8 text-blue-600 mb-3" viewBox="0 0 24 24" fill="none">
                <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
                <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" />
              </svg>
              <p className="text-sm text-gray-600">Creating repositories...</p>
            </div>
          )}

          {step === 'done' && result && (
            <div className="space-y-3">
              <div className="px-4 py-3 bg-green-50 border border-green-200 rounded-md">
                <p className="text-sm font-medium text-green-800">
                  Migration complete
                </p>
                <ul className="mt-2 text-sm text-green-700 space-y-1">
                  <li>{result.created} repositor{result.created === 1 ? 'y' : 'ies'} created</li>
                  {result.skippedExisting > 0 && (
                    <li>{result.skippedExisting} skipped (already exist)</li>
                  )}
                  {result.skippedUnsupported > 0 && (
                    <li>{result.skippedUnsupported} skipped (unsupported format)</li>
                  )}
                </ul>
              </div>

              {result.errors.length > 0 && (
                <div className="px-4 py-3 bg-red-50 border border-red-200 rounded-md">
                  <p className="text-sm font-medium text-red-800">Errors</p>
                  <ul className="mt-1 text-sm text-red-700 space-y-0.5">
                    {result.errors.map((err, i) => (
                      <li key={i}>{err}</li>
                    ))}
                  </ul>
                </div>
              )}
            </div>
          )}
        </div>

        {/* Footer */}
        <div className="px-6 py-3 border-t border-gray-200 flex justify-end gap-2 shrink-0">
          {step === 'credentials' && (
            <>
              <button
                className="px-4 py-2 bg-white border border-gray-200 text-gray-700 text-sm font-medium rounded-md hover:bg-gray-50 transition-colors"
                onClick={handleClose}
              >
                Cancel
              </button>
              <button
                className="px-4 py-2 bg-blue-600 hover:bg-blue-700 text-white text-sm font-medium rounded-md transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                onClick={handlePreview}
                disabled={loading || !nexusUrl || !username || !password}
              >
                {loading ? 'Connecting...' : 'Preview'}
              </button>
            </>
          )}

          {step === 'preview' && (
            <>
              <button
                className="px-4 py-2 bg-white border border-gray-200 text-gray-700 text-sm font-medium rounded-md hover:bg-gray-50 transition-colors"
                onClick={() => setStep('credentials')}
              >
                Back
              </button>
              <button
                className="px-4 py-2 bg-blue-600 hover:bg-blue-700 text-white text-sm font-medium rounded-md transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                onClick={handleExecute}
                disabled={importableCount === 0}
              >
                Import {importableCount} repositor{importableCount === 1 ? 'y' : 'ies'}
              </button>
            </>
          )}

          {step === 'done' && (
            <button
              className="px-4 py-2 bg-blue-600 hover:bg-blue-700 text-white text-sm font-medium rounded-md transition-colors"
              onClick={handleClose}
            >
              Close
            </button>
          )}
        </div>
      </div>
    </div>
  );
}
