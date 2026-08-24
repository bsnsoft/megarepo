import { useState } from 'react';
import type { FirewallExemption } from '../../../types/firewall';
import { formatSpan, fromLocalInputValue, isoFromNowSeconds, toLocalInputValue } from './format';

/**
 * Approving an exemption — which is to say, deciding how long the firewall will
 * be told to ignore something.
 *
 * The expiry is the whole point of the dialog. An exemption without one is a
 * permanent hole punched in the policy by somebody who was in a hurry on a
 * Tuesday, and the reason `megarepo.firewall.exemption.default-validity` exists
 * is so that the easy path is the bounded one. So the field is pre-filled from
 * the instance's default validity, the maximum is enforced before the request
 * leaves the browser (the server enforces it too, with a 400), and clearing the
 * field is possible but has to be done deliberately and says what it means.
 */
export default function ExemptionApproveDialog({
  exemption,
  defaultValiditySeconds,
  maxValiditySeconds,
  busy,
  error,
  onCancel,
  onConfirm,
}: {
  exemption: FirewallExemption;
  defaultValiditySeconds: number | null;
  maxValiditySeconds: number | null;
  busy: boolean;
  error?: string | null;
  onCancel: () => void;
  onConfirm: (expiresAt: string | null, note: string) => void;
}) {
  const [expiry, setExpiry] = useState<string>(() => {
    if (exemption.expiresAt) {
      return toLocalInputValue(exemption.expiresAt);
    }
    if (defaultValiditySeconds != null && defaultValiditySeconds > 0) {
      return toLocalInputValue(isoFromNowSeconds(defaultValiditySeconds));
    }
    return '';
  });
  const [note, setNote] = useState('');

  const iso = fromLocalInputValue(expiry);
  const beyondMax =
    iso != null &&
    maxValiditySeconds != null &&
    maxValiditySeconds > 0 &&
    new Date(iso).getTime() - Date.now() > maxValiditySeconds * 1000;

  const maxHint =
    maxValiditySeconds != null && maxValiditySeconds > 0
      ? `at most ${formatSpan(maxValiditySeconds * 1000)} from now`
      : null;

  return (
    <div
      className="fixed inset-0 bg-black/40 backdrop-blur-[2px] flex items-center justify-center z-[1000] animate-[fadeIn_0.15s_ease]"
      onClick={busy ? undefined : onCancel}
    >
      <div
        role="dialog"
        aria-modal="true"
        aria-label="Approve exemption"
        className="bg-white rounded-lg p-6 w-[560px] max-w-[92vw] shadow-lg animate-[slideUp_0.15s_ease]"
        onClick={(e) => e.stopPropagation()}
      >
        <h3 className="text-base font-semibold mb-1 text-gray-900">Approve exemption</h3>
        <p className="font-mono text-xs text-gray-600 break-all mb-3">{exemption.componentKey}</p>
        <p className="text-sm text-gray-600 leading-relaxed mb-4">
          While this is approved, the rules it covers stop refusing this component. The exemption is
          recorded on every download it lets through.
        </p>

        <label htmlFor="exemption-expiry" className="block text-xs text-gray-500 mb-1.5">
          Expires
          {maxHint && <span className="text-gray-400"> — {maxHint}</span>}
        </label>
        <input
          id="exemption-expiry"
          type="datetime-local"
          value={expiry}
          disabled={busy}
          onChange={(e) => setExpiry(e.target.value)}
          className="w-full px-3 py-2 border border-gray-300 rounded-md text-sm mb-1"
        />
        {expiry === '' ? (
          <p className="text-[11px] text-amber-800 mb-4">
            No expiry: this exemption never lapses and has to be revoked by hand.
          </p>
        ) : beyondMax ? (
          <p role="alert" className="text-[11px] text-red-700 mb-4">
            Longer than the instance allows ({maxHint}). The server will refuse it.
          </p>
        ) : (
          <p className="text-[11px] text-gray-400 mb-4">
            Pre-filled from the instance default. Shorten it if you can.
          </p>
        )}

        <label htmlFor="exemption-note" className="block text-xs text-gray-500 mb-1.5">
          Decision note
        </label>
        <textarea
          id="exemption-note"
          rows={3}
          value={note}
          disabled={busy}
          placeholder="What was checked, and what has to happen before it expires"
          onChange={(e) => setNote(e.target.value)}
          className="w-full px-3 py-2 border border-gray-300 rounded-md text-sm mb-4"
        />

        {error && (
          <div
            role="alert"
            className="mb-4 rounded-md border border-red-300 bg-red-50 px-3 py-2 text-xs text-red-800"
          >
            {error}
          </div>
        )}

        <div className="flex gap-2 justify-end">
          <button
            className="inline-flex items-center justify-center px-4 py-2 bg-white border border-gray-200 text-gray-700 text-sm font-medium rounded-md hover:bg-gray-50"
            onClick={onCancel}
            disabled={busy}
          >
            Cancel
          </button>
          <button
            className="inline-flex items-center justify-center px-4 py-2 text-sm font-medium rounded-md bg-blue-600 text-white hover:bg-blue-700 disabled:opacity-40"
            disabled={busy || beyondMax}
            onClick={() => onConfirm(iso, note.trim())}
          >
            Approve
          </button>
        </div>
      </div>
    </div>
  );
}
