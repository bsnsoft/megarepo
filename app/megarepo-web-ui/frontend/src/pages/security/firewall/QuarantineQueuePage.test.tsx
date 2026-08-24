import { describe, expect, it } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes, useSearchParams } from 'react-router-dom';
import QuarantineQueuePage from './QuarantineQueuePage';
import { ToastProvider } from '../../../components/Toast';
import { mockFetch, renderPage, type MockFetch } from '../../../test/harness';
import type { FirewallQuarantineEntry } from '../../../types/firewall';

/** Stands in for the exemptions page and reports what it was handed. */
function QueryProbe() {
  const [params] = useSearchParams();
  return <div data-testid="query">{decodeURIComponent(params.toString())}</div>;
}

const HELD: FirewallQuarantineEntry = {
  id: 'q-1',
  repositoryId: 'r-1',
  repositoryName: 'maven-proxy',
  componentKey: 'pkg:maven/org.example/thing@1.2.3',
  path: 'org/example/thing/1.2.3/thing-1.2.3.jar',
  state: 'QUARANTINED',
  reason: 'MIN_AGE_NOT_MET',
  resolution: null,
  policyId: 'p-1',
  policyName: 'Default',
  advisoryIds: [],
  evaluation: { rule: 'MIN_AGE' },
  firstSeen: new Date(Date.now() - 3 * 86_400_000).toISOString(),
  lastSeen: new Date().toISOString(),
  hitCount: 12,
  lastEvaluatedAt: new Date().toISOString(),
  nextEvaluationAt: new Date(Date.now() + 6 * 3_600_000).toISOString(),
  decidedAt: null,
  decidedBy: null,
  decisionReason: null,
  exemptionId: null,
};

const STUCK: FirewallQuarantineEntry = {
  ...HELD,
  id: 'q-2',
  componentKey: 'pkg:npm/left-pad@1.0.0',
  reason: 'POLICY_VIOLATION',
  nextEvaluationAt: null,
  hitCount: 3,
};

function setup(entries = [HELD, STUCK]): MockFetch {
  return mockFetch([
    {
      match: '/admin/firewall/status',
      body: {
        enforcement: { enabled: true, updatedAt: null, updatedBy: null, requiredConfirmation: 'X' },
        violationWindowDays: 30,
        summary: { blocking: 1, quarantineNotEnforced: 0, observing: 0, notEvaluated: 0 },
        repositories: [
          {
            repositoryId: 'r-1',
            repositoryName: 'maven-proxy',
            format: 'maven2',
            type: 'proxy',
            mode: 'QUARANTINE',
            failMode: 'FAIL_CLOSED',
            effectiveState: 'BLOCKING',
            configured: true,
            violations: 4,
            updatedAt: null,
          },
        ],
      },
    },
    { match: '/admin/firewall/quarantine', body: { items: entries, continuationToken: null } },
  ]);
}

describe('QuarantineQueuePage', () => {
  it('asks for held entries by default, so an operator opens the page on the work', async () => {
    const mock = setup();
    renderPage(<QuarantineQueuePage />, { path: '/admin/firewall/quarantine' });

    await screen.findByText('pkg:maven/org.example/thing@1.2.3');
    const queueCall = mock.calls.find((call) => call.url.includes('/admin/firewall/quarantine'));
    expect(queueCall?.url).toContain('state=QUARANTINED');
  });

  /**
   * The column the screen exists for. An entry with a next check needs nobody;
   * one without needs a decision, and the cell has to say which.
   */
  it('separates "lifts by itself" from "needs a decision"', async () => {
    setup();
    renderPage(<QuarantineQueuePage />, { path: '/admin/firewall/quarantine' });

    const held = (await screen.findByText('pkg:maven/org.example/thing@1.2.3')).closest('tr');
    expect(within(held as HTMLElement).getByText(/^in \d+ h$/)).toBeInTheDocument();

    const stuck = screen.getByText('pkg:npm/left-pad@1.0.0').closest('tr');
    expect(within(stuck as HTMLElement).getByText('needs a decision')).toBeInTheDocument();
  });

  it('refuses to release without a note, then releases with one', async () => {
    const mock = setup();
    mock.handlers.push({
      match: '/quarantine/q-1/release',
      method: 'POST',
      body: { ...HELD, state: 'RELEASED', decisionReason: 'reviewed' },
    });
    const user = userEvent.setup();
    renderPage(<QuarantineQueuePage />, { path: '/admin/firewall/quarantine' });

    const row = (await screen.findByText('pkg:maven/org.example/thing@1.2.3')).closest('tr');
    await user.click(within(row as HTMLElement).getByRole('button', { name: 'Release' }));

    const dialog = await screen.findByRole('dialog');
    const confirm = within(dialog).getByRole('button', { name: 'Release' });
    // The note is @NotBlank server-side; the dialog holds the same line so the
    // audit trail cannot be an empty string.
    expect(confirm).toBeDisabled();

    await user.type(within(dialog).getByRole('textbox'), 'reviewed the advisory');
    expect(confirm).toBeEnabled();
    await user.click(confirm);

    await waitFor(() => {
      expect(mock.calls.some((call) => call.url.includes('/quarantine/q-1/release'))).toBe(true);
    });
    const call = mock.calls.find((entry) => entry.url.includes('/quarantine/q-1/release'));
    expect(call?.body).toEqual({ note: 'reviewed the advisory' });
  });

  /**
   * 409 means the entry moved under the operator — a sweep released it, or
   * somebody else decided first. The server's own sentence says which transition
   * was refused, and it belongs in the dialog they are standing in, not in a
   * toast that disappears.
   */
  it('shows the server reason in place when a transition is refused', async () => {
    const mock = setup();
    mock.handlers.push({
      match: '/quarantine/q-1/block',
      method: 'POST',
      status: 409,
      body: {
        status: 409,
        error: 'Conflict',
        message: 'Cannot block an entry that is already RELEASED.',
        timestamp: '2026-08-24T12:00:00Z',
      },
    });
    const user = userEvent.setup();
    renderPage(<QuarantineQueuePage />, { path: '/admin/firewall/quarantine' });

    const row = (await screen.findByText('pkg:maven/org.example/thing@1.2.3')).closest('tr');
    await user.click(within(row as HTMLElement).getByRole('button', { name: 'Block' }));

    const dialog = await screen.findByRole('dialog');
    await user.type(within(dialog).getByRole('textbox'), 'confirmed malicious');
    await user.click(within(dialog).getByRole('button', { name: 'Block permanently' }));

    expect(await within(dialog).findByRole('alert')).toHaveTextContent(
      /Cannot block an entry that is already RELEASED/,
    );
  });

  /**
   * A component key typed by hand is a key that matches nothing, and an
   * exemption that matches nothing still reads as "approved" in every list. So
   * the queue carries the key across rather than sending the operator to an
   * empty form.
   */
  it('hands the component key to the exemption form instead of asking for it again', async () => {
    setup();
    const user = userEvent.setup();
    render(
      <MemoryRouter initialEntries={['/admin/firewall/quarantine']}>
        <ToastProvider>
          <Routes>
            <Route path="/admin/firewall/quarantine" element={<QuarantineQueuePage />} />
            <Route path="/admin/firewall/exemptions" element={<QueryProbe />} />
          </Routes>
        </ToastProvider>
      </MemoryRouter>,
    );

    const row = (await screen.findByText('pkg:maven/org.example/thing@1.2.3')).closest('tr');
    await user.click(within(row as HTMLElement).getByRole('button', { name: 'Exempt…' }));

    const probe = await screen.findByTestId('query');
    expect(probe).toHaveTextContent('componentKey=pkg:maven/org.example/thing@1.2.3');
    expect(probe).toHaveTextContent('repositoryId=r-1');
    expect(probe).toHaveTextContent('request=1');
  });

  it('says so plainly when nothing is held', async () => {
    mockFetch([
      { match: '/admin/firewall/status', status: 404, body: {} },
      { match: '/admin/firewall/quarantine', body: { items: [], continuationToken: null } },
    ]);
    renderPage(<QuarantineQueuePage />, { path: '/admin/firewall/quarantine' });

    expect(await screen.findByText(/Nothing is being held with these filters/)).toBeInTheDocument();
  });
});
