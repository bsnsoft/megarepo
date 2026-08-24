import { createContext, useContext, useState, useCallback, useEffect, type ReactNode } from 'react';
import { api } from '../api/client';
import { decodeTokenClaims } from './jwt';
import type { TokenResponse } from '../types/api';

/**
 * The administrator role id, as seeded by `V2__seed_default_data.sql` and as
 * used by `SecurityConfig` (which sees it as the authority `ROLE_nx-admin`).
 */
export const ADMIN_ROLE = 'nx-admin';

export interface AuthUser {
  username: string;
  /** Role ids from the access token, e.g. `['nx-admin']`. */
  roles: string[];
}

interface AuthContextValue {
  user: AuthUser | null;
  isAuthenticated: boolean;
  /**
   * Whether the current session carries `role`.
   *
   * ————————————————————————————————————————————————————————————————————————
   * This answer is a UX signal, never a permission check.
   *
   * It is read out of a JWT this client cannot verify, held in localStorage
   * that its own user can edit. Its only job is to keep people out of screens
   * that would answer them with 403s. Authorization lives on the server, in
   * `SecurityConfig`'s filter chain, and every endpoint the hidden screens
   * would call enforces the role for itself. Nothing here may ever become the
   * reason a server-side check is left out — an attacker simply does not run
   * this code.
   * ————————————————————————————————————————————————————————————————————————
   */
  hasRole: (role: string) => boolean;
  isAdmin: boolean;
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

/**
 * Builds the session user from the access token.
 *
 * Roles are deliberately taken from the token and nowhere else: the token is
 * already persisted (localStorage, by the API client) and is replaced on every
 * sliding-session refresh, so a reload and a refresh both end up with the roles
 * that token actually carries. A second copy in localStorage would be one more
 * thing that can go stale against it.
 *
 * `fallbackUsername` covers the one case where the token is present but
 * unreadable: the session stays logged in with no roles rather than being
 * thrown out, and the server keeps deciding what it may do.
 */
function userFromToken(token: string | null, fallbackUsername: string | null): AuthUser | null {
  if (!token) {
    return null;
  }
  const claims = decodeTokenClaims(token);
  return {
    username: claims?.username ?? fallbackUsername ?? '',
    roles: claims?.roles ?? [],
  };
}

function storedUsername(): string | null {
  const raw = localStorage.getItem('user');
  if (!raw) {
    return null;
  }
  try {
    const parsed = JSON.parse(raw) as { username?: unknown };
    return typeof parsed.username === 'string' ? parsed.username : null;
  } catch {
    return null;
  }
}

function sameUser(a: AuthUser | null, b: AuthUser | null): boolean {
  if (a === null || b === null) {
    return a === b;
  }
  return (
    a.username === b.username &&
    a.roles.length === b.roles.length &&
    a.roles.every((role, index) => role === b.roles[index])
  );
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<AuthUser | null>(() => userFromToken(api.getToken(), storedUsername()));

  const login = useCallback(async (username: string, password: string) => {
    const res = await api.post<TokenResponse>('/security/auth/login', { username, password });
    api.setToken(res.token);
    setUser(userFromToken(res.token, username));
    localStorage.setItem('user', JSON.stringify({ username }));
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

  const isAuthenticated = user !== null;

  useEffect(() => {
    // Sliding session: periodically exchange the current token for a fresh
    // one while the user is logged in. If the refresh fails (token already
    // expired, server unreachable), the next regular API call returns 401 and
    // the client redirects to the login screen.
    if (!isAuthenticated) {
      return;
    }
    const refresh = async () => {
      if (!api.getToken()) {
        return;
      }
      try {
        const res = await api.post<TokenResponse>('/security/auth/refresh');
        api.setToken(res.token);
        // Re-read the claims: the fresh token is the one the rest of the app
        // sends, so the roles on screen have to be the roles in it. Identical
        // contents keep the previous object, which stops this from re-rendering
        // the tree every quarter of an hour.
        setUser((prev) => {
          const next = userFromToken(res.token, prev?.username ?? null);
          return sameUser(prev, next) ? prev : next;
        });
      } catch {
        // Intentionally ignored — see comment above.
      }
    };
    void refresh();
    const intervalId = window.setInterval(() => void refresh(), TOKEN_REFRESH_INTERVAL_MS);
    return () => window.clearInterval(intervalId);
  }, [isAuthenticated]);

  const hasRole = useCallback((role: string) => user?.roles.includes(role) ?? false, [user]);

  return (
    <AuthContext.Provider
      value={{
        user,
        isAuthenticated,
        hasRole,
        isAdmin: hasRole(ADMIN_ROLE),
        login,
        logout,
      }}
    >
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
