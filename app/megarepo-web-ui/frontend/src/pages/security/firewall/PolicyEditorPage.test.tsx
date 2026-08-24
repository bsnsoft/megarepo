import { describe, expect, it } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import PolicyEditorPage from './PolicyEditorPage';
import { mockFetch, renderPage, type MockFetch } from '../../../test/harness';
import type { FirewallPolicy, FirewallRuleTypeInfo } from '../../../types/firewall';

const RULE_TYPES: FirewallRuleTypeInfo[] = [
  {
    ruleType: 'CVSS_THRESHOLD',
    label: 'CVSS threshold',
    description: 'Refuses components with an advisory at or above a score.',
    implemented: true,
    heuristic: false,
    quarantines: false,
    requiresComponentFacts: false,
    configSchema: [
      {
        key: 'minScore',
        type: 'number',
        label: 'Minimum score',
        description: 'CVSS base score at which the rule matches.',
        defaultValue: 7,
        required: true,
        allowedValues: null,
      },
    ],
  },
  {
    ruleType: 'UNKNOWN_COMPONENT',
    label: 'Unknown component',
    description: 'Matches when no advisory names the component.',
    implemented: true,
    heuristic: false,
    quarantines: true,
    requiresComponentFacts: false,
    configSchema: [
      {
        key: 'allowUnidentifiedFormats',
        type: 'list',
        label: 'Formats without coordinates',
        description: null,
        defaultValue: [],
        required: false,
        allowedValues: null,
      },
    ],
  },
  {
    ruleType: 'TYPOSQUAT',
    label: 'Typosquat',
    description: 'Names resembling packages this instance already holds.',
    implemented: true,
    heuristic: true,
    quarantines: false,
    requiresComponentFacts: false,
    configSchema: [
      {
        key: 'maxDistance',
        type: 'integer',
        label: 'Maximum edit distance',
        description: null,
        defaultValue: 1,
        required: false,
        allowedValues: null,
      },
    ],
  },
  {
    ruleType: 'LICENSE',
    label: 'License',
    description: 'Declared licenses against allow and deny lists.',
    implemented: true,
    heuristic: false,
    quarantines: false,
    requiresComponentFacts: true,
    configSchema: [
      {
        key: 'allowed',
        type: 'list',
        label: 'Allowed licenses',
        description: null,
        defaultValue: [],
        required: false,
        allowedValues: null,
      },
      {
        key: 'denied',
        type: 'list',
        label: 'Denied licenses',
        description: null,
        defaultValue: [],
        required: false,
        allowedValues: null,
      },
    ],
  },
  {
    ruleType: 'ADVISORY_MATCH',
    label: 'Known advisory',
    description: 'Any advisory names the component.',
    implemented: false,
    heuristic: false,
    quarantines: false,
    requiresComponentFacts: false,
    configSchema: [],
  },
];

const POLICY: FirewallPolicy = {
  id: 'p-1',
  name: 'Default policy',
  description: 'Seeded',
  isDefault: true,
  rules: [
    { id: 'r-1', ruleType: 'CVSS_THRESHOLD', action: 'BLOCK', config: { minScore: 9 }, enabled: true, implemented: true },
    { id: 'r-2', ruleType: 'ADVISORY_MATCH', action: 'WARN', config: {}, enabled: true, implemented: false },
  ],
  assignedRepositories: 3,
  enforcingRepositories: 2,
  createdAt: '2026-08-01T00:00:00Z',
  createdBy: 'admin',
};

function setup(policy: FirewallPolicy = POLICY, factsEnabled?: boolean): MockFetch {
  return mockFetch([
    {
      match: '/admin/firewall/status',
      body: {
        enforcement: { enabled: true, updatedAt: null, updatedBy: null, requiredConfirmation: 'X' },
        violationWindowDays: 30,
        summary: { blocking: 2, quarantineNotEnforced: 0, observing: 0, notEvaluated: 0 },
        repositories: [],
        ...(factsEnabled === undefined ? {} : { factsEnabled }),
      },
    },
    { match: '/admin/firewall/rule-types', body: RULE_TYPES },
    { match: /\/admin\/firewall\/policies\/[^/?]+$/, body: policy },
  ]);
}

function renderEditor(path = '/admin/firewall/policies/p-1') {
  return renderPage(<PolicyEditorPage />, { path, route: '/admin/firewall/policies/:id' });
}

describe('PolicyEditorPage', () => {
  it('renders a rule config field from the server schema, not a JSON blob', async () => {
    setup();
    renderEditor();

    const field = (await screen.findByLabelText(/Minimum score/)) as HTMLInputElement;
    expect(field).toHaveAttribute('type', 'number');
    expect(field.value).toBe('9');
    expect(screen.queryByText(/^\{/)).toBeNull();
  });

  /**
   * A rule with no bean in this build enforces nothing. Rendering it as a
   * working switch is worse than not rendering it: the operator counts it as
   * protection they do not have.
   */
  it('locks a rule this version cannot enforce and says so', async () => {
    setup();
    renderEditor();

    await screen.findByLabelText(/Minimum score/);
    expect(screen.getAllByText('not enforced by this version').length).toBeGreaterThan(0);
    expect(screen.getByLabelText('Action for ADVISORY_MATCH')).toBeDisabled();
  });

  it('says how many repositories are blocking with the policy being edited', async () => {
    setup();
    renderEditor();
    expect(await screen.findByText(/2 repositories are blocking with this policy/)).toBeInTheDocument();
  });

  /**
   * Wave A's finding, at the moment of the click: UNKNOWN_COMPONENT matches
   * practically every proxy component, so BLOCK is a close-the-repository
   * switch, not a filter.
   */
  it('warns about UNKNOWN_COMPONENT only once it is actually set to BLOCK', async () => {
    setup();
    const user = userEvent.setup();
    renderEditor();

    await screen.findByLabelText(/Minimum score/);
    await user.selectOptions(screen.getByLabelText('Add rule'), 'UNKNOWN_COMPONENT');

    const action = await screen.findByLabelText('Action for UNKNOWN_COMPONENT');
    // Added on WARN, as recommended — no alarm yet.
    expect((action as HTMLSelectElement).value).toBe('WARN');
    expect(screen.queryByText(/matches practically every component coming through a proxy/)).toBeNull();

    await user.selectOptions(action, 'BLOCK');
    expect(
      await screen.findByText(/matches practically every component coming through a proxy/),
    ).toBeInTheDocument();
  });

  it('labels a heuristic as a heuristic and defaults it to WARN', async () => {
    setup();
    const user = userEvent.setup();
    renderEditor();

    await screen.findByLabelText(/Minimum score/);
    await user.selectOptions(screen.getByLabelText('Add rule'), 'TYPOSQUAT');

    const action = (await screen.findByLabelText('Action for TYPOSQUAT')) as HTMLSelectElement;
    expect(action.value).toBe('WARN');
    expect(screen.getByText('heuristic')).toBeInTheDocument();

    await user.selectOptions(action, 'BLOCK');
    expect(await screen.findByText(/heuristic over the names this instance already holds/i)).toBeInTheDocument();
  });

  it('warns on a facts-reading rule when facts are switched off', async () => {
    setup(POLICY, false);
    const user = userEvent.setup();
    renderEditor();

    await screen.findByLabelText(/Minimum score/);
    await waitFor(() => expect(screen.getByText(/Component facts are switched off/)).toBeInTheDocument());

    await user.selectOptions(screen.getByLabelText('Add rule'), 'LICENSE');
    expect(
      await screen.findByText(/This rule reads component facts, and facts are switched off/),
    ).toBeInTheDocument();
  });

  /**
   * The link from a license finding. It stages the exact declared spelling and
   * saves nothing — a link in a detail dialog must not be able to change a
   * policy on its own.
   */
  it('stages a license handed over from a finding without saving it', async () => {
    const mock = setup();
    renderEditor(
      '/admin/firewall/policies/p-1?license=The+Apache+Software+License%2C+Version+2.0&list=denied',
    );

    expect(await screen.findByText(/was added to the denied list of the LICENSE rule/)).toBeInTheDocument();
    expect(screen.getByText('The Apache Software License, Version 2.0')).toBeInTheDocument();
    expect(screen.getByText('Unsaved changes')).toBeInTheDocument();
    expect(mock.calls.filter((call) => call.method === 'PUT')).toHaveLength(0);
  });

  /**
   * The confirmation phrase is B2's to choose. Rather than hard-coding a guess,
   * the save is attempted, the server's rejection is read for the phrase it
   * demands, and the retry carries it.
   */
  it('asks for the phrase the server demands and retries the save with it', async () => {
    const mock = setup();
    mock.handlers.push({
      match: '/admin/firewall/policies/p-1',
      method: 'PUT',
      responses: [
        {
          status: 400,
          body: {
            status: 400,
            error: 'Bad Request',
            message:
              'This changes what an enforcing repository denies. To confirm, send confirmation="REPLACE POLICY Default policy".',
            timestamp: '2026-08-24T00:00:00Z',
          },
        },
        { status: 200, body: POLICY },
      ],
    });
    const user = userEvent.setup();

    renderEditor();
    await screen.findByLabelText(/Minimum score/);
    await user.clear(screen.getByLabelText('Description'));
    await user.type(screen.getByLabelText('Description'), 'tightened');
    await user.click(screen.getByRole('button', { name: 'Save policy' }));

    const dialog = await screen.findByRole('dialog');
    expect(dialog).toHaveTextContent('REPLACE POLICY Default policy');

    await user.type(within(dialog).getByRole('textbox'), 'REPLACE POLICY Default policy');
    await user.click(within(dialog).getByRole('button', { name: 'Save policy' }));

    await waitFor(() => {
      expect(mock.calls.filter((call) => call.method === 'PUT')).toHaveLength(2);
    });
    const puts = mock.calls.filter((call) => call.method === 'PUT');
    const retry = puts[puts.length - 1];
    expect((retry?.body as { confirmation?: string }).confirmation).toBe(
      'REPLACE POLICY Default policy',
    );
    // The rule set is sent whole — an upsert replaces, it does not patch.
    expect((retry?.body as { rules: unknown[] }).rules).toHaveLength(2);
  });
});
