import { afterEach, describe, expect, it } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import App from '../App';
import { api } from '../api/client';
import { mockFetch, type MockFetch, type RouteHandler } from '../test/harness';
import { adminToken, viewerToken } from '../test/token';
import { ADMIN_ONLY_ROUTES } from './adminAreas';
import type { Component, LicenseStatus, Repository } from '../types/api';

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
 *
 * The other half of these tests is the one that is easy to forget: what a
 * non-administrator must still be able to do. Every rule in `SecurityConfig`
 * was written narrowly so that browsing, searching, uploading and provisioning
 * a repository keep working for a plain account, and a guard that quietly took
 * one of those away would be a worse regression than the 403s it removes.
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

const REPOSITORY: Repository = {
  name: 'maven-releases',
  format: 'maven2',
  type: 'hosted',
  url: '/repository/maven-releases',
  online: true,
  attributes: {},
  componentCount: 4,
  assetCount: 9,
  totalSize: 2048,
};

const COMPONENT: Component = {
  id: 'c-1',
  repository: 'maven-releases',
  format: 'maven2',
  group: 'de.bsnsoft',
  name: 'megarepo-core',
  version: '1.4.0',
  assets: [
    {
      id: 'a-1',
      downloadUrl: '/repository/maven-releases/de/bsnsoft/megarepo-core/1.4.0/megarepo-core-1.4.0.jar',
      path: 'de/bsnsoft/megarepo-core/1.4.0/megarepo-core-1.4.0.jar',
      repository: 'maven-releases',
      format: 'maven2',
      checksumMd5: null,
      checksumSha1: null,
      checksumSha256: null,
      checksumSha512: null,
      contentType: 'application/java-archive',
      lastModified: '2026-08-01T09:00:00Z',
      lastDownloaded: null,
      fileSize: 2048,
    },
  ],
};

/**
 * Answers for every endpoint any route below reaches, so that a page which is
 * *allowed* to load actually loads. Ordered general-to-specific: the harness
 * lets the last matching handler win.
 */
function handlers(token: string): RouteHandler[] {
  return [
    { match: '/system/license', body: LICENSE },
    { match: '/status/check', body: { status: 'UP', version: '1.4.0', edition: 'community' } },
    { match: '/security/auth/refresh', method: 'POST', body: { token } },
    { match: '/security/users', body: [] },
    { match: '/security/roles', body: [] },
    { match: '/metrics', body: {} },
    // Operational surfaces, all nx-admin since osTicket #155155.
    { match: '/tasks', body: [] },
    { match: '/blobstores', body: [{ name: 'default', type: 'File', blobCount: 1, totalSizeInBytes: 1, availableSpaceInBytes: null, config: {} }] },
    { match: '/cleanup-policies', body: [] },
    { match: '/routing-rules', body: [] },
    { match: '/audit', body: { items: [], continuationToken: null } },
    // Repositories: reads and writes stay open to any logged-in account.
    { match: '/repositories', body: [REPOSITORY] },
    { match: '/repositories', method: 'POST', body: REPOSITORY },
    { match: '/repositories/maven-releases', body: REPOSITORY },
    { match: '/components/c-1', body: COMPONENT },
  ];
}

function renderAt(path: string, token: string): MockFetch {
  window.history.pushState({}, '', path);
  api.setToken(token);
  const mock = mockFetch(handlers(token));
  render(<App />);
  return mock;
}

function requestedPaths(mock: MockFetch): string[] {
  return mock.calls.map((call) => call.url);
}

function requested(mock: MockFetch, fragment: string): boolean {
  return requestedPaths(mock).some((url) => url.includes(fragment));
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
    expect(requested(mock, '/security/users')).toBe(false);
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
    await waitFor(() => expect(requested(mock, '/security/users')).toBe(true));
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
});

/**
 * The operational screens closed by the third security round (osTicket
 * #155155). Each of these pages lives on one endpoint prefix that the server
 * now closes whole, reads included — so the whole route is guarded, and the
 * page must not fire a single request for an account that cannot have it.
 */
describe('operational administration screens', () => {
  const OPERATIONAL: { route: string; heading: string; endpoint: string }[] = [
    { route: '/admin/tasks', heading: 'Tasks', endpoint: '/tasks' },
    { route: '/admin/blobstores', heading: 'Blob Stores', endpoint: '/blobstores' },
    { route: '/admin/cleanup', heading: 'Cleanup Policies', endpoint: '/cleanup-policies' },
    { route: '/admin/routing-rules', heading: 'Routing Rules', endpoint: '/routing-rules' },
    { route: '/admin/audit', heading: 'Audit Log', endpoint: '/audit' },
  ];

  it.each(OPERATIONAL)('serves $route to an administrator', async ({ route, heading, endpoint }) => {
    const mock = renderAt(route, adminToken());

    expect(await screen.findByRole('heading', { name: heading })).toBeInTheDocument();
    await waitFor(() => expect(requested(mock, endpoint)).toBe(true));
  });

  it.each(OPERATIONAL)('keeps $route from an account without the role', async ({ route, heading, endpoint }) => {
    const mock = renderAt(route, viewerToken());

    expect(await screen.findByText('Administrators only')).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: heading })).not.toBeInTheDocument();
    expect(requested(mock, endpoint)).toBe(false);
  });
});

/**
 * The counter-cases. Everything below is what the server deliberately left
 * open, and a test that fails here means the gating grew past the chain.
 */
describe('screens the server leaves open', () => {
  it('lets an account without the role see the system status', async () => {
    const mock = renderAt('/admin/status', viewerToken());

    expect(await screen.findByRole('heading', { name: 'System Status' })).toBeInTheDocument();
    await waitFor(() => expect(requested(mock, '/status/check')).toBe(true));
    expect(screen.queryByText('Administrators only')).not.toBeInTheDocument();
  });

  it.each(['/browse', '/search', '/upload'])('lets an account without the role open %s', async (route) => {
    renderAt(route, viewerToken());

    await waitFor(() => expect(screen.queryByText('Administrators only')).not.toBeInTheDocument());
    expect(window.location.pathname).toBe(route);
  });

  it('leaves the repository list to an account without the role', async () => {
    const mock = renderAt('/admin/repositories', viewerToken());

    expect(await screen.findByRole('heading', { name: 'Repositories' })).toBeInTheDocument();
    await waitFor(() => expect(requested(mock, '/repositories')).toBe(true));
    expect(screen.getByText('maven-releases')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Create Repository' })).toBeInTheDocument();
  });

  it('lets an account without the role create a repository', async () => {
    const user = userEvent.setup();
    const mock = renderAt('/admin/repositories/create', viewerToken());

    await user.click(await screen.findByRole('button', { name: /Maven \(Hosted\)/ }));
    await user.type(screen.getByLabelText('Repository Name'), 'team-releases');
    await user.click(screen.getByRole('button', { name: 'Create Repository' }));

    await waitFor(() =>
      expect(
        mock.calls.some((call) => call.method === 'POST' && call.url.includes('/repositories')),
      ).toBe(true),
    );
    expect(screen.queryByText('Administrators only')).not.toBeInTheDocument();
  });

  it('lets an account without the role edit a repository', async () => {
    renderAt('/admin/repositories/maven-releases/edit', viewerToken());

    expect(await screen.findByRole('heading', { name: /Edit Repository/ })).toBeInTheDocument();
    expect(screen.queryByText('Administrators only')).not.toBeInTheDocument();
  });
});

/**
 * Controls whose endpoint is administrator-only on a page that is not. The
 * route stays open — taking browsing away to hide a button would be the wrong
 * trade — and the button is what disappears.
 */
describe('controls gated inside an open page', () => {
  it('offers no repository delete to an account without the role', async () => {
    renderAt('/admin/repositories', viewerToken());

    await screen.findByRole('heading', { name: 'Repositories' });
    expect(screen.queryByRole('button', { name: 'Delete' })).not.toBeInTheDocument();
    // /api/v1/admin/** — the migration runner and the YAML import/export.
    expect(screen.queryByRole('button', { name: /Bootstrap from Nexus/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Import Preset' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Export YAML/ })).not.toBeInTheDocument();
  });

  it('offers all of them to an administrator', async () => {
    renderAt('/admin/repositories', adminToken());

    await screen.findByRole('heading', { name: 'Repositories' });
    expect(await screen.findByRole('button', { name: 'Delete' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Bootstrap from Nexus/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Import Preset' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Export YAML/ })).toBeInTheDocument();
  });

  it('keeps Edit but drops Delete on the repository detail page', async () => {
    renderAt('/admin/repositories/maven-releases', viewerToken());

    expect(await screen.findByRole('heading', { name: 'maven-releases' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Edit' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Delete' })).not.toBeInTheDocument();
  });

  it('shows Delete on the repository detail page to an administrator', async () => {
    renderAt('/admin/repositories/maven-releases', adminToken());

    await screen.findByRole('heading', { name: 'maven-releases' });
    expect(screen.getByRole('button', { name: 'Delete' })).toBeInTheDocument();
  });

  it('drops both delete buttons on a component page, keeping the download', async () => {
    renderAt('/browse/maven-releases/components/c-1', viewerToken());

    expect(await screen.findByRole('heading', { name: /megarepo-core/ })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Delete component/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Delete' })).not.toBeInTheDocument();
    // What the page is actually for stays.
    expect(screen.getByRole('link', { name: /Download/ })).toBeInTheDocument();
  });

  it('shows both delete buttons on a component page to an administrator', async () => {
    renderAt('/browse/maven-releases/components/c-1', adminToken());

    await screen.findByRole('heading', { name: /megarepo-core/ });
    expect(screen.getByRole('button', { name: /Delete component/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Delete' })).toBeInTheDocument();
  });

  /**
   * `GET /api/v1/blobstores` is administrator-only, and the create form used
   * to ask for it anyway and swallow the 403 — leaving a picker with one
   * silent option. Asking is pointless and the silence is worse than the
   * refusal, so a non-administrator gets a name field and the reason for it.
   */
  it('explains the blob store field instead of asking for a list it cannot have', async () => {
    const mock = renderAt('/admin/repositories/create', viewerToken());

    await screen.findByRole('heading', { name: 'Create Repository' });
    await waitFor(() => expect(requested(mock, '/system/license')).toBe(true));
    expect(requested(mock, '/blobstores')).toBe(false);

    await userEvent.setup().click(screen.getByRole('button', { name: /Maven \(Hosted\)/ }));
    expect(screen.getByLabelText('Blob Store')).toHaveValue('default');
    expect(screen.getByText(/needs the administrator role/i)).toBeInTheDocument();
  });

  it('offers an administrator the real blob store list', async () => {
    const mock = renderAt('/admin/repositories/create', adminToken());

    await screen.findByRole('heading', { name: 'Create Repository' });
    await waitFor(() => expect(requested(mock, '/blobstores')).toBe(true));

    await userEvent.setup().click(screen.getByRole('button', { name: /Maven \(Hosted\)/ }));
    expect(screen.getByLabelText('Blob Store')).toHaveValue('default');
    expect(screen.queryByText(/needs the administrator role/i)).not.toBeInTheDocument();
  });
});
