/**
 * `megarepo.firewall.facts.enabled=false`, made visible.
 *
 * Component facts are what MIN_AGE and LICENSE decide on. Switched off, those
 * rules do not become permissive and they do not become strict — they become
 * *undecidable*, and what happens next is set by the repository's fail mode:
 * FAIL_OPEN serves everything those rules would have judged, FAIL_CLOSED holds
 * it. The second case is the dangerous one, because a permanent quarantine looks
 * exactly like a working firewall until somebody asks why nothing ever gets
 * released.
 *
 * The banner renders only on an explicit `false`. A server that does not report
 * the flag leaves it `undefined`, and inventing a state from a missing field is
 * how a UI ends up warning about a configuration nobody has.
 */
export default function FactsDisabledBanner({
  factsEnabled,
}: {
  factsEnabled: boolean | undefined;
}) {
  if (factsEnabled !== false) {
    return null;
  }

  return (
    <div
      role="status"
      className="rounded-md border border-amber-300 bg-amber-50 px-4 py-3 flex items-start gap-3"
    >
      <svg
        className="w-4 h-4 text-amber-600 shrink-0 mt-0.5"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
        aria-hidden="true"
      >
        <path d="M10.29 3.86L1.82 18a2 2 0 001.71 3h16.94a2 2 0 001.71-3L13.71 3.86a2 2 0 00-3.42 0z" />
        <line x1="12" y1="9" x2="12" y2="13" />
        <line x1="12" y1="17" x2="12.01" y2="17" />
      </svg>
      <p className="text-xs text-amber-900 leading-relaxed">
        <strong>Component facts are switched off</strong> (
        <code className="font-mono">megarepo.firewall.facts.enabled=false</code>). Age and license
        rules have nothing to decide on, so they answer “cannot decide” for every component. On a
        repository set to <strong>fail-closed</strong> that means permanent quarantine — entries go
        in and no re-evaluation ever gets them out. Either switch facts on, or take MIN_AGE and
        LICENSE out of the policies those repositories use.
      </p>
    </div>
  );
}
