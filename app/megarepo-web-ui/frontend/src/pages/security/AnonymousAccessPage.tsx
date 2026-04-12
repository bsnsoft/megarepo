import { useState, useEffect } from 'react';
import { api } from '../../api/client';
import LoadingSpinner from '../../components/LoadingSpinner';
import { useToast } from '../../components/Toast';
import type { AnonymousAccessSettings } from '../../types/api';

export default function AnonymousAccessPage() {
  const { showToast } = useToast();
  const [settings, setSettings] = useState<AnonymousAccessSettings | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    api
      .get<AnonymousAccessSettings>('/security/anonymous')
      .then(setSettings)
      .catch(() => showToast('error', 'Failed to load anonymous access settings'))
      .finally(() => setLoading(false));
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  async function handleSave() {
    if (!settings) return;
    setSaving(true);
    try {
      await api.put('/security/anonymous', settings);
      showToast('success', 'Anonymous access settings saved');
    } catch {
      showToast('error', 'Failed to save settings');
    } finally {
      setSaving(false);
    }
  }

  if (loading) {
    return (
      <div className="flex justify-center items-center py-20">
        <LoadingSpinner message="Loading settings..." />
      </div>
    );
  }

  if (!settings) return null;

  return (
    <div className="p-6 sm:p-8 max-w-7xl">
      <div className="flex items-start justify-between mb-6 gap-4">
        <div>
          <h1 className="text-2xl font-semibold text-gray-950">Anonymous Access</h1>
          <p className="text-sm text-gray-500 mt-1">Configure anonymous/guest access to repositories</p>
        </div>
      </div>

      <div className="bg-white rounded-lg border border-gray-200 overflow-hidden">
        <div className="p-6">
          <div className="mb-5">
            <label className="flex items-center gap-2.5 cursor-pointer">
              <input
                type="checkbox"
                checked={settings.enabled}
                onChange={(e) => setSettings({ ...settings, enabled: e.target.checked })}
                className="w-4 h-4 rounded border-gray-300 text-blue-600 focus:ring-blue-600"
              />
              <span className="text-sm font-medium text-gray-700">
                Allow anonymous users to access the server
              </span>
            </label>
          </div>

          <div className="mb-5">
            <label htmlFor="anon-user" className="block text-sm font-medium text-gray-700 mb-1.5">
              Anonymous User ID
            </label>
            <input
              id="anon-user"
              type="text"
              value={settings.userId}
              onChange={(e) => setSettings({ ...settings, userId: e.target.value })}
              className="w-full max-w-sm px-3 py-2 border border-gray-300 rounded-md text-sm text-gray-700 bg-white focus:outline-none focus:border-blue-600 focus:ring-1 focus:ring-blue-600 transition-colors"
            />
          </div>

          <div className="mb-5">
            <label htmlFor="anon-realm" className="block text-sm font-medium text-gray-700 mb-1.5">
              Realm Name
            </label>
            <input
              id="anon-realm"
              type="text"
              value={settings.realmName}
              onChange={(e) => setSettings({ ...settings, realmName: e.target.value })}
              className="w-full max-w-sm px-3 py-2 border border-gray-300 rounded-md text-sm text-gray-700 bg-white focus:outline-none focus:border-blue-600 focus:ring-1 focus:ring-blue-600 transition-colors"
            />
          </div>

          <div className="flex justify-end pt-5 mt-5 border-t border-gray-200">
            <button
              className="inline-flex items-center gap-1.5 px-4 py-2 bg-blue-600 hover:bg-blue-700 text-white text-sm font-medium rounded-md transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
              onClick={handleSave}
              disabled={saving}
            >
              {saving ? 'Saving...' : 'Save'}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
