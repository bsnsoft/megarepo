import { useState, useEffect } from 'react';
import { useAuth } from '../../auth/AuthContext';
import { api, ApiError, NetworkError } from '../../api/client';
import { useToast } from '../../components/Toast';
import LoadingSpinner from '../../components/LoadingSpinner';
import ErrorState from '../../components/ErrorState';
import Badge from '../../components/Badge';
import type { ApiUser, TokenResponse } from '../../types/api';

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

const inputFocusClass =
  'focus:border-blue-600 focus:ring-1 focus:ring-blue-600';

function statusLabel(status: string): string {
  const map: Record<string, string> = {
    active: 'Active',
    ACTIVE: 'Active',
    disabled: 'Disabled',
    DISABLED: 'Disabled',
    locked: 'Locked',
    LOCKED: 'Locked',
    CHANGE_PASSWORD: 'Password Change Required',
    changepassword: 'Password Change Required',
  };
  return map[status] || status;
}

function statusVariant(status: string): 'success' | 'warning' | 'danger' | 'default' {
  const s = status.toLowerCase();
  if (s === 'active') return 'success';
  if (s === 'change_password' || s === 'changepassword') return 'warning';
  if (s === 'disabled' || s === 'locked') return 'danger';
  return 'default';
}

export default function AccountPage() {
  const { user } = useAuth();
  const { showToast } = useToast();

  const [profile, setProfile] = useState<ApiUser | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);

  // Profile form
  const [firstName, setFirstName] = useState('');
  const [lastName, setLastName] = useState('');
  const [email, setEmail] = useState('');
  const [savingProfile, setSavingProfile] = useState(false);

  // Password form
  const [currentPassword, setCurrentPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [savingPassword, setSavingPassword] = useState(false);

  // NuGet API key (= the MegaRepo access token / bearer token)
  const [apiKey, setApiKey] = useState<string | null>(() => api.getToken());
  const [revealKey, setRevealKey] = useState(false);
  const [regenerating, setRegenerating] = useState(false);
  // Password gate before the key may be shown or copied (Sonatype-style).
  const [keyUnlocked, setKeyUnlocked] = useState(false);
  const [confirmPasswordForKey, setConfirmPasswordForKey] = useState('');
  const [unlockingKey, setUnlockingKey] = useState(false);

  function loadProfile() {
    setLoading(true);
    setLoadError(null);
    api
      .get<ApiUser>('/security/users/me')
      .then((data) => {
        setProfile(data);
        setFirstName(data.firstName || '');
        setLastName(data.lastName || '');
        setEmail(data.emailAddress || '');
      })
      .catch((err) => {
        const msg =
          err instanceof NetworkError
            ? err.message
            : err instanceof ApiError
              ? err.message
              : 'Failed to load profile';
        setLoadError(msg);
      })
      .finally(() => setLoading(false));
  }

  useEffect(() => {
    loadProfile();
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  async function handleSaveProfile() {
    if (!firstName.trim()) {
      showToast('error', 'First name is required');
      return;
    }
    if (!lastName.trim()) {
      showToast('error', 'Last name is required');
      return;
    }
    if (!email.trim()) {
      showToast('error', 'Email is required');
      return;
    }

    setSavingProfile(true);
    try {
      const updated = await api.put<ApiUser>('/security/users/me', {
        firstName: firstName.trim(),
        lastName: lastName.trim(),
        emailAddress: email.trim(),
      });
      setProfile(updated);
      showToast('success', 'Profile updated successfully');
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : 'Failed to update profile';
      showToast('error', message);
    } finally {
      setSavingProfile(false);
    }
  }

  async function handleChangePassword() {
    if (!currentPassword) {
      showToast('error', 'Current password is required');
      return;
    }
    if (!newPassword) {
      showToast('error', 'New password is required');
      return;
    }
    if (newPassword.length < 8) {
      showToast('error', 'New password must be at least 8 characters');
      return;
    }
    if (newPassword !== confirmPassword) {
      showToast('error', 'New passwords do not match');
      return;
    }

    setSavingPassword(true);
    try {
      await api.put('/security/users/me/change-password', {
        currentPassword,
        password: newPassword,
      });
      setCurrentPassword('');
      setNewPassword('');
      setConfirmPassword('');
      showToast('success', 'Password changed successfully');
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : 'Failed to change password';
      showToast('error', message);
    } finally {
      setSavingPassword(false);
    }
  }

  async function handleUnlockKey() {
    if (!confirmPasswordForKey) {
      showToast('error', 'Enter your password to reveal the key');
      return;
    }
    setUnlockingKey(true);
    try {
      // Re-verify the password server-side before exposing the key (Sonatype-style).
      await api.post('/security/users/me/verify-password', { password: confirmPasswordForKey });
      setKeyUnlocked(true);
      setRevealKey(true);
      setConfirmPasswordForKey('');
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : 'Password is incorrect';
      showToast('error', message);
    } finally {
      setUnlockingKey(false);
    }
  }

  function lockKey() {
    setKeyUnlocked(false);
    setRevealKey(false);
    setConfirmPasswordForKey('');
  }

  async function handleCopyKey() {
    if (!apiKey || !keyUnlocked) return;
    try {
      await navigator.clipboard.writeText(apiKey);
      showToast('success', 'API key copied to clipboard');
    } catch {
      showToast('error', 'Could not copy to clipboard');
    }
  }

  async function handleRegenerateKey() {
    const confirmed = window.confirm(
      'Generate a new API key?\n\n' +
        'Your current key keeps working until it expires; update any tooling ' +
        '(NuGet, npm, Maven) that uses the old key.',
    );
    if (!confirmed) return;

    setRegenerating(true);
    try {
      const res = await api.post<TokenResponse>('/security/auth/regenerate-token');
      api.setToken(res.token); // keep the UI session on the fresh token
      setApiKey(res.token);
      // The user just generated this key, so show it immediately (no re-prompt).
      setKeyUnlocked(true);
      setRevealKey(true);
      showToast('success', 'New API key generated');
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : 'Failed to regenerate API key';
      showToast('error', message);
    } finally {
      setRegenerating(false);
    }
  }

  function maskedKey(key: string): string {
    if (key.length <= 12) return '••••••••';
    return `${key.slice(0, 6)}${'•'.repeat(24)}${key.slice(-4)}`;
  }

  if (loading) {
    return (
      <div className="flex justify-center items-center py-20">
        <LoadingSpinner message="Loading profile..." />
      </div>
    );
  }

  if (loadError) {
    return <ErrorState title="Failed to load profile" message={loadError} onRetry={loadProfile} />;
  }

  const profileDirty =
    profile &&
    (firstName !== (profile.firstName || '') ||
      lastName !== (profile.lastName || '') ||
      email !== (profile.emailAddress || ''));

  return (
    <div className="p-6 sm:p-8 max-w-3xl">
      <div className="mb-6">
        <h1 className="text-2xl font-semibold text-gray-950">Account</h1>
        <p className="text-sm text-gray-500 mt-1">Manage your profile and security settings</p>
      </div>

      <div className="space-y-6">
        {/* Profile Information */}
        <div className="bg-white rounded-lg border border-gray-200 overflow-hidden">
          <div className="px-6 py-4 bg-gray-50 border-b border-gray-200">
            <h2 className="text-sm font-semibold text-gray-700">Profile Information</h2>
          </div>
          <div className="p-6 space-y-4">
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1.5">First Name</label>
                <input
                  type="text"
                  value={firstName}
                  onChange={(e) => setFirstName(e.target.value)}
                  placeholder="First name"
                  style={inputStyle}
                  className={inputFocusClass}
                />
              </div>
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1.5">Last Name</label>
                <input
                  type="text"
                  value={lastName}
                  onChange={(e) => setLastName(e.target.value)}
                  placeholder="Last name"
                  style={inputStyle}
                  className={inputFocusClass}
                />
              </div>
            </div>
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1.5">Email</label>
              <input
                type="email"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                placeholder="user@example.com"
                style={inputStyle}
                className={inputFocusClass}
              />
            </div>
            <div className="flex justify-end pt-2">
              <button
                className="inline-flex items-center px-4 py-2 bg-blue-600 hover:bg-blue-700 text-white text-sm font-medium rounded-md transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                onClick={handleSaveProfile}
                disabled={savingProfile || !profileDirty}
              >
                {savingProfile ? 'Saving...' : 'Save Changes'}
              </button>
            </div>
          </div>
        </div>

        {/* Change Password */}
        <div className="bg-white rounded-lg border border-gray-200 overflow-hidden">
          <div className="px-6 py-4 bg-gray-50 border-b border-gray-200">
            <h2 className="text-sm font-semibold text-gray-700">Change Password</h2>
          </div>
          <div className="p-6 space-y-4">
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1.5">Current Password</label>
              <input
                type="password"
                value={currentPassword}
                onChange={(e) => setCurrentPassword(e.target.value)}
                placeholder="Enter current password"
                style={inputStyle}
                className={inputFocusClass}
              />
            </div>
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1.5">New Password</label>
                <input
                  type="password"
                  value={newPassword}
                  onChange={(e) => setNewPassword(e.target.value)}
                  placeholder="Minimum 8 characters"
                  style={inputStyle}
                  className={inputFocusClass}
                />
              </div>
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1.5">Confirm New Password</label>
                <input
                  type="password"
                  value={confirmPassword}
                  onChange={(e) => setConfirmPassword(e.target.value)}
                  placeholder="Repeat new password"
                  style={inputStyle}
                  className={inputFocusClass}
                />
              </div>
            </div>
            {newPassword && confirmPassword && newPassword !== confirmPassword && (
              <p className="text-sm text-red-600">Passwords do not match</p>
            )}
            <div className="flex justify-end pt-2">
              <button
                className="inline-flex items-center px-4 py-2 bg-blue-600 hover:bg-blue-700 text-white text-sm font-medium rounded-md transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                onClick={handleChangePassword}
                disabled={savingPassword || !currentPassword || !newPassword || !confirmPassword}
              >
                {savingPassword ? 'Changing...' : 'Change Password'}
              </button>
            </div>
          </div>
        </div>

        {/* NuGet API Key (also npm / Maven bearer token) */}
        <div className="bg-white rounded-lg border border-gray-200 overflow-hidden">
          <div className="px-6 py-4 bg-gray-50 border-b border-gray-200">
            <h2 className="text-sm font-semibold text-gray-700">NuGet API Key</h2>
          </div>
          <div className="p-6 space-y-4">
            <p className="text-sm text-gray-600">
              Your API key is your personal access token. It is a{' '}
              <span className="font-medium text-gray-800">bearer token</span>: NuGet sends it as{' '}
              <code className="px-1 py-0.5 bg-gray-100 rounded text-[13px]">X-NuGet-ApiKey</code>, and it
              also works as the <code className="px-1 py-0.5 bg-gray-100 rounded text-[13px]">Authorization: Bearer</code>{' '}
              token / password for npm and Maven. Use it with{' '}
              <code className="px-1 py-0.5 bg-gray-100 rounded text-[13px]">dotnet nuget push --api-key</code>.
              Treat it like a password — anyone with this key can act as you.
            </p>

            {!keyUnlocked ? (
              <div className="rounded-md border border-gray-200 bg-gray-50/60 px-4 py-4 space-y-3">
                <label className="block text-sm font-medium text-gray-700">
                  Confirm your password to reveal the key
                </label>
                <div className="flex items-stretch gap-2">
                  <input
                    type="password"
                    value={confirmPasswordForKey}
                    onChange={(e) => setConfirmPasswordForKey(e.target.value)}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter') handleUnlockKey();
                    }}
                    placeholder="Your account password"
                    autoComplete="current-password"
                    style={inputStyle}
                    className={`flex-1 ${inputFocusClass}`}
                    disabled={!apiKey || unlockingKey}
                  />
                  <button
                    type="button"
                    className="inline-flex items-center px-4 py-2 bg-blue-600 hover:bg-blue-700 text-white text-sm font-medium rounded-md transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                    onClick={handleUnlockKey}
                    disabled={!apiKey || unlockingKey || !confirmPasswordForKey}
                  >
                    {unlockingKey ? 'Verifying...' : 'Reveal key'}
                  </button>
                </div>
                {!apiKey && (
                  <p className="text-[13px] text-gray-500">Key unavailable — please log in again.</p>
                )}
              </div>
            ) : (
              <div>
                <div className="flex items-center justify-between mb-1.5">
                  <label className="block text-sm font-medium text-gray-700">Your API key</label>
                  <button
                    type="button"
                    className="text-xs font-medium text-gray-500 hover:text-gray-700 transition-colors"
                    onClick={lockKey}
                  >
                    Lock
                  </button>
                </div>
                <div className="flex items-stretch gap-2">
                  <input
                    type="text"
                    readOnly
                    value={apiKey ? (revealKey ? apiKey : maskedKey(apiKey)) : ''}
                    style={{ ...inputStyle, fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace', fontSize: '13px' }}
                    className="flex-1"
                    onFocus={(e) => e.currentTarget.select()}
                  />
                  <button
                    type="button"
                    className="inline-flex items-center px-3 py-2 bg-white border border-gray-300 hover:bg-gray-50 text-gray-700 text-sm font-medium rounded-md transition-colors disabled:opacity-50"
                    onClick={() => setRevealKey((v) => !v)}
                    disabled={!apiKey}
                  >
                    {revealKey ? 'Hide' : 'Show'}
                  </button>
                  <button
                    type="button"
                    className="inline-flex items-center px-3 py-2 bg-white border border-gray-300 hover:bg-gray-50 text-gray-700 text-sm font-medium rounded-md transition-colors disabled:opacity-50"
                    onClick={handleCopyKey}
                    disabled={!apiKey}
                  >
                    Copy
                  </button>
                </div>
              </div>
            )}

            <div className="rounded-md bg-amber-50 border border-amber-200 px-4 py-3 text-[13px] text-amber-800">
              Resetting issues a fresh key for this account. For security, tokens are
              stateless: your <span className="font-medium">old key keeps working until it
              expires</span>. After resetting, update any NuGet/npm/Maven configuration that
              used the previous key.
            </div>

            <div className="flex justify-end pt-1">
              <button
                type="button"
                className="inline-flex items-center px-4 py-2 bg-white border border-red-300 hover:bg-red-50 text-red-700 text-sm font-medium rounded-md transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                onClick={handleRegenerateKey}
                disabled={regenerating}
              >
                {regenerating ? 'Resetting...' : 'Reset API key'}
              </button>
            </div>
          </div>
        </div>

        {/* Account Info (read-only) */}
        <div className="bg-white rounded-lg border border-gray-200 overflow-hidden">
          <div className="px-6 py-4 bg-gray-50 border-b border-gray-200">
            <h2 className="text-sm font-semibold text-gray-700">Account Information</h2>
          </div>
          <div className="p-6">
            <dl className="divide-y divide-gray-100">
              <div className="flex py-3.5">
                <dt className="w-40 shrink-0 text-sm font-medium text-gray-500">Username</dt>
                <dd className="flex-1 text-sm font-medium text-gray-900">
                  {profile?.userId ?? user?.username ?? 'Unknown'}
                </dd>
              </div>
              <div className="flex py-3.5">
                <dt className="w-40 shrink-0 text-sm font-medium text-gray-500">Status</dt>
                <dd className="flex-1">
                  {profile && (
                    <Badge variant={statusVariant(profile.status)}>
                      {statusLabel(profile.status)}
                    </Badge>
                  )}
                </dd>
              </div>
              <div className="flex py-3.5">
                <dt className="w-40 shrink-0 text-sm font-medium text-gray-500">Source</dt>
                <dd className="flex-1">
                  {profile && <Badge variant="default">{profile.source}</Badge>}
                </dd>
              </div>
              <div className="flex py-3.5">
                <dt className="w-40 shrink-0 text-sm font-medium text-gray-500">Roles</dt>
                <dd className="flex-1">
                  <div className="flex flex-wrap gap-1.5">
                    {profile?.roles.map((role) => (
                      <span
                        key={role}
                        className="inline-flex items-center px-2.5 py-0.5 text-xs font-medium rounded-sm bg-gray-100 text-gray-700"
                      >
                        {role}
                      </span>
                    ))}
                    {(!profile?.roles || profile.roles.length === 0) && (
                      <span className="text-sm text-gray-400">No roles assigned</span>
                    )}
                  </div>
                </dd>
              </div>
            </dl>
          </div>
        </div>
      </div>
    </div>
  );
}
