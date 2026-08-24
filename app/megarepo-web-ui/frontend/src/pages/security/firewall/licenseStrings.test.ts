import { describe, expect, it } from 'vitest';
import {
  declaredLicensesFromReason,
  isUndeclaredLicenseReason,
  policyLicenseLink,
} from './licenseStrings';

/**
 * These strings are the ones `LicenseRule` produces. If A3 rewords a reason this
 * suite is what notices — which is the point, because the operator's only way to
 * get the exact declared spelling into a policy is through this parser.
 */
describe('declaredLicensesFromReason', () => {
  it('reads a denied declaration', () => {
    expect(declaredLicensesFromReason('declares GPL-3.0-only, which the policy denies')).toEqual([
      'GPL-3.0-only',
    ]);
  });

  it('reads a not-allowed declaration', () => {
    expect(
      declaredLicensesFromReason(
        "declares SSPL-1.0, which is not on the policy's list of allowed licenses",
      ),
    ).toEqual(['SSPL-1.0']);
  });

  it('reads several declarations', () => {
    expect(
      declaredLicensesFromReason('declares GPL-3.0-only, AGPL-3.0, which the policy denies'),
    ).toEqual(['GPL-3.0-only', 'AGPL-3.0']);
  });

  // The case a comma-splitting parser gets wrong, and the reason the split is
  // not a plain `.split(',')`: half a license name matches nothing at all.
  it('keeps a license name that contains a comma in one piece', () => {
    expect(
      declaredLicensesFromReason(
        'declares The Apache Software License, Version 2.0, which the policy denies',
      ),
    ).toEqual(['The Apache Software License, Version 2.0']);
  });

  it('reads the unparseable-expression reason too', () => {
    expect(
      declaredLicensesFromReason('the declared license expression MIT OR ??? could not be read'),
    ).toEqual(['MIT OR ???']);
  });

  it('offers nothing for a reason it does not recognise', () => {
    expect(declaredLicensesFromReason('declares no license, and the policy requires one')).toEqual(
      [],
    );
    expect(declaredLicensesFromReason('published 2 days ago, minimum age is 7 days')).toEqual([]);
    expect(declaredLicensesFromReason(null)).toEqual([]);
    expect(declaredLicensesFromReason('')).toEqual([]);
  });
});

describe('isUndeclaredLicenseReason', () => {
  it('recognises the no-license case', () => {
    expect(isUndeclaredLicenseReason('declares no license, and the policy requires one')).toBe(true);
  });

  it('does not confuse it with a normal declaration', () => {
    expect(isUndeclaredLicenseReason('declares MIT, which the policy denies')).toBe(false);
  });
});

describe('policyLicenseLink', () => {
  it('points at the policy that decided', () => {
    expect(policyLicenseLink('abc-123', 'GPL-3.0-only', 'denied')).toBe(
      '/admin/firewall/policies/abc-123?license=GPL-3.0-only&list=denied',
    );
  });

  it('falls back to the default policy when the finding names none', () => {
    expect(policyLicenseLink(null, 'MIT', 'allowed')).toBe(
      '/admin/firewall/policies/default?license=MIT&list=allowed',
    );
  });

  it('encodes a spelling with spaces and commas', () => {
    const link = policyLicenseLink('p1', 'The Apache Software License, Version 2.0', 'allowed');
    expect(link).toContain('license=The+Apache+Software+License%2C+Version+2.0');
  });
});
