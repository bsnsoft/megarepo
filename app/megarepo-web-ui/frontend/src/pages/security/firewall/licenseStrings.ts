/**
 * Pulling the declared license strings back out of a LICENSE finding.
 *
 * Wave A3 made a decision the UI has to honour: the server does **no SPDX
 * mapping**. `LicenseRule` compares the string a component declares, as written,
 * against the allow and deny lists an operator maintains — so `Apache-2.0`,
 * `Apache License 2.0` and `The Apache Software License, Version 2.0` are three
 * different entries and all three may need to be in the list.
 *
 * That makes the exact spelling the actionable part of a finding, and typing it
 * back by hand is exactly where a transcription error becomes a policy that
 * silently matches nothing. The rule quotes the declaration verbatim in its
 * reason for this reason; this parser is the other half — it hands the operator
 * the literal string to click into a list.
 *
 * The reasons produced by `LicenseRule`:
 *
 *   declares <A, B, …>, which the policy denies
 *   declares <A, B, …>, which is not on the policy's list of allowed licenses
 *   declares no license, and the policy requires one
 *   the declared license expression <A, …> could not be read
 *
 * Anything that does not look like one of those yields nothing rather than a
 * guess: offering the operator a mangled string to add to a policy is worse than
 * offering none.
 */

const DECLARES = /^declares\s+(.+?),\s+which\s+(?:the policy denies|is not on the policy)/i;
const UNREADABLE = /^the declared license expression\s+(.+?)\s+could not be read/i;

/**
 * Splits on the separator `String.join(", ", …)` produced, while leaving commas
 * that sit inside a license name alone — `"The Apache Software License, Version
 * 2.0"` is one entry, and cutting it in half would produce two strings that
 * match nothing.
 *
 * The heuristic: a fragment that begins with a word like `Version 2.0` continues
 * the previous entry rather than starting a new one.
 */
function splitDeclarations(list: string): string[] {
  const parts = list.split(/,\s+/);
  const result: string[] = [];
  for (const part of parts) {
    const trimmed = part.trim();
    if (trimmed.length === 0) {
      continue;
    }
    const continuation = /^(v|ver\.?|version)\b/i.test(trimmed) && result.length > 0;
    if (continuation) {
      result[result.length - 1] = `${result[result.length - 1]}, ${trimmed}`;
    } else {
      result.push(trimmed);
    }
  }
  return result;
}

/**
 * The declared license strings a LICENSE finding is about, exactly as the
 * component wrote them. Empty when the reason is not a LICENSE reason, or is
 * the "declares no license" case, which names no string to add anywhere.
 */
export function declaredLicensesFromReason(reason: string | null | undefined): string[] {
  if (!reason) {
    return [];
  }
  const text = reason.trim();
  const matched = DECLARES.exec(text) ?? UNREADABLE.exec(text);
  if (!matched) {
    return [];
  }
  return splitDeclarations(matched[1]);
}

/** True when the component declared nothing and the policy required a license. */
export function isUndeclaredLicenseReason(reason: string | null | undefined): boolean {
  return /^declares no license\b/i.test((reason ?? '').trim());
}

/**
 * The URL that opens the policy editor with a license queued for one of its
 * lists. The editor reads these back and stages the entry unsaved, so the
 * operator still sees what they are about to change before it is written.
 */
export function policyLicenseLink(
  policyId: string | null | undefined,
  license: string,
  list: 'allowed' | 'denied',
): string {
  const target = policyId ?? 'default';
  const params = new URLSearchParams({ license, list });
  return `/admin/firewall/policies/${encodeURIComponent(target)}?${params.toString()}`;
}
