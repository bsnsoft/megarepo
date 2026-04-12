import { useState, type FormEvent } from 'react';
import { useNavigate, useLocation } from 'react-router-dom';
import { useAuth } from './AuthContext';
import { NetworkError } from '../api/client';

export default function LoginPage() {
  const { login } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();

  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  const from = (location.state as { from?: string })?.from || '/';

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    setError('');
    setLoading(true);
    try {
      await login(username, password);
      navigate(from, { replace: true });
    } catch (err: unknown) {
      const msg = err instanceof NetworkError
        ? 'Unable to connect to the server. Please check your network.'
        : err instanceof Error ? err.message
        : 'Login failed';
      setError(msg);
    } finally {
      setLoading(false);
    }
  }

  return (
    <div style={{
      minHeight: '100vh',
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'center',
      background: 'linear-gradient(135deg, #0f172a 0%, #1e293b 50%, #0f172a 100%)',
      position: 'relative',
      overflow: 'hidden',
    }}>
      {/* Subtle grid pattern */}
      <div style={{
        position: 'absolute',
        inset: 0,
        backgroundImage: `
          radial-gradient(ellipse at 30% 20%, rgba(37, 99, 235, 0.08) 0%, transparent 50%),
          radial-gradient(ellipse at 70% 80%, rgba(245, 158, 11, 0.06) 0%, transparent 50%)
        `,
        pointerEvents: 'none',
      }} />

      <div style={{
        position: 'relative',
        width: '100%',
        maxWidth: '400px',
        margin: '0 16px',
      }}>
        {/* Card */}
        <div style={{
          background: '#ffffff',
          borderRadius: '16px',
          padding: '40px 36px 32px',
          boxShadow: '0 25px 50px -12px rgba(0, 0, 0, 0.5), 0 0 0 1px rgba(255,255,255,0.05)',
        }}>
          {/* Logo */}
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', gap: '10px', marginBottom: '4px' }}>
            <img src="/icon.png" alt="" width="44" height="44" style={{ borderRadius: '10px' }} />
            <div>
              <span style={{ fontSize: '28px', fontWeight: 800, color: '#1e293b', letterSpacing: '-0.02em' }}>Mega</span>
              <span style={{ fontSize: '28px', fontWeight: 800, color: '#f59e0b', letterSpacing: '-0.02em' }}>Repo</span>
            </div>
          </div>
          <p style={{ textAlign: 'center', fontSize: '14px', color: '#94a3b8', marginBottom: '32px', fontWeight: 400 }}>
            Artifact Repository Manager
          </p>

          <form onSubmit={handleSubmit}>
            {error && (
              <div style={{
                background: '#fef2f2',
                border: '1px solid #fecaca',
                color: '#b91c1c',
                borderRadius: '8px',
                padding: '10px 14px',
                fontSize: '13px',
                marginBottom: '20px',
                lineHeight: 1.5,
              }}>
                {error}
              </div>
            )}

            <div style={{ marginBottom: '20px' }}>
              <label htmlFor="username" style={{
                display: 'block',
                fontSize: '13px',
                fontWeight: 600,
                color: '#374151',
                marginBottom: '6px',
              }}>
                Username
              </label>
              <input
                id="username"
                type="text"
                value={username}
                onChange={(e) => setUsername(e.target.value)}
                placeholder="Enter username"
                autoFocus
                autoComplete="username"
                required
                style={{
                  width: '100%',
                  padding: '10px 14px',
                  border: '1px solid #d1d5db',
                  borderRadius: '8px',
                  fontSize: '14px',
                  color: '#1e293b',
                  background: '#ffffff',
                  outline: 'none',
                  transition: 'border-color 0.15s, box-shadow 0.15s',
                  boxSizing: 'border-box',
                }}
                onFocus={(e) => {
                  e.target.style.borderColor = '#3b82f6';
                  e.target.style.boxShadow = '0 0 0 3px rgba(59, 130, 246, 0.1)';
                }}
                onBlur={(e) => {
                  e.target.style.borderColor = '#d1d5db';
                  e.target.style.boxShadow = 'none';
                }}
              />
            </div>

            <div style={{ marginBottom: '28px' }}>
              <label htmlFor="password" style={{
                display: 'block',
                fontSize: '13px',
                fontWeight: 600,
                color: '#374151',
                marginBottom: '6px',
              }}>
                Password
              </label>
              <input
                id="password"
                type="password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                placeholder="Enter password"
                autoComplete="current-password"
                required
                style={{
                  width: '100%',
                  padding: '10px 14px',
                  border: '1px solid #d1d5db',
                  borderRadius: '8px',
                  fontSize: '14px',
                  color: '#1e293b',
                  background: '#ffffff',
                  outline: 'none',
                  transition: 'border-color 0.15s, box-shadow 0.15s',
                  boxSizing: 'border-box',
                }}
                onFocus={(e) => {
                  e.target.style.borderColor = '#3b82f6';
                  e.target.style.boxShadow = '0 0 0 3px rgba(59, 130, 246, 0.1)';
                }}
                onBlur={(e) => {
                  e.target.style.borderColor = '#d1d5db';
                  e.target.style.boxShadow = 'none';
                }}
              />
            </div>

            <button
              type="submit"
              disabled={loading}
              style={{
                width: '100%',
                padding: '11px 16px',
                background: loading ? '#93c5fd' : '#2563eb',
                color: '#ffffff',
                border: 'none',
                borderRadius: '8px',
                fontSize: '14px',
                fontWeight: 600,
                cursor: loading ? 'not-allowed' : 'pointer',
                transition: 'background 0.15s, transform 0.1s',
                letterSpacing: '0.01em',
              }}
              onMouseEnter={(e) => { if (!loading) (e.target as HTMLElement).style.background = '#1d4ed8'; }}
              onMouseLeave={(e) => { if (!loading) (e.target as HTMLElement).style.background = '#2563eb'; }}
            >
              {loading ? 'Signing in...' : 'Sign In'}
            </button>
          </form>
        </div>

        {/* Footer */}
        <p style={{
          textAlign: 'center',
          fontSize: '12px',
          color: '#475569',
          marginTop: '24px',
          letterSpacing: '0.02em',
        }}>
          by BSNSoft Solutions GmbH
        </p>
      </div>
    </div>
  );
}
