/**
 * What the editor says about a rule, over and above what `/rule-types` tells it.
 *
 * The server describes a rule structurally: implemented, heuristic, quarantines,
 * needs facts, and its config fields. That is enough to render a form and not
 * nearly enough to stop somebody arming the wrong thing. Three findings from
 * wave A have to reach the operator at the moment of the click, and none of them
 * fit in a boolean:
 *
 * * `UNKNOWN_COMPONENT` matches practically every component a proxy has not seen
 *   an advisory for. Set to BLOCK it does not filter a repository, it closes it.
 * * `TYPOSQUAT` and `NAMESPACE_CONFUSION` are heuristics over a local corpus.
 *   They are useful and they are wrong sometimes; a heuristic armed to BLOCK on
 *   day one is how the whole firewall ends up switched off.
 * * `MIN_AGE` and `LICENSE` read component facts. With
 *   `megarepo.firewall.facts.enabled=false` they can never decide.
 *
 * So the catalogue is copy, not configuration: the server stays the authority on
 * which rules exist and what they take, and this file is the sentence the
 * operator reads before deciding. A rule the server reports that is missing here
 * still renders — it just gets no extra warning.
 */

import type { FirewallAction, FirewallRuleType } from '../../../types/firewall';

export interface RuleCopy {
  /** Human name, used when the server sends no label. */
  label: string;
  /** One line under the name in the editor. */
  summary: string;
  /**
   * Shown next to the action control, always — not only on BLOCK. The operator
   * choosing WARN deserves to know what they are choosing between.
   */
  caution?: string;
  /**
   * Shown as a blocking-severity warning at the moment the action is switched to
   * BLOCK, and only then.
   */
  blockWarning?: string;
  /** Action pre-selected when the rule is added to a policy. */
  recommendedAction: FirewallAction;
}

const CATALOG: Partial<Record<FirewallRuleType, RuleCopy>> = {
  ADVISORY_MATCH: {
    label: 'Known advisory',
    summary: 'The component is named by an advisory this instance has ingested.',
    recommendedAction: 'BLOCK',
  },
  CVSS_THRESHOLD: {
    label: 'CVSS threshold',
    summary: 'An advisory scores at or above the configured CVSS score.',
    recommendedAction: 'BLOCK',
  },
  KNOWN_MALICIOUS: {
    label: 'Known malicious',
    summary: 'The component is on a malware advisory, not merely a vulnerability one.',
    recommendedAction: 'BLOCK',
  },
  LICENSE: {
    label: 'License',
    summary:
      'Compares the licenses the component declares against your allow and deny lists. ' +
      'Declared metadata only, and the spelling is compared as written.',
    caution:
      'Licenses are matched on the strings components actually declare — no SPDX ' +
      'normalisation happens on the server. Add the spelling you saw in a finding, ' +
      'not the one you expected.',
    recommendedAction: 'WARN',
  },
  MIN_AGE: {
    label: 'Minimum age',
    summary:
      'Holds a version that was published less than the configured age ago. ' +
      'The hold lifts by itself once the version is old enough.',
    caution: 'Needs component facts. Without them this rule can never decide.',
    recommendedAction: 'BLOCK',
  },
  UNKNOWN_COMPONENT: {
    label: 'Unknown component',
    summary:
      'Matches when no advisory names the component at all — which is the normal ' +
      'state for practically every component a proxy serves.',
    caution:
      'This matches practically every proxy component. It is a quarantine-everything ' +
      'switch, not a filter.',
    blockWarning:
      'UNKNOWN_COMPONENT matches practically every component coming through a proxy: ' +
      '“no advisory mentions it” is the normal case, not the exception. Set to BLOCK on ' +
      'an enforcing repository, this quarantines almost every new download until somebody ' +
      'releases it by hand. Use it on a narrow repository, or leave it on WARN.',
    recommendedAction: 'WARN',
  },
  TYPOSQUAT: {
    label: 'Typosquat (heuristic)',
    summary:
      'A proxied name that closely resembles a package this instance already trusts. ' +
      'Evidence — the resembled package and the edit distance — is in every finding.',
    caution:
      'Heuristic. It compares names, it does not know intent, and a legitimate package ' +
      'can resemble another one.',
    blockWarning:
      'TYPOSQUAT is a heuristic over the names this instance already holds. It will ' +
      'occasionally be wrong about a legitimate package, and set to BLOCK that is a ' +
      'failed build with an accusation in the log. Run it on WARN, read the findings, ' +
      'then decide.',
    recommendedAction: 'WARN',
  },
  NAMESPACE_CONFUSION: {
    label: 'Namespace confusion (heuristic)',
    summary:
      'An internal namespace arriving from an upstream proxy instead of from your own ' +
      'hosted repository.',
    caution:
      'Heuristic. It depends on the namespaces you declare internal being complete ' +
      'and correct.',
    blockWarning:
      'NAMESPACE_CONFUSION is a heuristic: it is only as good as the internal namespaces ' +
      'configured below. An incomplete list produces false accusations, a wrong one blocks ' +
      'a package you do use. Start on WARN.',
    recommendedAction: 'WARN',
  },
};

export function ruleCopy(ruleType: FirewallRuleType): RuleCopy | undefined {
  return CATALOG[ruleType];
}

export function ruleLabel(ruleType: FirewallRuleType, serverLabel?: string | null): string {
  if (serverLabel && serverLabel.trim().length > 0) {
    return serverLabel;
  }
  return CATALOG[ruleType]?.label ?? ruleType;
}

/**
 * WARN unless the rule is one whose whole point is refusal. Wave A4 is explicit
 * that heuristics default to WARN in any policy this project seeds; the same has
 * to hold when a human adds one in the editor, because the default is what most
 * policies will keep.
 */
export function recommendedAction(
  ruleType: FirewallRuleType,
  info?: { heuristic?: boolean } | null,
): FirewallAction {
  if (info?.heuristic) {
    return 'WARN';
  }
  return CATALOG[ruleType]?.recommendedAction ?? 'WARN';
}

/**
 * The warning to show when this rule is being set to BLOCK, or null when there
 * is nothing special to say. `heuristic` from the server counts even for a rule
 * this file has never heard of — a future heuristic must not arrive unlabelled.
 */
export function blockWarning(
  ruleType: FirewallRuleType,
  info?: { heuristic?: boolean; label?: string | null } | null,
): string | null {
  const copy = CATALOG[ruleType];
  if (copy?.blockWarning) {
    return copy.blockWarning;
  }
  if (info?.heuristic) {
    return `${ruleLabel(ruleType, info.label)} is a heuristic: it infers intent from names and will
      sometimes be wrong. On BLOCK that costs somebody a build. Start on WARN.`;
  }
  return null;
}
