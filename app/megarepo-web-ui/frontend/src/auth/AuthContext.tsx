import { createContext, useContext, useState, useCallback, useEffect, type ReactNode } from 'react';
import { api } from '../api/client';
import type { TokenResponse } from '../types/api';

interface AuthUser {
  username: string;
}

interface AuthContextValue {
  user: AuthUser | null;
  isAuthenticated: boolean;
  login: (username: string, password: string) => Promise<void>;
  logout: () => void;
}

const AuthContext = createContext<AuthContextValue | null>(null);

/**
 * Sliding session: renew the token well before it expires so an actively used
 * (or simply open) UI never logs the user out mid-work. The backend issues a
 * fresh token as long as the current one is still valid.
 */
const TOKEN_REFRESH_INTERVAL_MS = 15 * 60 * 1000;

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<AuthUser | null>(() => {
    const token = api.getToken();
    if (token) {
      const storedUser = localStorage.getItem('user');
      return storedUser ? JSON.parse(storedUser) : null;
    }
    return null;
  });

  const login = useCallback(async (username: string, password: string) => {
    const res = await api.post<TokenResponse>('/security/auth/login', { username, password });
    api.setToken(res.token);
    const authUser: AuthUser = { username };
    setUser(authUser);
    localStorage.setItem('user', JSON.stringify(authUser));
  }, []);

  const logout = useCallback(() => {
    api.setToken(null);
    setUser(null);
    localStorage.removeItem('user');
  }, []);

  useEffect(() => {
    // Validate token on mount by hitting status endpoint
    if (api.getToken()) {
      api.get('/status/check').catch(() => {
        // Token invalid, clear auth state
        api.setToken(null);
        setUser(null);
        localStorage.removeItem('user');
      });
    }
  }, []);

  useEffect(() => {
    // Sliding session: periodically exchange the current token for a fresh
    // one while the user is logged in. If the refresh fails (token already
    // expired, server unreachable), the next regular API call returns 401 and
    // the client redirects to the login screen.
    if (!user) {
      return;
    }
    const refresh = async () => {
      if (!api.getToken()) {
        return;
      }
      try {
        const res = await api.post<TokenResponse>('/security/auth/refresh');
        api.setToken(res.token);
      } catch {
        // Intentionally ignored — see comment above.
      }
    };
    void refresh();
    const intervalId = window.setInterval(() => void refresh(), TOKEN_REFRESH_INTERVAL_MS);
    return () => window.clearInterval(intervalId);
  }, [user]);

  return (
    <AuthContext.Provider value={{ user, isAuthenticated: user !== null, login, logout }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) {
    throw new Error('useAuth must be used within AuthProvider');
  }
  return ctx;
}
