import { useState } from 'react';

export interface ConfirmPrompt {
  title: string;
  body: string;
  /** Exact text the operator has to reproduce; also what the API demands. */
  phrase: string;
  confirmLabel: string;
  onConfirm: (phrase: string) => void;
}

/**
 * A confirmation the operator has to type out.
 *
 * Deliberately heavier than the app's ConfirmDialog, which is right for deleting
 * a thing you are looking at: the consequence is immediate and lands on the
 * person clicking. Arming the firewall — or editing a policy an enforcing
 * repository uses — is the opposite: nothing visibly happens here, and the
 * failure surfaces hours later in somebody else's pipeline. Typing the phrase
 * forces the reader through the sentence describing what will happen, and naming
 * the thing makes arming the wrong row in a list of identical dropdowns
 * impossible rather than merely unlikely.
 *
 * The same phrase is required by the API, so scripts and curl get the same
 * guard; this dialog is the human end of it, not the guard itself. Which is also
 * why the phrase is a prop and not a constant: for the policy writes it is read
 * back out of the server's own rejection (`requiredConfirmationFrom`), so the
 * dialog always asks for what this build actually wants.
 */
export default function ConfirmPhraseDialog({
  prompt,
  busy,
  onCancel,
  onConfirm,
}: {
  prompt: ConfirmPrompt;
  busy: boolean;
  onCancel: () => void;
  onConfirm: (phrase: string) => void;
}) {
  const [typed, setTyped] = useState('');
  const matches = typed.trim() === prompt.phrase;

  return (
    <div
      className="fixed inset-0 bg-black/40 backdrop-blur-[2px] flex items-center justify-center z-[1000] animate-[fadeIn_0.15s_ease]"
      onClick={onCancel}
    >
      <div
        role="dialog"
        aria-modal="true"
        aria-label={prompt.title}
        className="bg-white rounded-lg p-6 w-[520px] max-w-[92vw] shadow-lg animate-[slideUp_0.15s_ease]"
        onClick={(e) => e.stopPropagation()}
      >
        <h3 className="text-base font-semibold mb-2 text-gray-900">{prompt.title}</h3>
        <p className="text-sm text-gray-600 leading-relaxed mb-4">{prompt.body}</p>

        <label htmlFor="firewall-confirm-phrase" className="block text-xs text-gray-500 mb-1.5">
          Type{' '}
          <code className="bg-gray-100 px-1 py-0.5 rounded font-mono text-gray-800">
            {prompt.phrase}
          </code>{' '}
          to continue.
        </label>
        <input
          id="firewall-confirm-phrase"
          type="text"
          autoFocus
          autoComplete="off"
          value={typed}
          onChange={(e) => setTyped(e.target.value)}
          className="w-full px-3 py-2 border border-gray-300 rounded-md text-sm font-mono mb-5"
        />

        <div className="flex gap-2 justify-end">
          <button
            className="inline-flex items-center justify-center px-4 py-2 bg-white border border-gray-200 text-gray-700 text-sm font-medium rounded-md hover:bg-gray-50 transition-colors"
            onClick={onCancel}
          >
            Cancel
          </button>
          <button
            className="inline-flex items-center justify-center px-4 py-2 text-sm font-medium rounded-md bg-red-600 text-white hover:bg-red-700 disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
            disabled={!matches || busy}
            onClick={() => onConfirm(typed.trim())}
          >
            {prompt.confirmLabel}
          </button>
        </div>
      </div>
    </div>
  );
}
