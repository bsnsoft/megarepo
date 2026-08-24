import { describe, expect, it } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import PolicyListPage from './PolicyListPage';
import { mockFetch, renderPage, type MockFetch } from '../../../test/harness';
import type { FirewallPolicy } from '../../../types/firewall';

const DEFAULT_POLICY: FirewallPolicy = {
  id: 'p-1',
  name: 'Default policy',
  description: 'Seeded with the instance',
  isDefault: true,
  rules: [
    { id: 'r-1', ruleType: 'KNOWN_MALICIOUS', action: 'BLOCK', config: {}, enabled: true, implemented: true },
    { id: 'r-2', ruleType: 'TYPOSQUAT', action: 'WARN', config: {}, enabled: true, implemented: true },
  ],
  assignedRepositories: 4,
  enforcingRepositories: 3,
  createdAt: '2026-07-01T00:00:00Z',
  createdBy: 'system',
};

const STRICT: FirewallPolicy = {
  ...DEFAULT_POLICY,
  id: 'p-2',
  name: 'Strict',
  description: null,
  isDefault: false,
  assignedRepositories: 0,
  enforcingRepositories: 0,
};

function setup(policies: FirewallPolicy[] = [DEFAULT_POLICY, STRICT]): MockFetch {
  return mockFetch([{ match: /\/admin\/firewall\/policies(\?|$)/, body: policies }]);
}

describe('PolicyListPage', () => {
  it('separates how many repositories use a policy from how many block with it', async () => {
    setup();
    renderPage(<PolicyListPage />, { path: '/admin/firewall/policies' });

    const row = (await screen.findByRole('link', { name: 'Default policy' })).closest('tr');
    expect(within(row as HTMLElement).getByText('4')).toBeInTheDocument();
    expect(within(row as HTMLElement).getByText('3')).toBeInTheDocument();
    expect(within(row as HTMLElement).getByText('default')).toBeInTheDocument();
  });

  /**
   * The default is what every unassigned repository uses, so it cannot be
   * deleted out from under them — another policy has to take the flag first.
   */
  it('will not delete the default policy', async () => {
    setup();
    renderPage(<PolicyListPage />, { path: '/admin/firewall/policies' });

    const row = (await screen.findByRole('link', { name: 'Default policy' })).closest('tr');
    expect(within(row as HTMLElement).getByRole('button', { name: 'Delete' })).toBeDisabled();

    const other = screen.getByRole('link', { name: 'Strict' }).closest('tr');
    expect(within(other as HTMLElement).getByRole('button', { name: 'Delete' })).toBeEnabled();
  });

  it('asks for the phrase the server demands before moving the default', async () => {
    const mock = setup();
    mock.handlers.push({
      match: '/admin/firewall/policies/p-2/default',
      method: 'POST',
      responses: [
        {
          status: 400,
          body: {
            status: 400,
            error: 'Bad Request',
            message: 'This changes what enforcing repositories deny. To confirm, send confirmation="MOVE DEFAULT".',
            timestamp: '2026-08-24T00:00:00Z',
          },
        },
        { status: 200, body: { ...STRICT, isDefault: true } },
      ],
    });
    const user = userEvent.setup();
    renderPage(<PolicyListPage />, { path: '/admin/firewall/policies' });

    const row = (await screen.findByRole('link', { name: 'Strict' })).closest('tr');
    await user.click(within(row as HTMLElement).getByRole('button', { name: 'Make default' }));

    const dialog = await screen.findByRole('dialog');
    expect(dialog).toHaveTextContent('MOVE DEFAULT');

    await user.type(within(dialog).getByRole('textbox'), 'MOVE DEFAULT');
    await user.click(within(dialog).getByRole('button', { name: 'Make default' }));

    await waitFor(() => {
      const posts = mock.calls.filter((call) => call.url.includes('/p-2/default'));
      expect(posts).toHaveLength(2);
      expect((posts[1].body as { confirmation?: string }).confirmation).toBe('MOVE DEFAULT');
    });
  });

  it('says what an empty list means rather than showing an empty table', async () => {
    setup([]);
    renderPage(<PolicyListPage />, { path: '/admin/firewall/policies' });
    expect(await screen.findByText(/nothing is being evaluated against anything/i)).toBeInTheDocument();
  });
});
