import { describe, expect, it } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import ExemptionsPage from './ExemptionsPage';
import { mockFetch, renderPage, type MockFetch } from '../../../test/harness';
import type { FirewallExemption } from '../../../types/firewall';

const DAY = 86_400;

const PENDING: FirewallExemption = {
  id: 'e-1',
  componentKey: 'pkg:npm/lodash@4.17.20',
  keyKind: 'PURL',
  scope: 'VERSION',
  repositoryId: 'r-1',
  repositoryName: 'npm-proxy',
  ruleType: 'CVSS_THRESHOLD',
  advisoryIds: ['CVE-2021-23337'],
  state: 'REQUESTED',
  expiresAt: null,
  expired: false,
  expiryNotifiedAt: null,
  justification: 'Needed for the release, patched version lands next sprint.',
  requestedBy: 'dev.one',
  requestedAt: '2026-08-20T10:00:00Z',
  approvedBy: null,
  approvedAt: null,
  decisionNote: null,
};

const LEGACY: FirewallExemption = {
  ...PENDING,
  id: 'e-2',
  componentKey: 'maven2:org.apache.logging.log4j:log4j-core',
  keyKind: 'LEGACY_COORDINATE',
  state: 'APPROVED',
  ruleType: null,
  repositoryId: null,
  repositoryName: null,
  justification: null,
  requestedBy: 'migration',
};

function setup(items: FirewallExemption[] = [PENDING, LEGACY]): MockFetch {
  return mockFetch([
    {
      match: '/admin/firewall/status',
      body: {
        enforcement: { enabled: false, updatedAt: null, updatedBy: null, requiredConfirmation: 'X' },
        violationWindowDays: 30,
        summary: { blocking: 0, quarantineNotEnforced: 0, observing: 1, notEvaluated: 0 },
        repositories: [
          {
            repositoryId: 'r-1',
            repositoryName: 'npm-proxy',
            format: 'npm',
            type: 'proxy',
            mode: 'AUDIT',
            failMode: null,
            effectiveState: 'OBSERVING',
            configured: true,
            violations: 0,
            updatedAt: null,
          },
        ],
      },
    },
    {
      match: '/firewall/exemptions/summary',
      body: {
        requested: 1,
        approved: 4,
        rejected: 0,
        expired: 2,
        revoked: 1,
        legacy: 7,
        // Jackson's default encoding: seconds as a number.
        defaultValidity: 30 * DAY,
        maxValidity: 90 * DAY,
        selfServiceRequests: true,
      },
    },
    // Anchored: `/firewall/exemptions` on its own would also swallow
    // `/summary`, and the approval dialog would silently lose its default
    // validity — which is exactly the bug these tests are here to catch.
    { match: /\/firewall\/exemptions(\?|$)/, body: { items, continuationToken: null } },
  ]);
}

describe('ExemptionsPage', () => {
  /**
   * V18 turned the old NVD whitelist into approved, non-expiring exemptions.
   * Without the marker they read as somebody's considered decision from last
   * week rather than a carried-over row nobody has reviewed.
   */
  it('marks rows migrated from the old whitelist', async () => {
    setup();
    renderPage(<ExemptionsPage />, { path: '/admin/firewall/exemptions' });

    const legacyRow = (await screen.findByText('maven2:org.apache.logging.log4j:log4j-core')).closest(
      'tr',
    );
    expect(within(legacyRow as HTMLElement).getByText('migrated from whitelist')).toBeInTheDocument();

    const currentRow = screen.getByText('pkg:npm/lodash@4.17.20').closest('tr');
    expect(within(currentRow as HTMLElement).queryByText('migrated from whitelist')).toBeNull();
  });

  it('shows the legacy count from the summary endpoint', async () => {
    setup();
    renderPage(<ExemptionsPage />, { path: '/admin/firewall/exemptions' });
    const card = (await screen.findByText('Migrated from whitelist')).parentElement;
    expect(card).toHaveTextContent('7');
  });

  /**
   * The instance's default validity is what makes the easy path the bounded one.
   * If the dialog opened empty, every approval would be permanent by accident.
   */
  it('pre-fills the approval expiry from the instance default validity', async () => {
    setup();
    const user = userEvent.setup();
    renderPage(<ExemptionsPage />, { path: '/admin/firewall/exemptions' });

    const row = (await screen.findByText('pkg:npm/lodash@4.17.20')).closest('tr');
    await user.click(within(row as HTMLElement).getByRole('button', { name: 'Approve' }));

    const dialog = await screen.findByRole('dialog', { name: 'Approve exemption' });
    const expiry = within(dialog).getByLabelText(/Expires/) as HTMLInputElement;
    expect(expiry.value).not.toBe('');

    const chosen = new Date(expiry.value).getTime();
    const expected = Date.now() + 30 * DAY * 1000;
    expect(Math.abs(chosen - expected)).toBeLessThan(5 * 60_000);

    // And the maximum is stated, because the server enforces it with a 400.
    expect(dialog).toHaveTextContent(/at most 90 d from now/);
  });

  it('sends the approval with the expiry and the note', async () => {
    const mock = setup();
    mock.handlers.push({
      match: '/firewall/exemptions/e-1/approve',
      method: 'POST',
      body: { ...PENDING, state: 'APPROVED' },
    });
    const user = userEvent.setup();
    renderPage(<ExemptionsPage />, { path: '/admin/firewall/exemptions' });

    const row = (await screen.findByText('pkg:npm/lodash@4.17.20')).closest('tr');
    await user.click(within(row as HTMLElement).getByRole('button', { name: 'Approve' }));

    const dialog = await screen.findByRole('dialog', { name: 'Approve exemption' });
    await user.type(within(dialog).getByRole('textbox'), 'checked, upgrade scheduled');
    await user.click(within(dialog).getByRole('button', { name: 'Approve' }));

    await waitFor(() => {
      expect(mock.calls.some((call) => call.url.includes('/e-1/approve'))).toBe(true);
    });
    const call = mock.calls.find((entry) => entry.url.includes('/e-1/approve'));
    const body = call?.body as { expiresAt: string | null; note: string };
    expect(body.note).toBe('checked, upgrade scheduled');
    expect(body.expiresAt).toBeTruthy();
  });

  /**
   * Revoke is the only way out, on purpose: an exemption that was once live is
   * the answer to "why did this build pass in March", and a delete button would
   * erase that answer.
   */
  it('offers revoke on an approved exemption and no delete anywhere', async () => {
    setup();
    renderPage(<ExemptionsPage />, { path: '/admin/firewall/exemptions' });

    const legacyRow = (await screen.findByText('maven2:org.apache.logging.log4j:log4j-core')).closest(
      'tr',
    );
    expect(within(legacyRow as HTMLElement).getByRole('button', { name: 'Revoke' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /delete/i })).toBeNull();
  });

  it('opens the request form pre-filled when the queue sent the operator here', async () => {
    setup();
    renderPage(<ExemptionsPage />, {
      path: '/admin/firewall/exemptions?request=1&componentKey=pkg:npm/left-pad@1.0.0&repositoryId=r-1',
    });

    const key = (await screen.findByLabelText(/Component key/)) as HTMLInputElement;
    expect(key.value).toBe('pkg:npm/left-pad@1.0.0');
  });

  it('says when self-service requests are switched off', async () => {
    const mock = setup();
    mock.handlers.push({
      match: '/firewall/exemptions/summary',
      body: {
        requested: 0,
        approved: 0,
        rejected: 0,
        expired: 0,
        revoked: 0,
        legacy: 0,
        defaultValidity: 'P30D',
        maxValidity: 'P90D',
        selfServiceRequests: false,
      },
    });
    renderPage(<ExemptionsPage />, { path: '/admin/firewall/exemptions' });

    expect(await screen.findByText(/Self-service requests are switched off/)).toBeInTheDocument();
  });
});
