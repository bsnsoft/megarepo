import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import type { FirewallViolation } from '../../../types/api';
import { advisoryUrl } from './advisoryLinks';
import { declaredLicensesFromReason, isUndeclaredLicenseReason, policyLicenseLink } from './licenseStrings';
import { formatTimestamp, humanizeEnum } from './format';
import { ruleLabel } from './ruleCatalog';
import type { FirewallRuleType } from '../../../types/firewall';

/**
 * One finding, in full.
 *
 * The list row says what and when. This says *why the firewall thought so*, and
 * that is the only view from which a disagreement can be settled: which
 * advisories, from which sources, at what confidence, whether the component came
 * through a group, and — for a license verdict — the exact string the component
 * declared.
 *
 * Everything below the header comes out of `requestContext`, which
 * `FirewallViolationRecorder` fills. Every read of it is defensive: the recorder
 * writes a map, not a schema, and a detail dialog that throws on a missing key
 * would take the whole findings table down with it.
 */

interface Finding {
  advisoryId?: string;
  severity?: string;
  cvssScore?: number;
  confidence?: string;
  sources?: string[];
  advisoryIds?: string[];
}

function readString(context: Record<string, unknown>, key: string): string | null {
  const value = context[key];
  return typeof value === 'string' && value.length > 0 ? value : null;
}

function readStrings(context: Record<string, unknown>, key: string): string[] {
  const value = context[key];
  return Array.isArray(value) ? value.filter((entry): entry is string => typeof entry === 'string') : [];
}

function readFindings(context: Record<string, unknown>): Finding[] {
  const value = context['findings'];
  if (!Array.isArray(value)) {
    return [];
  }
  return value.filter((entry): entry is Finding => typeof entry === 'object' && entry !== null);
}

function Row({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="grid grid-cols-[9rem_1fr] gap-3 py-1.5 border-b border-gray-100 last:border-b-0">
      <dt className="text-xs text-gray-500">{label}</dt>
      <dd className="text-sm text-gray-800 break-words">{children}</dd>
    </div>
  );
}

export function AdvisoryLinks({ ids }: { ids: string[] }) {
  if (ids.length === 0) {
    return <span className="text-gray-400">—</span>;
  }
  return (
    <span className="flex flex-wrap gap-x-3 gap-y-1">
      {ids.map((id) => {
        const href = advisoryUrl(id);
        return href ? (
          <a
            key={id}
            href={href}
            target="_blank"
            rel="noopener noreferrer"
            className="font-mono text-xs text-blue-600 hover:underline"
          >
            {id}
          </a>
        ) : (
          <span key={id} className="font-mono text-xs text-gray-700">
            {id}
          </span>
        );
      })}
    </span>
  );
}

export default function ViolationDetailDialog({
  violation,
  onClose,
}: {
  violation: FirewallViolation;
  onClose: () => void;
}) {
  const context = violation.requestContext ?? {};
  const ruleReason = readString(context, 'ruleReason');
  const confidence = readString(context, 'confidence');
  const sources = readStrings(context, 'sources');
  const viaRepository = readString(context, 'viaRepository');
  const policy = readString(context, 'policy');
  const findings = readFindings(context);
  const maxCvss = typeof context['maxCvssScore'] === 'number' ? (context['maxCvssScore'] as number) : null;
  const preExisting = context['preExisting'] === true;
  const blocked = context['blocked'] === true;
  const phase = readString(context, 'phase');

  const policyId = violation.policyId ?? readString(context, 'assignedPolicyId');
  const licenses =
    violation.ruleType === 'LICENSE' ? declaredLicensesFromReason(ruleReason) : [];
  const undeclared = violation.ruleType === 'LICENSE' && isUndeclaredLicenseReason(ruleReason);

  return (
    <div
      className="fixed inset-0 bg-black/40 backdrop-blur-[2px] flex items-center justify-center z-[1000] animate-[fadeIn_0.15s_ease]"
      onClick={onClose}
    >
      <div
        role="dialog"
        aria-modal="true"
        aria-label="Finding detail"
        className="bg-white rounded-lg w-[720px] max-w-[94vw] max-h-[88vh] overflow-y-auto shadow-lg animate-[slideUp_0.15s_ease]"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="p-6 space-y-5">
          <div className="flex items-start justify-between gap-4">
            <div>
              <h3 className="text-base font-semibold text-gray-900">Finding detail</h3>
              <p className="font-mono text-xs text-gray-600 break-all mt-1">{violation.purl}</p>
            </div>
            <button
              onClick={onClose}
              aria-label="Close"
              className="text-gray-400 hover:text-gray-700 text-xl leading-none px-2"
            >
              ×
            </button>
          </div>

          <dl>
            <Row label="When">{formatTimestamp(violation.occurredAt)}</Row>
            <Row label="Repository">
              {violation.repositoryName ?? '—'}
              {/*
                A group serves from a member, and the member is what decides. Without
                this line the row names a repository nobody set a mode on, and the
                operator goes looking for a configuration that is not there.
              */}
              {viaRepository && (
                <span className="block text-xs text-gray-500 mt-0.5">
                  Requested through group <strong>{viaRepository}</strong> — the resolving member
                  above is what evaluated it.
                </span>
              )}
            </Row>
            <Row label="Rule">
              {ruleLabel(violation.ruleType as FirewallRuleType)}{' '}
              <span className="font-mono text-xs text-gray-500">({violation.ruleType})</span>
            </Row>
            <Row label="Action">
              <span
                className={`inline-block px-2 py-0.5 rounded text-xs font-medium ${
                  violation.action === 'BLOCK'
                    ? 'bg-red-50 text-red-700'
                    : 'bg-amber-50 text-amber-700'
                }`}
              >
                {violation.action === 'BLOCK' ? (blocked ? 'blocked' : 'would block') : 'warn'}
              </span>
              {phase === 'audit' && (
                <span className="ml-2 text-xs text-gray-500">recorded in audit — served anyway</span>
              )}
              {preExisting && (
                <span className="ml-2 text-xs text-gray-500">
                  pre-existing component — never refused
                </span>
              )}
            </Row>
            {ruleReason && <Row label="Reason">{ruleReason}</Row>}
            {policy && <Row label="Policy">{policy}</Row>}
            <Row label="Confidence">
              {confidence ? (
                <span className="font-mono text-xs">{confidence}</span>
              ) : (
                <span className="text-gray-400">—</span>
              )}
              {maxCvss != null && (
                <span className="ml-2 text-xs text-gray-600">max CVSS {maxCvss}</span>
              )}
            </Row>
            <Row label="Sources">
              {sources.length > 0 ? (
                <span className="text-xs font-mono text-gray-700">{sources.join(', ')}</span>
              ) : (
                <span className="text-gray-400">—</span>
              )}
            </Row>
            <Row label="Advisories">
              <AdvisoryLinks ids={violation.advisoryIds ?? []} />
            </Row>
          </dl>

          {/* ── Declared licenses, verbatim ─────────────────────────────── */}
          {(licenses.length > 0 || undeclared) && (
            <section className="rounded-md border border-gray-200 bg-gray-50 p-4">
              <h4 className="text-sm font-semibold text-gray-900 mb-1">Declared licenses</h4>
              <p className="text-xs text-gray-600 mb-3">
                Exactly as the component declares them. Licenses are matched as written — there is no
                SPDX normalisation — so put <em>this</em> spelling in the policy list, not the one you
                would have written.
              </p>
              {undeclared ? (
                <p className="text-xs text-gray-700">
                  The component declares no license at all. There is no string to add; loosen{' '}
                  <code className="font-mono">allowUndeclared</code> in the policy if that should pass.
                </p>
              ) : (
                <ul className="space-y-2">
                  {licenses.map((license) => (
                    <li
                      key={license}
                      className="flex items-center justify-between gap-3 bg-white border border-gray-200 rounded px-3 py-2"
                    >
                      <code className="font-mono text-xs text-gray-900 break-all">{license}</code>
                      <span className="flex gap-2 shrink-0">
                        <Link
                          to={policyLicenseLink(policyId, license, 'allowed')}
                          className="text-xs px-2 py-1 rounded border border-emerald-300 text-emerald-700 hover:bg-emerald-50"
                        >
                          Add to allowed
                        </Link>
                        <Link
                          to={policyLicenseLink(policyId, license, 'denied')}
                          className="text-xs px-2 py-1 rounded border border-red-300 text-red-700 hover:bg-red-50"
                        >
                          Add to denied
                        </Link>
                      </span>
                    </li>
                  ))}
                </ul>
              )}
            </section>
          )}

          {/* ── The advisories behind the verdict ───────────────────────── */}
          {findings.length > 0 && (
            <section>
              <h4 className="text-sm font-semibold text-gray-900 mb-2">
                Matching advisories ({findings.length})
              </h4>
              <div className="overflow-x-auto">
                <table className="w-full text-xs">
                  <thead>
                    <tr className="text-left text-gray-500 border-b border-gray-200">
                      <th className="py-1.5 pr-3">Advisory</th>
                      <th className="py-1.5 pr-3">Severity</th>
                      <th className="py-1.5 pr-3">CVSS</th>
                      <th className="py-1.5 pr-3">Confidence</th>
                      <th className="py-1.5">Sources</th>
                    </tr>
                  </thead>
                  <tbody>
                    {findings.map((finding, index) => (
                      <tr key={finding.advisoryId ?? index} className="border-b border-gray-100">
                        <td className="py-1.5 pr-3">
                          <AdvisoryLinks ids={finding.advisoryId ? [finding.advisoryId] : []} />
                        </td>
                        <td className="py-1.5 pr-3 text-gray-700">
                          {humanizeEnum(finding.severity ?? null)}
                        </td>
                        <td className="py-1.5 pr-3 font-mono text-gray-700">
                          {finding.cvssScore ?? '—'}
                        </td>
                        <td className="py-1.5 pr-3 font-mono text-gray-700">
                          {finding.confidence ?? '—'}
                        </td>
                        <td className="py-1.5 font-mono text-gray-600">
                          {(finding.sources ?? []).join(', ') || '—'}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </section>
          )}
        </div>
      </div>
    </div>
  );
}
