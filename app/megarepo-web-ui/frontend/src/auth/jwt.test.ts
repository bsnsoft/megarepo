import { describe, expect, it } from 'vitest';
import { decodeTokenClaims } from './jwt';
import { fakeToken } from '../test/token';

describe('decodeTokenClaims', () => {
  /**
   * The backend joins the role ids with commas
   * (`JwtTokenProvider.generateAccessToken`), so the split is the whole
   * contract between the two sides.
   */
  it('reads the comma-separated roles claim the backend writes', () => {
    const claims = decodeTokenClaims(fakeToken({ sub: 'admin', roles: 'nx-admin,nx-viewer' }));

    expect(claims?.username).toBe('admin');
    expect(claims?.roles).toEqual(['nx-admin', 'nx-viewer']);
  });

  it('reads a single role', () => {
    expect(decodeTokenClaims(fakeToken({ sub: 'reader', roles: 'nx-viewer' }))?.roles).toEqual([
      'nx-viewer',
    ]);
  });

  it('treats a token without roles as a session with no roles', () => {
    const claims = decodeTokenClaims(fakeToken({ sub: 'reader' }));

    expect(claims?.username).toBe('reader');
    expect(claims?.roles).toEqual([]);
  });

  it('survives a non-ASCII subject', () => {
    expect(decodeTokenClaims(fakeToken({ sub: 'jörg', roles: '' }))?.username).toBe('jörg');
  });

  it.each([
    ['nothing', null],
    ['an empty string', ''],
    ['something that is not a JWT', 'not-a-token'],
    ['a JWT whose payload is not JSON', 'aGVhZGVy.bm90LWpzb24.sig'],
  ])('returns null for %s', (_label, token) => {
    expect(decodeTokenClaims(token)).toBeNull();
  });
});
