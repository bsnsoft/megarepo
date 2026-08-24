import { describe, expect, it } from 'vitest';
import { blockWarning, recommendedAction, ruleCopy, ruleLabel } from './ruleCatalog';

describe('recommendedAction', () => {
  // A4 is explicit: a heuristic set to BLOCK on day one gets the whole firewall
  // switched off. The default the editor offers is where that is decided.
  it('offers WARN for the heuristics', () => {
    expect(recommendedAction('TYPOSQUAT')).toBe('WARN');
    expect(recommendedAction('NAMESPACE_CONFUSION')).toBe('WARN');
  });

  it('offers WARN for UNKNOWN_COMPONENT, which matches almost everything', () => {
    expect(recommendedAction('UNKNOWN_COMPONENT')).toBe('WARN');
  });

  it('offers BLOCK where refusal is the point', () => {
    expect(recommendedAction('KNOWN_MALICIOUS')).toBe('BLOCK');
    expect(recommendedAction('CVSS_THRESHOLD')).toBe('BLOCK');
  });

  it('follows the server when it calls a rule heuristic, even an unknown one', () => {
    expect(recommendedAction('ADVISORY_MATCH', { heuristic: true })).toBe('WARN');
  });
});

describe('blockWarning', () => {
  it('warns about UNKNOWN_COMPONENT matching practically every proxy component', () => {
    const warning = blockWarning('UNKNOWN_COMPONENT');
    expect(warning).toMatch(/practically every component/i);
  });

  it('labels the heuristics as heuristics', () => {
    expect(blockWarning('TYPOSQUAT')).toMatch(/heuristic/i);
    expect(blockWarning('NAMESPACE_CONFUSION')).toMatch(/heuristic/i);
  });

  it('still warns for a heuristic this build has never heard of', () => {
    expect(blockWarning('ADVISORY_MATCH', { heuristic: true, label: 'Some new rule' })).toMatch(
      /heuristic/i,
    );
  });

  it('says nothing about a rule that has nothing special about it', () => {
    expect(blockWarning('KNOWN_MALICIOUS')).toBeNull();
  });
});

describe('labels', () => {
  it('prefers what the server says', () => {
    expect(ruleLabel('MIN_AGE', 'Mindestalter')).toBe('Mindestalter');
  });

  it('falls back to the catalogue, then to the enum name', () => {
    expect(ruleLabel('MIN_AGE')).toBe('Minimum age');
    expect(ruleLabel('ADVISORY_MATCH', '   ')).toBe('Known advisory');
  });

  it('carries the no-SPDX-mapping caution on the LICENSE rule', () => {
    expect(ruleCopy('LICENSE')?.caution).toMatch(/no SPDX/i);
  });
});
