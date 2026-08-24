import { describe, expect, it } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import RepositoryFirewallPage from './RepositoryFirewallPage';
import { mockFetch, renderPage, type MockFetch } from '../../test/harness';

const PROXY = {
  repositoryId: 'r-1',
  repositoryName: 'maven-proxy',
  format: 'maven2',
  type: 'proxy',
  mode: 'AUDIT',
  failMode: 'FAIL_OPEN',
  effectiveState: 'OBSERVING',
  configured: true,
  violations: 2,
  updatedAt: '2026-08-20T09:00:00Z',
  policyId: null,
  policyName: null,
};

const GROUP = {
  ...PROXY,
  repositoryId: 'r-2',
  repositoryName: 'maven-public',
  type: 'group',
  mode: 'OFF',
  effectiveState: 'NOT_EVALUATED',
  violations: 0,
};

const LICENSE_VIOLATION = {
  id: 1,
  repositoryId: 'r-1',
  repositoryName: 'maven-proxy',
  purl: 'pkg:maven/com.example/thing@2.0.0',
  policyId: 'p-1',
  ruleType: 'LICENSE',
  action: 'BLOCK',
  advisoryIds: [],
  occurredAt: '2026-08-23T09:00:00Z',
  requestContext: {
    phase: 'enforcement',
    blocked: true,
    rule: 'LICENSE',
    ruleReason: 'declares The Apache Software License, Version 2.0, which the policy denies',
    confidence: 'EXACT',
    sources: ['pom'],
    viaRepository: 'maven-public',
    findings: [],
  },
};

function setup(overrides: { factsEnabled?: boolean; policies?: unknown } = {}): MockFetch {
  return mockFetch([
    {
      match: '/admin/firewall/status',
      body: {
        enforcement: {
          enabled: false,
          updatedAt: '2026-08-01T00:00:00Z',
          updatedBy: 'admin',
          requiredConfirmation: 'ENABLE ENFORCEMENT',
        },
        violationWindowDays: 30,
        summary: { blocking: 0, quarantineNotEnforced: 0, observing: 1, notEvaluated: 1 },
        repositories: [PROXY, GROUP],
        ...(overrides.factsEnabled === undefined ? {} : { factsEnabled: overrides.factsEnabled }),
      },
    },
    { match: '/admin/firewall/violations', body: { items: [LICENSE_VIOLATION], continuationToken: null } },
    {
      match: /\/admin\/firewall\/policies(\?|$)/,
      body: overrides.policies ?? [
        { id: 'p-1', name: 'Default policy', description: null, isDefault: true, rules: [], assignedRepositories: 1, enforcingRepositories: 0, createdAt: null, createdBy: null },
        { id: 'p-2', name: 'Strict', description: null, isDefault: false, rules: [], assignedRepositories: 0, enforcingRepositories: 0, createdAt: null, createdBy: null },
      ],
    },
  ]);
}

describe('RepositoryFirewallPage', () => {
  /**
   * A group holds nothing of its own — the resolving member decides — and the
   * server answers 400 rather than storing a mode that governs nothing. Leaving
   * the control live would teach the operator that the firewall is broken.
   */
  it('grays out mode and policy on a group row and says why', async () => {
    setup();
    renderPage(<RepositoryFirewallPage />, { path: '/admin/firewall' });

    const mode = await screen.findByLabelText('Mode for maven-public');
    expect(mode).toBeDisabled();
    expect(mode).toHaveAttribute('title', expect.stringContaining('resolving member'));

    await waitFor(() => {
      expect(screen.getByLabelText('Policy for maven-public')).toBeDisabled();
    });

    // The member repository stays fully operable.
    expect(screen.getByLabelText('Mode for maven-proxy')).toBeEnabled();
  });

  it('assigns a policy to a repository and says it replaces the default', async () => {
    const mock = setup();
    mock.handlers.push({ match: '/admin/firewall/repositories/r-1/policy', method: 'PUT', body: PROXY });
    const user = userEvent.setup();
    renderPage(<RepositoryFirewallPage />, { path: '/admin/firewall' });

    const policy = await screen.findByLabelText('Policy for maven-proxy');
    await user.selectOptions(policy, 'p-2');

    await waitFor(() => {
      expect(mock.calls.some((call) => call.url.includes('/repositories/r-1/policy'))).toBe(true);
    });
    const call = mock.calls.find((entry) => entry.url.includes('/repositories/r-1/policy'));
    expect((call?.body as { policyId: string }).policyId).toBe('p-2');
    // Confirmed by the customer and easy to assume backwards, so the column
    // legend has to say it: a repository policy replaces the default.
    expect(screen.getByText(/replaces/)).toBeInTheDocument();
  });

  /**
   * Only an explicit false. A server that does not report the flag has told the
   * UI nothing, and inventing a configuration nobody has is its own bug.
   */
  it('warns when component facts are switched off, and stays quiet when it does not know', async () => {
    setup({ factsEnabled: false });
    renderPage(<RepositoryFirewallPage />, { path: '/admin/firewall' });
    expect(await screen.findByText(/Component facts are switched off/)).toBeInTheDocument();
    expect(screen.getByText(/permanent quarantine/)).toBeInTheDocument();
  });

  it('says nothing about facts when the server does not report the flag', async () => {
    setup();
    renderPage(<RepositoryFirewallPage />, { path: '/admin/firewall' });
    await screen.findByLabelText('Mode for maven-proxy');
    expect(screen.queryByText(/Component facts are switched off/)).toBeNull();
  });

  /**
   * The license spelling is the actionable part of a LICENSE finding, and it is
   * matched as written — so the detail hands over the literal string rather than
   * asking the operator to retype it.
   */
  it('shows the declared license verbatim in the finding detail, with a way into the policy', async () => {
    setup();
    const user = userEvent.setup();
    renderPage(<RepositoryFirewallPage />, { path: '/admin/firewall' });

    await user.click(await screen.findByText('pkg:maven/com.example/thing@2.0.0'));

    const dialog = await screen.findByRole('dialog', { name: 'Finding detail' });
    expect(
      within(dialog).getByText('The Apache Software License, Version 2.0'),
    ).toBeInTheDocument();

    const allow = within(dialog).getByRole('link', { name: 'Add to allowed' });
    expect(allow).toHaveAttribute(
      'href',
      expect.stringContaining('/admin/firewall/policies/p-1?license='),
    );
    expect(allow.getAttribute('href')).toContain('list=allowed');

    // A group request names the group; the member above it is what evaluated.
    expect(dialog).toHaveTextContent(/Requested through group\s*maven-public/);
    expect(dialog).toHaveTextContent('EXACT');
  });
});
