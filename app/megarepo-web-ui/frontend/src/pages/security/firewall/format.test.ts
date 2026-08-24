import { describe, expect, it } from 'vitest';
import {
  formatAge,
  formatRelative,
  formatSpan,
  fromLocalInputValue,
  humanizeEnum,
  isoFromNowSeconds,
  toLocalInputValue,
} from './format';
import { advisoryUrl } from './advisoryLinks';

const NOW = new Date('2026-08-24T12:00:00Z');

describe('formatRelative', () => {
  // The queue's most-read column. "in 6 h" is the answer to "does this need me".
  it('says how long until a future moment', () => {
    expect(formatRelative('2026-08-24T18:00:00Z', NOW)).toBe('in 6 h');
    expect(formatRelative('2026-08-27T12:00:00Z', NOW)).toBe('in 3 d');
    expect(formatRelative('2026-08-24T12:20:00Z', NOW)).toBe('in 20 min');
  });

  it('says how long since a past moment', () => {
    expect(formatRelative('2026-08-24T06:00:00Z', NOW)).toBe('6 h ago');
  });

  it('answers a dash for nothing and for junk', () => {
    expect(formatRelative(null, NOW)).toBe('—');
    expect(formatRelative('not a date', NOW)).toBe('—');
  });
});

describe('formatAge / formatSpan', () => {
  it('measures backwards from now', () => {
    expect(formatAge('2026-08-21T12:00:00Z', NOW)).toBe('3 d');
  });

  it('does not pretend to sub-minute precision', () => {
    expect(formatSpan(5_000)).toBe('under a minute');
  });
});

describe('datetime-local round trip', () => {
  // The approval dialog writes into a datetime-local input and sends an Instant.
  // Losing the offset here would offer an expiry hours away from the one shown.
  it('survives a round trip through the local input format', () => {
    const iso = isoFromNowSeconds(3600, NOW);
    const roundTripped = fromLocalInputValue(toLocalInputValue(iso));
    expect(roundTripped).not.toBeNull();
    expect(new Date(roundTripped as string).getTime()).toBe(new Date(iso).getTime());
  });

  it('treats an empty input as no expiry', () => {
    expect(fromLocalInputValue('')).toBeNull();
    expect(toLocalInputValue(null)).toBe('');
  });
});

describe('humanizeEnum', () => {
  it('turns an enum constant into a sentence fragment', () => {
    expect(humanizeEnum('RE_EVALUATED_CLEAN')).toBe('Re evaluated clean');
    expect(humanizeEnum(null)).toBe('—');
  });
});

describe('advisoryUrl', () => {
  it('links CVEs at NVD and GHSAs at GitHub', () => {
    expect(advisoryUrl('CVE-2021-44228')).toBe('https://nvd.nist.gov/vuln/detail/CVE-2021-44228');
    expect(advisoryUrl('GHSA-jfh8-c2jp-5v3q')).toBe(
      'https://github.com/advisories/GHSA-JFH8-C2JP-5V3Q',
    );
  });

  it('sends other ecosystem ids to OSV', () => {
    expect(advisoryUrl('PYSEC-2021-76')).toBe('https://osv.dev/vulnerability/PYSEC-2021-76');
  });

  // A link that 404s reads as "the advisory does not exist", which is worse
  // than plain text.
  it('does not guess a URL for something unrecognisable', () => {
    expect(advisoryUrl('internal-finding-17')).toBeNull();
  });
});
