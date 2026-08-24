import { afterEach, describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import Sidebar from './Sidebar';
import { AuthProvider } from '../auth/AuthContext';
import { api } from '../api/client';
import { mockFetch } from '../test/harness';
import { adminToken, viewerToken } from '../test/token';
import type { LicenseStatus } from '../types/api';

/**
 * What the navigation offers, per role.
 *
 * The sidebar used to render every administration entry to everybody. Once the
 * backend started refusing those endpoints to non-administrators (osTicket
 * #155155) that turned two thirds of the navigation into links whose only
 * possible outcome was a permission error, which reads as a broken product.
 *
 * These tests mount the real `AuthProvider` over a real-shaped access token, so
 * they cover the whole path the roles take: token → claims → context → screen.
 */

const LICENSE: LicenseStatus = {
  licensed: true,
  company: 'ACME Corp',
  email: 'ops@acme.example',
  issuedAt: '2026-01-01T00:00:00Z',
  activeUsers: 12,
  requiresPurchase: false,
  message: 'Licensed',
};

function renderSidebar(token: string, path = '/') {
  api.setToken(token);
  const mock = mockFetch([
    { match: '/system/license', body: LICENSE },
    { match: '/status/check', body: { status: 'UP', version: '1.4.0', edition: 'community' } },
    { match: '/security/auth/refresh', method: 'POST', body: { token } },
  ]);
  render(
    <MemoryRouter initialEntries={[path]}>
      <AuthProvider>
        <Sidebar />
      </AuthProvider>
    </MemoryRouter>,
  );
  return mock;
}

afterEach(() => {
  api.setToken(null);
  localStorage.removeItem('user');
});

describe('Sidebar navigation', () => {
  /**
   * Rendered at an entry of the section under test, because a section only
   * expands its links when one of them is the current page.
   */
  it('shows the security administration to an administrator', () => {
    renderSidebar(adminToken(), '/admin/users');

    expect(screen.getByRole('button', { name: 'Security' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Users' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Roles' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'LDAP' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Repository Firewall' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'NVD Firewall' })).toBeInTheDocument();
  });

  it('hides the security administration from an account without the role', () => {
    renderSidebar(viewerToken(), '/admin/users');

    expect(screen.queryByRole('button', { name: 'Security' })).not.toBeInTheDocument();
    for (const label of [
      'Users',
      'Roles',
      'LDAP',
      'SSL Certificates',
      'Anonymous Access',
      'Repository Firewall',
      'Quarantine',
      'Firewall Policies',
      'Exemptions',
      'NVD Firewall',
    ]) {
      expect(screen.queryByRole('link', { name: label })).not.toBeInTheDocument();
    }
  });

  /**
   * The system section is the mixed one: the status page is open to any
   * logged-in account, the other four are not. Gating the whole section would
   * take away a page that works; leaving it whole would offer four that cannot.
   */
  it('leaves a non-administrator the system pages that are not restricted', () => {
    renderSidebar(viewerToken(), '/admin/status');

    expect(screen.getByRole('link', { name: 'Status' })).toBeInTheDocument();
    for (const label of ['Tasks', 'Audit Log', 'HTTP', 'License']) {
      expect(screen.queryByRole('link', { name: label })).not.toBeInTheDocument();
    }
  });

  it('shows the system administration in full to an administrator', () => {
    renderSidebar(adminToken(), '/admin/status');

    expect(screen.getByRole('link', { name: 'Status' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Tasks' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Audit Log' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'HTTP' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'License' })).toBeInTheDocument();
  });

  /**
   * The repository section is the other mixed one, and the reason the whole
   * list is per-route rather than per-section. `/admin/repositories` stays: the
   * server keeps reading, creating and updating a repository open to any
   * logged-in account, because the documented provisioning recipes need it.
   * The three storage and routing screens beside it do not.
   */
  it('leaves the browse navigation and the repository list alone', () => {
    renderSidebar(viewerToken(), '/admin/repositories');

    expect(screen.getByRole('link', { name: 'Dashboard' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Browse' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Search' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Upload' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Repository' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Repositories' })).toBeInTheDocument();
  });

  it('hides the storage and routing screens from an account without the role', () => {
    renderSidebar(viewerToken(), '/admin/repositories');

    for (const label of ['Blob Stores', 'Cleanup Policies', 'Routing Rules']) {
      expect(screen.queryByRole('link', { name: label })).not.toBeInTheDocument();
    }
  });

  it('shows the repository section in full to an administrator', () => {
    renderSidebar(adminToken(), '/admin/repositories');

    expect(screen.getByRole('link', { name: 'Repositories' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Blob Stores' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Cleanup Policies' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Routing Rules' })).toBeInTheDocument();
  });

  /**
   * `GET /api/v1/system/license` is deliberately open to every logged-in user
   * (SecurityConfig carves it out of the system admin rule), and the banner it
   * feeds carries the edition and the seat warning. Hiding the banner along
   * with the administration entries would be a regression with no upside.
   */
  it('keeps the licence banner for an account without the role', async () => {
    renderSidebar(viewerToken());

    expect(await screen.findByText('Licensed to ACME Corp')).toBeInTheDocument();
  });
});
