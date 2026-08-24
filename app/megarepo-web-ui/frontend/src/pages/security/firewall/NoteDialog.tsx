import { useState } from 'react';

export interface NotePrompt {
  title: string;
  body: string;
  /** Label above the note field. */
  noteLabel: string;
  confirmLabel: string;
  variant: 'danger' | 'primary';
  /** Server-side `@NotBlank` on release/block; optional on exemption decisions. */
  required: boolean;
  placeholder?: string;
  onConfirm: (note: string) => void;
}

/**
 * The dialog behind every firewall decision that ends up in an audit trail.
 *
 * Releasing a held component and blocking one are both overrides of an automatic
 * decision, and the entry keeps `decidedBy` and `decisionReason` forever. The
 * note is mandatory server-side, and it is mandatory here for the same reason it
 * is there: six weeks later the only question anyone asks about a released
 * component is *why*, and "released by admin" does not answer it.
 *
 * `required` is false for exemption decisions, where the API accepts a bare
 * decision — the field stays, because a habit of writing one is worth more than
 * the validation.
 */
export default function NoteDialog({
  prompt,
  busy,
  error,
  onCancel,
}: {
  prompt: NotePrompt;
  busy: boolean;
  /** A rejection from the server, shown in place rather than as a toast. */
  error?: string | null;
  onCancel: () => void;
}) {
  const [note, setNote] = useState('');
  const blank = note.trim().length === 0;

  return (
    <div
      className="fixed inset-0 bg-black/40 backdrop-blur-[2px] flex items-center justify-center z-[1000] animate-[fadeIn_0.15s_ease]"
      onClick={busy ? undefined : onCancel}
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

        <label htmlFor="firewall-decision-note" className="block text-xs text-gray-500 mb-1.5">
          {prompt.noteLabel}
          {prompt.required && <span className="text-red-600"> *</span>}
        </label>
        <textarea
          id="firewall-decision-note"
          autoFocus
          rows={3}
          value={note}
          placeholder={prompt.placeholder}
          onChange={(e) => setNote(e.target.value)}
          className="w-full px-3 py-2 border border-gray-300 rounded-md text-sm mb-1"
        />
        <p className="text-[11px] text-gray-400 mb-4">
          Kept with the entry. It is what an auditor reads instead of asking you.
        </p>

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
            className="inline-flex items-center justify-center px-4 py-2 bg-white border border-gray-200 text-gray-700 text-sm font-medium rounded-md hover:bg-gray-50 transition-colors"
            onClick={onCancel}
            disabled={busy}
          >
            Cancel
          </button>
          <button
            className={`inline-flex items-center justify-center px-4 py-2 text-sm font-medium rounded-md text-white disabled:opacity-40 disabled:cursor-not-allowed transition-colors ${
              prompt.variant === 'danger'
                ? 'bg-red-600 hover:bg-red-700'
                : 'bg-blue-600 hover:bg-blue-700'
            }`}
            disabled={busy || (prompt.required && blank)}
            onClick={() => prompt.onConfirm(note.trim())}
          >
            {prompt.confirmLabel}
          </button>
        </div>
      </div>
    </div>
  );
}
