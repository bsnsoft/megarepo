/**
 * Where an advisory id can be read in full.
 *
 * A finding names ids and nothing else, and the first thing anybody does with a
 * blocked build is go and read the advisory. Turning the id into a link is the
 * difference between "GHSA-xxxx-…" being evidence and being a string to
 * copy-paste into a search box.
 *
 * Ids this function does not recognise get no link rather than a guessed one —
 * an advisory link that 404s is worse than plain text, because it reads as the
 * advisory not existing.
 */
export function advisoryUrl(advisoryId: string): string | null {
  const id = advisoryId.trim();
  if (/^CVE-\d{4}-\d{4,}$/i.test(id)) {
    return `https://nvd.nist.gov/vuln/detail/${encodeURIComponent(id.toUpperCase())}`;
  }
  if (/^GHSA-[23456789cfghjmpqrvwx]{4}-[23456789cfghjmpqrvwx]{4}-[23456789cfghjmpqrvwx]{4}$/i.test(id)) {
    return `https://github.com/advisories/${encodeURIComponent(id.toUpperCase())}`;
  }
  // OSV keeps ecosystem-prefixed ids (GO-…, PYSEC-…, RUSTSEC-…, MAL-…) and
  // resolves every one of them, including the two above.
  if (/^[A-Z][A-Z0-9]*-\d{4}-\d+$/i.test(id) || /^MAL-\d{4}-\d+$/i.test(id)) {
    return `https://osv.dev/vulnerability/${encodeURIComponent(id.toUpperCase())}`;
  }
  return null;
}
