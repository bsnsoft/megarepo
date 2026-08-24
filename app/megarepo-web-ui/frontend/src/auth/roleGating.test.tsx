import { afterEach, describe, expect, it } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import App from '../App';
import { api } from '../api/client';
import { mockFetch, type MockFetch } from '../test/harness';
import { adminToken, viewerToken } from '../test/token';
import { ADMIN_ONLY_ROUTES } from './adminAreas';
import type { LicenseStatus } from '../types/api';

/**
 * Reaching an administration screen by URL.
 *
 * Filtering the navigation is only half of it — the routes are public URLs, and
 * a bookmark, a link in a ticket or a browser's autocomplete all bypass the
 * sidebar. Before the guard, a non-administrator who did that got the page: it
 * mounted, fired its requests, and filled with "You don't have permission to
 * perform this action." from every one of them.
 *
 * The whole app is mounted here (real router, real AuthProvider, real routes)
 * because the thing under test is the wiring, not a component.
 */

const LICENSE: LicenseStatus = {
  licensed: false,
  company: null,
  email: null,
  issuedAt: null,
  activeUsers: 3,
  requiresPurchase: false,
  message: 'Community Edition',
};

function renderAt(path: string, token: string): MockFetch {
  window.history.pushState({}, '', path);
  api.setToken(token);
  const mock = mockFetch([
    { match: '/system/license', body: LICENSE },
    { match: '/status/check', body: { status: 'UP', version: '1.4.0', edition: 'community' } },
    { match: '/security/auth/refresh', method: 'POST', body: { token } },
    { match: '/security/users', body: [] },
    { match: '/security/roles', body: [] },
    { match: '/tasks', body: [] },
  ]);
  render(<App />);
  return mock;
}

function requestedPaths(mock: MockFetch): string[] {
  return mock.calls.map((call) => call.url);
}

afterEach(() => {
  api.setToken(null);
  localStorage.removeItem('user');
  window.history.pushState({}, '', '/');
});

describe('administration routes reached by URL', () => {
  it('answers an account without the role with a notice instead of the page', async () => {
    const mock = renderAt('/admin/users', viewerToken());

    expect(await screen.findByText('Administrators only')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Back to dashboard' })).toBeInTheDocument();
    // No page, so no requests it is not allowed to make, so no wall of 403s.
    expect(requestedPaths(mock).some((url) => url.includes('/security/users'))).toBe(false);
    expect(screen.queryByRole('heading', { name: 'Users' })).not.toBeInTheDocument();
    expect(screen.queryByText(/don't have permission to perform this action/i)).not.toBeInTheDocument();
  });

  it('keeps the URL, so the address bar still shows what was asked for', async () => {
    renderAt('/admin/roles', viewerToken());

    await screen.findByText('Administrators only');
    expect(window.location.pathname).toBe('/admin/roles');
  });

  it('serves the page to an administrator', async () => {
    const mock = renderAt('/admin/users', adminToken());

    expect(await screen.findByRole('heading', { name: 'Users' })).toBeInTheDocument();
    await waitFor(() =>
      expect(requestedPaths(mock).some((url) => url.includes('/security/users'))).toBe(true),
    );
    expect(screen.queryByText('Administrators only')).not.toBeInTheDocument();
  });

  /**
   * The guard has to cover every route the sidebar hides, or a hidden entry is
   * still one paste away. Driving both off the same list is what keeps them
   * from drifting apart; this asserts the list is actually wired up.
   */
  it.each(ADMIN_ONLY_ROUTES)('guards %s', async (route) => {
    renderAt(route, viewerToken());

    expect(await screen.findByText('Administrators only')).toBeInTheDocument();
  });

  it('guards a page below a guarded route', async () => {
    renderAt('/admin/firewall/policies/p-1', viewerToken());

    expect(await screen.findByText('Administrators only')).toBeInTheDocument();
  });

  /**
   * The counter-case: the task list is `authenticated()` on the server, so a
   * non-administrator may use it and the guard must not be in the way.
   */
  it('does not stand in front of screens the server leaves open', async () => {
    const mock = renderAt('/admin/tasks', viewerToken());

    await waitFor(() => expect(requestedPaths(mock).some((url) => url.includes('/tasks'))).toBe(true));
    expect(screen.queryByText('Administrators only')).not.toBeInTheDocument();
  });
});
