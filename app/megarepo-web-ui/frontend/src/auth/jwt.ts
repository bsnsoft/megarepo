/**
 * Reading the claims out of the access token the backend issued.
 *
 * The token is a signed JWT and the signature is *not* checked here — it cannot
 * be, the key never leaves the server. Everything this module returns is
 * therefore a hint about what the current session probably is, good enough to
 * decide what to put on the screen and worth nothing as a permission check. See
 * the note on `hasRole` in AuthContext.
 */

export interface TokenClaims {
  /** `sub` — the user id the token was issued for, or null if absent. */
  username: string | null;
  /** Role ids as they appear in the `roles` claim, e.g. `nx-admin`. */
  roles: string[];
}

function base64UrlDecode(segment: string): string {
  const base64 = segment.replace(/-/g, '+').replace(/_/g, '/');
  const padded = base64.padEnd(base64.length + ((4 - (base64.length % 4)) % 4), '=');
  const binary = atob(padded);
  const bytes = Uint8Array.from(binary, (char) => char.charCodeAt(0));
  return new TextDecoder().decode(bytes);
}

/**
 * The backend writes the roles as one comma-separated string
 * (`JwtTokenProvider.generateAccessToken`). An array is accepted as well so a
 * future change of that shape degrades to "no roles" nowhere.
 */
function parseRoles(claim: unknown): string[] {
  if (typeof claim === 'string') {
    return claim
      .split(',')
      .map((role) => role.trim())
      .filter((role) => role.length > 0);
  }
  if (Array.isArray(claim)) {
    return claim.filter((role): role is string => typeof role === 'string' && role.length > 0);
  }
  return [];
}

/**
 * Returns the claims of `token`, or null if it is missing or not a readable
 * JWT. A token that cannot be read is not treated as an error: the caller falls
 * back to "authenticated, no roles", which hides the administration screens and
 * leaves every actual decision to the server.
 */
export function decodeTokenClaims(token: string | null | undefined): TokenClaims | null {
  if (!token) {
    return null;
  }
  const parts = token.split('.');
  if (parts.length !== 3) {
    return null;
  }
  try {
    const payload = JSON.parse(base64UrlDecode(parts[1])) as Record<string, unknown>;
    const sub = payload.sub;
    return {
      username: typeof sub === 'string' && sub.length > 0 ? sub : null,
      roles: parseRoles(payload.roles),
    };
  } catch {
    return null;
  }
}
