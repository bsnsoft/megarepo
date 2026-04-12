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
