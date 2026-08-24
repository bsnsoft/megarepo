/**
 * Builds access tokens shaped like the ones `JwtTokenProvider` issues: three
 * base64url segments, UTF-8 JSON payload, `sub` plus a comma-separated `roles`
 * claim. The signature is a placeholder — the client never checks it, and a
 * test that had to produce a valid one would be testing the wrong thing.
 */

function base64Url(value: string): string {
  const bytes = new TextEncoder().encode(value);
  let binary = '';
  bytes.forEach((byte) => {
    binary += String.fromCharCode(byte);
  });
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

export function fakeToken(claims: Record<string, unknown>): string {
  const header = base64Url(JSON.stringify({ alg: 'HS256', typ: 'JWT' }));
  const payload = base64Url(JSON.stringify({ type: 'access', ...claims }));
  return `${header}.${payload}.not-a-real-signature`;
}

/** Token for an administrator. */
export function adminToken(username = 'admin'): string {
  return fakeToken({ sub: username, roles: 'nx-admin' });
}

/** Token for a logged-in account that holds no administrative role. */
export function viewerToken(username = 'reader'): string {
  return fakeToken({ sub: username, roles: 'nx-viewer' });
}
