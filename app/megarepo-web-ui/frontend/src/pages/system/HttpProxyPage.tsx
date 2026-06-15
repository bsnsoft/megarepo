import { useState, useEffect, useCallback } from 'react';
import { api } from '../../api/client';
import LoadingSpinner from '../../components/LoadingSpinner';
import ErrorState from '../../components/ErrorState';
import { useToast } from '../../components/Toast';
import type { OutboundProxySettings } from '../../types/api';

const inputStyle: React.CSSProperties = {
  width: '100%',
  padding: '8px 12px',
  border: '1px solid #d1d5db',
  borderRadius: '6px',
  fontSize: '14px',
  color: '#374151',
  backgroundColor: '#fff',
  outline: 'none',
  transition: 'border-color 0.15s, box-shadow 0.15s',
};

const inputFocusClass = 'focus:border-blue-600 focus:ring-1 focus:ring-blue-600';

interface FormState {
  enabled: boolean;
  host: string;
  port: number;
  username: string;
  password: string; // write-only: blank means "keep stored"
  nonProxyHosts: string;
}

export default function HttpProxyPage() {
  const { showToast } = useToast();

  const [settings, setSettings] = useState<OutboundProxySettings | null>(null);
  const [form, setForm] = useState<FormState | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  const load = useCallback(() => {
    setLoading(true);
    setLoadError(null);
    api
      .get<OutboundProxySettings>('/system/http-proxy')
      .then((data) => {
        setSettings(data);
        setForm({
          enabled: data.enabled,
          host: data.host ?? '',
          port: data.port || 3128,
          username: data.username ?? '',
          password: '',
          nonProxyHosts: data.nonProxyHosts ?? '',
        });
      })
      .catch((err) => setLoadError(err instanceof Error ? err.message : 'Failed to load proxy settings'))
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  async function handleSave() {
    if (!form) return;
    if (form.enabled && !form.host.trim()) {
      showToast('error', 'A proxy host is required when the proxy is enabled');
      return;
    }
    if (form.port < 1 || form.port > 65535) {
      showToast('error', 'Port must be between 1 and 65535');
      return;
    }

    setSaving(true);
    try {
      const saved = await api.put<OutboundProxySettings>('/system/http-proxy', {
        enabled: form.enabled,
        host: form.host.trim() || null,
        port: form.port,
        username: form.username.trim() || null,
        // Only send a password if the user typed one; blank keeps the stored one.
        password: form.password ? form.password : null,
        passwordSet: false,
        nonProxyHosts: form.nonProxyHosts.trim() || null,
        configured: true,
        source: 'database',
      });
      setSettings(saved);
      setForm({
        enabled: saved.enabled,
        host: saved.host ?? '',
        port: saved.port || 3128,
        username: saved.username ?? '',
        password: '',
        nonProxyHosts: saved.nonProxyHosts ?? '',
      });
      showToast('success', 'Proxy settings saved');
    } catch (err: unknown) {
      showToast('error', err instanceof Error ? err.message : 'Failed to save proxy settings');
    } finally {
      setSaving(false);
    }
  }

  if (loading) {
    return (
      <div className="flex justify-center items-center py-20">
        <LoadingSpinner message="Loading proxy settings..." />
      </div>
    );
  }

  if (loadError || !form || !settings) {
    return <ErrorState title="Failed to load proxy settings" message={loadError ?? 'Unknown error'} onRetry={load} />;
  }

  return (
    <div className="p-6 sm:p-8 max-w-3xl">
      <div className="mb-6">
        <h1 className="text-2xl font-semibold text-gray-950">HTTP Proxy</h1>
        <p className="text-sm text-gray-500 mt-1">
          Route all upstream fetches from proxy repositories (Maven Central, npmjs.org,
          api.nuget.org, …) through a corporate forward proxy.
        </p>
      </div>

      <div className="space-y-6">
        <div className="bg-white rounded-lg border border-gray-200 overflow-hidden">
          <div className="px-6 py-4 bg-gray-50 border-b border-gray-200 flex items-center justify-between">
            <h2 className="text-sm font-semibold text-gray-700">Outbound Proxy</h2>
            <span
              className={`inline-flex items-center px-2.5 py-0.5 text-xs font-medium rounded-full ${
                settings.source === 'database'
                  ? 'bg-blue-50 text-blue-700'
                  : 'bg-gray-100 text-gray-600'
              }`}
              title={
                settings.source === 'database'
                  ? 'Active configuration is managed here in the UI.'
                  : 'No UI configuration yet — the deployment-side (Helm/env) configuration applies.'
              }
            >
              {settings.source === 'database' ? 'Managed in UI' : 'Using environment defaults'}
            </span>
          </div>

          <div className="p-6 space-y-5">
            <label className="flex items-center gap-3 cursor-pointer">
              <input
                type="checkbox"
                checked={form.enabled}
                onChange={(e) => setForm({ ...form, enabled: e.target.checked })}
                className="h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-600"
              />
              <span className="text-sm font-medium text-gray-700">Route upstream traffic through a forward proxy</span>
            </label>

            <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
              <div className="sm:col-span-2">
                <label className="block text-sm font-medium text-gray-700 mb-1.5">Host</label>
                <input
                  type="text"
                  value={form.host}
                  onChange={(e) => setForm({ ...form, host: e.target.value })}
                  placeholder="proxy.corp.example.com"
                  style={inputStyle}
                  className={inputFocusClass}
                  disabled={!form.enabled}
                />
              </div>
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1.5">Port</label>
                <input
                  type="number"
                  min={1}
                  max={65535}
                  value={form.port}
                  onChange={(e) => setForm({ ...form, port: Number(e.target.value) })}
                  style={inputStyle}
                  className={inputFocusClass}
                  disabled={!form.enabled}
                />
              </div>
            </div>

            <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1.5">
                  Username <span className="text-gray-400 font-normal">(optional)</span>
                </label>
                <input
                  type="text"
                  value={form.username}
                  onChange={(e) => setForm({ ...form, username: e.target.value })}
                  placeholder="Proxy username"
                  autoComplete="off"
                  style={inputStyle}
                  className={inputFocusClass}
                  disabled={!form.enabled}
                />
              </div>
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1.5">
                  Password <span className="text-gray-400 font-normal">(optional)</span>
                </label>
                <input
                  type="password"
                  value={form.password}
                  onChange={(e) => setForm({ ...form, password: e.target.value })}
                  placeholder={settings.passwordSet ? '•••••••• (unchanged)' : 'Proxy password'}
                  autoComplete="new-password"
                  style={inputStyle}
                  className={inputFocusClass}
                  disabled={!form.enabled}
                />
                {settings.passwordSet && (
                  <p className="text-xs text-gray-400 mt-1">A password is stored. Leave blank to keep it unchanged.</p>
                )}
              </div>
            </div>

            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1.5">
                Non-proxy hosts <span className="text-gray-400 font-normal">(optional)</span>
              </label>
              <input
                type="text"
                value={form.nonProxyHosts}
                onChange={(e) => setForm({ ...form, nonProxyHosts: e.target.value })}
                placeholder="localhost, *.internal.example.com"
                style={inputStyle}
                className={inputFocusClass}
                disabled={!form.enabled}
              />
              <p className="text-xs text-gray-400 mt-1">
                Comma-separated host patterns that bypass the proxy. The <code>*</code> wildcard is supported.
              </p>
            </div>

            <div className="rounded-md bg-gray-50 border border-gray-200 px-4 py-3 text-[13px] text-gray-600">
              Until saved here, the deployment-side configuration
              (<code className="px-1 py-0.5 bg-gray-100 rounded">megarepo.outbound-proxy.*</code> via Helm/env)
              applies. Saving takes precedence and takes effect immediately — no restart needed.
              The password is never sent back to the browser.
            </div>

            <div className="flex justify-end pt-1">
              <button
                type="button"
                className="inline-flex items-center px-4 py-2 bg-blue-600 hover:bg-blue-700 text-white text-sm font-medium rounded-md transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                onClick={handleSave}
                disabled={saving}
              >
                {saving ? 'Saving...' : 'Save Changes'}
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
