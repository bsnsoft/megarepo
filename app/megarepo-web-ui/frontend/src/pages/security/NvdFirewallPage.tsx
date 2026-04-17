import { useState, useEffect, useCallback, useRef } from 'react';
import { api } from '../../api/client';
import LoadingSpinner from '../../components/LoadingSpinner';
import { useToast } from '../../components/Toast';
import type {
  NvdFirewallSettings,
  NvdSyncState,
  NvdBlock,
  NvdWhitelistEntry,
} from '../../types/api';

const cvssColor = (score: number) => {
  if (score >= 9) return 'text-red-600 bg-red-50';
  if (score >= 7) return 'text-orange-600 bg-orange-50';
  if (score >= 4) return 'text-yellow-700 bg-yellow-50';
  return 'text-green-700 bg-green-50';
};

const formatRelative = (iso: string | null): string => {
  if (!iso) return 'never';
  const diff = Date.now() - new Date(iso).getTime();
  const s = Math.floor(diff / 1000);
  if (s < 60) return `${s}s ago`;
  const m = Math.floor(s / 60);
  if (m < 60) return `${m}m ago`;
  const h = Math.floor(m / 60);
  if (h < 24) return `${h}h ago`;
  return `${Math.floor(h / 24)}d ago`;
};

export default function NvdFirewallPage() {
  const { showToast } = useToast();

  const [settings, setSettings] = useState<NvdFirewallSettings | null>(null);
  const [syncState, setSyncState] = useState<NvdSyncState | null>(null);
  const [blocks, setBlocks] = useState<NvdBlock[]>([]);
  const [whitelist, setWhitelist] = useState<NvdWhitelistEntry[]>([]);

  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [triggeringSync, setTriggeringSync] = useState(false);

  const [newWl, setNewWl] = useState<{ entryType: 'COMPONENT' | 'CVE'; value: string; reason: string }>({
    entryType: 'COMPONENT',
    value: '',
    reason: '',
  });

  const pollingRef = useRef<number | null>(null);

  const loadAll = useCallback(async () => {
    try {
      const [s, state, bl, wl] = await Promise.all([
        api.get<NvdFirewallSettings>('/security/nvd-firewall'),
        api.get<NvdSyncState>('/security/nvd-firewall/sync-state'),
        api.get<NvdBlock[]>('/security/nvd-firewall/blocks?size=50'),
        api.get<NvdWhitelistEntry[]>('/security/nvd-firewall/whitelist'),
      ]);
      setSettings(s);
      setSyncState(state);
      setBlocks(bl);
      setWhitelist(wl);
    } catch {
      showToast('error', 'Failed to load NVD Firewall data');
    } finally {
      setLoading(false);
    }
  }, [showToast]);

  useEffect(() => {
    loadAll();
  }, [loadAll]);

  // Poll sync state every 2s while SYNCING
  useEffect(() => {
    const isSyncing = syncState?.status === 'SYNCING';
    if (isSyncing && pollingRef.current === null) {
      pollingRef.current = window.setInterval(async () => {
        try {
          const state = await api.get<NvdSyncState>('/security/nvd-firewall/sync-state');
          setSyncState(state);
          if (state.status !== 'SYNCING') {
            if (state.status === 'IDLE') showToast('success', `NVD sync complete (${state.totalCves.toLocaleString()} CVEs)`);
            else if (state.status === 'ERROR') showToast('error', `NVD sync failed: ${state.errorMessage}`);
          }
        } catch {
          // keep polling
        }
      }, 2000);
    }
    if (!isSyncing && pollingRef.current !== null) {
      window.clearInterval(pollingRef.current);
      pollingRef.current = null;
    }
    return () => {
      if (pollingRef.current !== null) {
        window.clearInterval(pollingRef.current);
        pollingRef.current = null;
      }
    };
  }, [syncState?.status, showToast]);

  async function handleSaveSettings() {
    if (!settings) return;
    setSaving(true);
    try {
      const saved = await api.put<NvdFirewallSettings>('/security/nvd-firewall', settings);
      setSettings(saved);
      showToast('success', 'Settings saved');
    } catch {
      showToast('error', 'Failed to save settings');
    } finally {
      setSaving(false);
    }
  }

  async function handleTriggerSync(mode: 'auto' | 'full' | 'delta') {
    setTriggeringSync(true);
    try {
      await api.post(`/security/nvd-firewall/sync?mode=${mode}`, {});
      const state = await api.get<NvdSyncState>('/security/nvd-firewall/sync-state');
      setSyncState(state);
      showToast('success', `Sync started (${mode})`);
    } catch (e) {
      const msg = e instanceof Error ? e.message : 'Failed to start sync';
      showToast('error', msg);
    } finally {
      setTriggeringSync(false);
    }
  }

  async function handleAddWhitelist() {
    if (!newWl.value.trim()) return;
    try {
      const added = await api.post<NvdWhitelistEntry>('/security/nvd-firewall/whitelist', newWl);
      setWhitelist((prev) => [added, ...prev]);
      setNewWl({ entryType: 'COMPONENT', value: '', reason: '' });
      showToast('success', 'Whitelist entry added');
    } catch {
      showToast('error', 'Failed to add whitelist entry (duplicate?)');
    }
  }

  async function handleDeleteWhitelist(id: number) {
    try {
      await api.delete(`/security/nvd-firewall/whitelist/${id}`);
      setWhitelist((prev) => prev.filter((e) => e.id !== id));
    } catch {
      showToast('error', 'Failed to delete whitelist entry');
    }
  }

  if (loading) {
    return (
      <div className="flex justify-center items-center py-20">
        <LoadingSpinner message="Loading NVD Firewall..." />
      </div>
    );
  }

  if (!settings || !syncState) return null;

  const syncProgress = syncState.totalResults && syncState.totalResults > 0
    ? (syncState.syncedCves / syncState.totalResults) * 100
    : 0;

  return (
    <div className="p-6 sm:p-8 max-w-7xl space-y-6">
      <div>
        <h1 className="text-2xl font-semibold text-gray-950">NVD Firewall</h1>
        <p className="text-sm text-gray-500 mt-1">
          Block artifact downloads with known vulnerabilities above a configured CVSS threshold using a local mirror of the NIST National Vulnerability Database.
        </p>
      </div>

      {/* ── Sync Status Card ────────────────────────────────────────── */}
      <div className="bg-white rounded-lg border border-gray-200 overflow-hidden">
        <div className="p-6">
          <div className="flex items-start justify-between mb-4 gap-4">
            <div>
              <h2 className="text-lg font-semibold text-gray-950">Local CVE Mirror</h2>
              <p className="text-xs text-gray-500 mt-0.5">
                Downloads all CVE data from NVD to a local database. Lookups are offline and instant.
              </p>
            </div>
            <div className="flex gap-2">
              <button
                className="px-3 py-1.5 text-xs font-medium rounded-md border border-gray-300 bg-white hover:bg-gray-50 disabled:opacity-50"
                onClick={() => handleTriggerSync('delta')}
                disabled={triggeringSync || syncState.status === 'SYNCING' || !syncState.lastSuccessAt}
                title={!syncState.lastSuccessAt ? 'Run a full sync first' : 'Fetch only CVEs modified since last sync'}
              >
                Delta Sync
              </button>
              <button
                className="px-3 py-1.5 text-xs font-medium rounded-md bg-blue-600 hover:bg-blue-700 text-white disabled:opacity-50"
                onClick={() => handleTriggerSync(syncState.lastSuccessAt ? 'delta' : 'full')}
                disabled={triggeringSync || syncState.status === 'SYNCING'}
              >
                {syncState.status === 'SYNCING' ? 'Syncing…' : syncState.lastSuccessAt ? 'Sync Now' : 'Initial Sync'}
              </button>
            </div>
          </div>

          <div className="grid grid-cols-2 md:grid-cols-4 gap-4 mb-4">
            <Stat label="Status" value={
              <span className={`inline-flex items-center gap-1.5 text-sm font-medium ${
                syncState.status === 'SYNCING' ? 'text-blue-600' :
                syncState.status === 'ERROR' ? 'text-red-600' : 'text-green-700'
              }`}>
                {syncState.status === 'SYNCING' && (
                  <span className="w-2 h-2 rounded-full bg-blue-600 animate-pulse" />
                )}
                {syncState.status === 'IDLE' ? 'Idle' : syncState.status === 'SYNCING' ? `Syncing (${syncState.mode})` : 'Error'}
              </span>
            } />
            <Stat label="CVEs in DB" value={<span className="text-sm font-mono font-semibold text-gray-950">{syncState.totalCves.toLocaleString()}</span>} />
            <Stat label="Last sync" value={<span className="text-sm text-gray-700">{formatRelative(syncState.lastSuccessAt)}</span>} />
            <Stat label="Last attempt" value={<span className="text-sm text-gray-700">{formatRelative(syncState.lastSyncAt)}</span>} />
          </div>

          {syncState.status === 'SYNCING' && (
            <div className="mt-3">
              <div className="flex justify-between items-center text-xs text-gray-600 mb-1.5">
                <span>
                  {syncState.syncedCves.toLocaleString()}
                  {syncState.totalResults ? ` / ${syncState.totalResults.toLocaleString()}` : ''} CVEs
                </span>
                <span>{syncState.totalResults ? `${syncProgress.toFixed(1)}%` : '…'}</span>
              </div>
              <div className="h-2 bg-gray-200 rounded-full overflow-hidden">
                <div
                  className="h-full bg-blue-600 transition-all duration-500"
                  style={{ width: syncState.totalResults ? `${syncProgress}%` : '10%' }}
                />
              </div>
              <p className="text-xs text-gray-500 mt-2">
                This runs once at setup (~2–3 min with API key), then incrementally once a day.
              </p>
            </div>
          )}

          {syncState.status === 'ERROR' && syncState.errorMessage && (
            <div className="mt-3 p-3 bg-red-50 border border-red-200 rounded text-xs text-red-800">
              <strong>Last error:</strong> {syncState.errorMessage}
            </div>
          )}
        </div>
      </div>

      {/* ── Settings ────────────────────────────────────────────────── */}
      <div className="bg-white rounded-lg border border-gray-200">
        <div className="p-6">
          <h2 className="text-lg font-semibold text-gray-950 mb-4">Settings</h2>

          <div className="mb-4">
            <label className="flex items-center gap-2.5 cursor-pointer">
              <input
                type="checkbox"
                checked={settings.enabled}
                onChange={(e) => setSettings({ ...settings, enabled: e.target.checked })}
                className="w-4 h-4 rounded border-gray-300 text-blue-600 focus:ring-blue-600"
              />
              <span className="text-sm font-medium text-gray-700">Enable NVD Firewall (block downloads above threshold)</span>
            </label>
          </div>

          <div className="mb-4">
            <label htmlFor="nvd-api-key" className="block text-sm font-medium text-gray-700 mb-1.5">
              NVD API Key
            </label>
            <input
              id="nvd-api-key"
              type="password"
              value={settings.apiKey ?? ''}
              onChange={(e) => setSettings({ ...settings, apiKey: e.target.value || null })}
              placeholder="xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
              className="w-full max-w-sm px-3 py-2 border border-gray-300 rounded-md text-sm"
            />
            <p className="text-xs text-gray-400 mt-1">
              Free at{' '}
              <a href="https://nvd.nist.gov/developers/request-an-api-key" target="_blank" rel="noopener noreferrer" className="text-blue-600 hover:underline">
                nvd.nist.gov
              </a>
              {' '}— raises rate limit from 5 to 50 requests/30s.
            </p>
          </div>

          <div className="mb-4">
            <label htmlFor="cvss-threshold" className="block text-sm font-medium text-gray-700 mb-1.5">
              CVSS Score Threshold
            </label>
            <div className="flex items-center gap-3 max-w-sm">
              <input
                id="cvss-threshold"
                type="range"
                min="0"
                max="10"
                step="0.1"
                value={settings.cvssThreshold}
                onChange={(e) => setSettings({ ...settings, cvssThreshold: parseFloat(e.target.value) })}
                className="flex-1"
              />
              <span className={`text-sm font-mono font-semibold min-w-[3ch] text-right px-2 py-0.5 rounded ${cvssColor(settings.cvssThreshold)}`}>
                {settings.cvssThreshold.toFixed(1)}
              </span>
            </div>
            <p className="text-xs text-gray-400 mt-1">
              Block anything with a CVE at or above this score. Typical: 7.0 (HIGH) or 9.0 (CRITICAL).
            </p>
          </div>

          <div className="flex justify-end pt-4 border-t border-gray-200">
            <button
              className="px-4 py-2 bg-blue-600 hover:bg-blue-700 text-white text-sm font-medium rounded-md disabled:opacity-50"
              onClick={handleSaveSettings}
              disabled={saving}
            >
              {saving ? 'Saving…' : 'Save Settings'}
            </button>
          </div>
        </div>
      </div>

      {/* ── Blocks Log ──────────────────────────────────────────────── */}
      <div className="bg-white rounded-lg border border-gray-200">
        <div className="p-6">
          <h2 className="text-lg font-semibold text-gray-950 mb-1">Blocked Downloads</h2>
          <p className="text-xs text-gray-500 mb-4">
            Last 50 block events. If nothing appears here after enabling, nobody has triggered a download yet.
          </p>
          {blocks.length === 0 ? (
            <p className="text-sm text-gray-400 italic">No blocks recorded.</p>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="text-left text-xs font-medium text-gray-500 border-b border-gray-200">
                    <th className="py-2 pr-4">When</th>
                    <th className="py-2 pr-4">User</th>
                    <th className="py-2 pr-4">Component</th>
                    <th className="py-2 pr-4">Score</th>
                    <th className="py-2">CVEs</th>
                  </tr>
                </thead>
                <tbody>
                  {blocks.map((b) => (
                    <tr key={b.id} className="border-b border-gray-100">
                      <td className="py-2 pr-4 text-xs text-gray-600">{formatRelative(b.timestamp)}</td>
                      <td className="py-2 pr-4 text-gray-700">{b.userId ?? 'anonymous'}</td>
                      <td className="py-2 pr-4 font-mono text-xs text-gray-700">{b.componentKey}</td>
                      <td className="py-2 pr-4">
                        <span className={`inline-block px-2 py-0.5 rounded text-xs font-mono font-semibold ${cvssColor(b.maxCvssScore)}`}>
                          {b.maxCvssScore.toFixed(1)}
                        </span>
                      </td>
                      <td className="py-2 text-xs">
                        {b.cveDetails.slice(0, 3).map((c) => (
                          <a
                            key={c.cveId}
                            href={`https://nvd.nist.gov/vuln/detail/${c.cveId}`}
                            target="_blank"
                            rel="noopener noreferrer"
                            className="inline-block mr-2 text-blue-600 hover:underline font-mono"
                          >
                            {c.cveId}
                          </a>
                        ))}
                        {b.cveDetails.length > 3 && <span className="text-gray-400">+{b.cveDetails.length - 3} more</span>}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </div>

      {/* ── Whitelist ───────────────────────────────────────────────── */}
      <div className="bg-white rounded-lg border border-gray-200">
        <div className="p-6">
          <h2 className="text-lg font-semibold text-gray-950 mb-1">Whitelist</h2>
          <p className="text-xs text-gray-500 mb-4">
            Components or specific CVE-IDs that bypass the firewall. Use component prefix without version
            (<code className="bg-gray-100 px-1">maven2:group:artifact</code>) to whitelist all versions.
          </p>

          <div className="flex gap-2 mb-4">
            <select
              value={newWl.entryType}
              onChange={(e) => setNewWl({ ...newWl, entryType: e.target.value as 'COMPONENT' | 'CVE' })}
              className="px-3 py-2 border border-gray-300 rounded-md text-sm"
            >
              <option value="COMPONENT">Component</option>
              <option value="CVE">CVE-ID</option>
            </select>
            <input
              type="text"
              placeholder={newWl.entryType === 'CVE' ? 'CVE-2021-44228' : 'maven2:org.apache.logging.log4j:log4j-core'}
              value={newWl.value}
              onChange={(e) => setNewWl({ ...newWl, value: e.target.value })}
              className="flex-1 px-3 py-2 border border-gray-300 rounded-md text-sm font-mono"
            />
            <input
              type="text"
              placeholder="Reason (optional)"
              value={newWl.reason}
              onChange={(e) => setNewWl({ ...newWl, reason: e.target.value })}
              className="flex-1 px-3 py-2 border border-gray-300 rounded-md text-sm"
            />
            <button
              onClick={handleAddWhitelist}
              className="px-4 py-2 bg-gray-900 hover:bg-gray-800 text-white text-sm font-medium rounded-md"
            >
              Add
            </button>
          </div>

          {whitelist.length === 0 ? (
            <p className="text-sm text-gray-400 italic">No whitelist entries.</p>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="text-left text-xs font-medium text-gray-500 border-b border-gray-200">
                    <th className="py-2 pr-4">Type</th>
                    <th className="py-2 pr-4">Value</th>
                    <th className="py-2 pr-4">Reason</th>
                    <th className="py-2 pr-4">Added</th>
                    <th className="py-2"></th>
                  </tr>
                </thead>
                <tbody>
                  {whitelist.map((w) => (
                    <tr key={w.id} className="border-b border-gray-100">
                      <td className="py-2 pr-4">
                        <span className={`inline-block px-2 py-0.5 rounded text-xs font-medium ${
                          w.entryType === 'CVE' ? 'bg-purple-50 text-purple-700' : 'bg-blue-50 text-blue-700'
                        }`}>
                          {w.entryType}
                        </span>
                      </td>
                      <td className="py-2 pr-4 font-mono text-xs">{w.value}</td>
                      <td className="py-2 pr-4 text-gray-600 text-xs">{w.reason ?? '—'}</td>
                      <td className="py-2 pr-4 text-xs text-gray-500">
                        {formatRelative(w.addedAt)} by {w.addedBy ?? 'unknown'}
                      </td>
                      <td className="py-2 text-right">
                        <button
                          onClick={() => handleDeleteWhitelist(w.id)}
                          className="text-xs text-red-600 hover:text-red-800"
                        >
                          Remove
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

function Stat({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <div>
      <div className="text-xs text-gray-500 uppercase tracking-wide mb-1">{label}</div>
      <div>{value}</div>
    </div>
  );
}
