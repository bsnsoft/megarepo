import { useState, useCallback } from 'react';
import { api } from '../api/client';
import { useToast } from './Toast';

interface ImportPresetDialogProps {
  open: boolean;
  onClose: () => void;
  onImported: () => void;
}

interface ImportResult {
  created: string[];
  skipped: string[];
}

type InputMode = 'preset' | 'file' | 'paste';

const PRESETS: Record<string, { label: string; description: string; yaml: string }> = {
  default: {
    label: 'Default (18 repos)',
    description: 'Maven, npm, PyPI, NuGet, Raw, and Docker repositories with proxy, hosted, and group configurations.',
    yaml: `# MegaRepo Default Repository Setup
repositories:
  # Maven
  - name: maven-central
    format: maven2
    type: PROXY
    remoteUrl: https://repo1.maven.org/maven2/
  - name: maven-releases
    format: maven2
    type: HOSTED
  - name: maven-snapshots
    format: maven2
    type: HOSTED
  - name: maven-public
    format: maven2
    type: GROUP
    members: [maven-central, maven-releases, maven-snapshots]

  # npm
  - name: npm-proxy
    format: npm
    type: PROXY
    remoteUrl: https://registry.npmjs.org/
  - name: npm-hosted
    format: npm
    type: HOSTED
  - name: npm-public
    format: npm
    type: GROUP
    members: [npm-proxy, npm-hosted]

  # PyPI
  - name: pypi-proxy
    format: pypi
    type: PROXY
    remoteUrl: https://pypi.org/simple/
  - name: pypi-hosted
    format: pypi
    type: HOSTED
  - name: pypi-public
    format: pypi
    type: GROUP
    members: [pypi-proxy, pypi-hosted]

  # NuGet
  - name: nuget-proxy
    format: nuget
    type: PROXY
    remoteUrl: https://api.nuget.org/v3/index.json
  - name: nuget-hosted
    format: nuget
    type: HOSTED
  - name: nuget-public
    format: nuget
    type: GROUP
    members: [nuget-proxy, nuget-hosted]

  # Raw
  - name: raw-hosted
    format: raw
    type: HOSTED

  # Docker
  - name: docker-hosted
    format: docker
    type: HOSTED
  - name: docker-hub-proxy
    format: docker
    type: PROXY
    remoteUrl: https://registry-1.docker.io/
  - name: docker-public
    format: docker
    type: GROUP
    members: [docker-hub-proxy, docker-hosted]`,
  },
  java: {
    label: 'Java Team (Maven only)',
    description: 'Maven Central proxy, hosted releases/snapshots, and a public group.',
    yaml: `# MegaRepo Java Team Setup
repositories:
  - name: maven-central
    format: maven2
    type: PROXY
    remoteUrl: https://repo1.maven.org/maven2/
  - name: maven-releases
    format: maven2
    type: HOSTED
  - name: maven-snapshots
    format: maven2
    type: HOSTED
  - name: maven-public
    format: maven2
    type: GROUP
    members: [maven-central, maven-releases, maven-snapshots]`,
  },
  python: {
    label: 'Python Team (PyPI only)',
    description: 'PyPI proxy, hosted repository, and a public group.',
    yaml: `# MegaRepo Python Team Setup
repositories:
  - name: pypi-proxy
    format: pypi
    type: PROXY
    remoteUrl: https://pypi.org/simple/
  - name: pypi-hosted
    format: pypi
    type: HOSTED
  - name: pypi-public
    format: pypi
    type: GROUP
    members: [pypi-proxy, pypi-hosted]`,
  },
};

function parseRepoNames(yaml: string): { name: string; format: string; type: string }[] {
  const repos: { name: string; format: string; type: string }[] = [];
  const lines = yaml.split('\n');
  let current: Partial<{ name: string; format: string; type: string }> = {};

  for (const line of lines) {
    const trimmed = line.trim();
    if (trimmed.startsWith('- name:')) {
      if (current.name) repos.push(current as { name: string; format: string; type: string });
      current = { name: trimmed.replace('- name:', '').trim() };
    } else if (trimmed.startsWith('format:') && current.name) {
      current.format = trimmed.replace('format:', '').trim();
    } else if (trimmed.startsWith('type:') && current.name) {
      current.type = trimmed.replace('type:', '').trim();
    }
  }
  if (current.name) repos.push(current as { name: string; format: string; type: string });
  return repos;
}

const TYPE_COLORS: Record<string, string> = {
  HOSTED: 'bg-emerald-100 text-emerald-700',
  PROXY: 'bg-blue-100 text-blue-700',
  GROUP: 'bg-purple-100 text-purple-700',
};

export default function ImportPresetDialog({ open, onClose, onImported }: ImportPresetDialogProps) {
  const { showToast } = useToast();
  const [mode, setMode] = useState<InputMode>('preset');
  const [selectedPreset, setSelectedPreset] = useState<string>('default');
  const [pastedYaml, setPastedYaml] = useState('');
  const [fileYaml, setFileYaml] = useState('');
  const [fileName, setFileName] = useState('');
  const [installing, setInstalling] = useState(false);

  const activeYaml =
    mode === 'preset' ? PRESETS[selectedPreset]?.yaml ?? '' : mode === 'file' ? fileYaml : pastedYaml;

  const preview = activeYaml ? parseRepoNames(activeYaml) : [];

  const handleFileChange = useCallback((e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    setFileName(file.name);
    const reader = new FileReader();
    reader.onload = (ev) => {
      setFileYaml(ev.target?.result as string);
    };
    reader.readAsText(file);
  }, []);

  async function handleInstall() {
    if (!activeYaml.trim()) {
      showToast('error', 'No YAML content to import');
      return;
    }
    setInstalling(true);
    try {
      const result = await api.postText<ImportResult>('/admin/import-repos', activeYaml, 'text/yaml');
      const parts: string[] = [];
      if (result.created.length > 0) parts.push(`${result.created.length} created`);
      if (result.skipped.length > 0) parts.push(`${result.skipped.length} skipped (already exist)`);
      showToast('success', `Import complete: ${parts.join(', ')}`);
      onImported();
      onClose();
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Import failed';
      showToast('error', msg);
    } finally {
      setInstalling(false);
    }
  }

  if (!open) return null;

  return (
    <div
      className="fixed inset-0 bg-black/40 backdrop-blur-[2px] flex items-center justify-center z-[1000] animate-[fadeIn_0.15s_ease]"
      onClick={onClose}
    >
      <div
        className="bg-white rounded-lg w-[600px] max-w-[92vw] max-h-[85vh] shadow-lg animate-[slideUp_0.15s_ease] flex flex-col"
        onClick={(e) => e.stopPropagation()}
      >
        {/* Header */}
        <div className="px-6 py-4 border-b border-gray-200">
          <h3 className="text-base font-semibold text-gray-900">Import Repository Preset</h3>
          <p className="text-sm text-gray-500 mt-0.5">
            Quickly set up repositories from a built-in preset or custom YAML.
          </p>
        </div>

        {/* Body */}
        <div className="px-6 py-4 overflow-y-auto flex-1">
          {/* Mode tabs */}
          <div className="flex gap-1 mb-4 bg-gray-100 rounded-md p-0.5">
            {([
              ['preset', 'Built-in Preset'],
              ['file', 'Upload YAML'],
              ['paste', 'Paste YAML'],
            ] as [InputMode, string][]).map(([m, label]) => (
              <button
                key={m}
                className={`flex-1 px-3 py-1.5 text-sm font-medium rounded transition-colors ${
                  mode === m ? 'bg-white text-gray-900 shadow-sm' : 'text-gray-500 hover:text-gray-700'
                }`}
                onClick={() => setMode(m)}
              >
                {label}
              </button>
            ))}
          </div>

          {/* Mode content */}
          {mode === 'preset' && (
            <div className="space-y-2">
              {Object.entries(PRESETS).map(([key, preset]) => (
                <label
                  key={key}
                  className={`flex items-start gap-3 p-3 rounded-lg border cursor-pointer transition-colors ${
                    selectedPreset === key
                      ? 'border-blue-500 bg-blue-50/50'
                      : 'border-gray-200 hover:border-gray-300'
                  }`}
                >
                  <input
                    type="radio"
                    name="preset"
                    checked={selectedPreset === key}
                    onChange={() => setSelectedPreset(key)}
                    className="mt-0.5"
                  />
                  <div>
                    <div className="text-sm font-medium text-gray-900">{preset.label}</div>
                    <div className="text-xs text-gray-500 mt-0.5">{preset.description}</div>
                  </div>
                </label>
              ))}
            </div>
          )}

          {mode === 'file' && (
            <div>
              <label className="flex flex-col items-center justify-center gap-2 p-6 border-2 border-dashed border-gray-300 rounded-lg cursor-pointer hover:border-blue-400 transition-colors">
                <svg
                  className="w-8 h-8 text-gray-400"
                  fill="none"
                  stroke="currentColor"
                  viewBox="0 0 24 24"
                  strokeWidth="1.5"
                >
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    d="M3 16.5v2.25A2.25 2.25 0 005.25 21h13.5A2.25 2.25 0 0021 18.75V16.5m-13.5-9L12 3m0 0l4.5 4.5M12 3v13.5"
                  />
                </svg>
                <span className="text-sm text-gray-600">
                  {fileName || 'Choose a .yml or .yaml file'}
                </span>
                <input
                  type="file"
                  accept=".yml,.yaml"
                  className="hidden"
                  onChange={handleFileChange}
                />
              </label>
            </div>
          )}

          {mode === 'paste' && (
            <textarea
              className="w-full h-40 p-3 border border-gray-300 rounded-lg font-mono text-xs resize-y focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent"
              placeholder={`repositories:\n  - name: my-repo\n    format: maven2\n    type: HOSTED`}
              value={pastedYaml}
              onChange={(e) => setPastedYaml(e.target.value)}
            />
          )}

          {/* Preview */}
          {preview.length > 0 && (
            <div className="mt-4">
              <h4 className="text-xs font-semibold text-gray-500 uppercase tracking-wide mb-2">
                Preview ({preview.length} repositories)
              </h4>
              <div className="border border-gray-200 rounded-lg divide-y divide-gray-100 max-h-48 overflow-y-auto">
                {preview.map((repo) => (
                  <div key={repo.name} className="flex items-center gap-2 px-3 py-2 text-sm">
                    <span className="font-medium text-gray-900 flex-1">{repo.name}</span>
                    <span
                      className={`px-1.5 py-0.5 rounded text-xs font-medium ${TYPE_COLORS[repo.type] ?? 'bg-gray-100 text-gray-600'}`}
                    >
                      {repo.type}
                    </span>
                    <span className="text-xs text-gray-500 w-16 text-right">{repo.format}</span>
                  </div>
                ))}
              </div>
            </div>
          )}
        </div>

        {/* Footer */}
        <div className="px-6 py-4 border-t border-gray-200 flex gap-2 justify-end">
          <button
            className="inline-flex items-center justify-center px-4 py-2 bg-white border border-gray-200 text-gray-700 text-sm font-medium rounded-md hover:bg-gray-50 transition-colors"
            onClick={onClose}
          >
            Cancel
          </button>
          <button
            className="inline-flex items-center justify-center px-4 py-2 bg-blue-600 hover:bg-blue-700 text-white text-sm font-medium rounded-md transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
            disabled={installing || !activeYaml.trim()}
            onClick={handleInstall}
          >
            {installing ? 'Installing...' : `Install ${preview.length > 0 ? `(${preview.length} repos)` : ''}`}
          </button>
        </div>
      </div>
    </div>
  );
}
