interface ConfirmDialogProps {
  open: boolean;
  title: string;
  message: string;
  confirmLabel?: string;
  cancelLabel?: string;
  variant?: 'danger' | 'primary';
  onConfirm: () => void;
  onCancel: () => void;
}

export default function ConfirmDialog({
  open,
  title,
  message,
  confirmLabel = 'Confirm',
  cancelLabel = 'Cancel',
  variant = 'danger',
  onConfirm,
  onCancel,
}: ConfirmDialogProps) {
  if (!open) return null;

  const confirmBtnClass =
    variant === 'danger'
      ? 'bg-white border border-gray-300 text-red-600 hover:bg-red-50'
      : 'bg-blue-600 hover:bg-blue-700 text-white';

  return (
    <div
      className="fixed inset-0 bg-black/40 backdrop-blur-[2px] flex items-center justify-center z-[1000] animate-[fadeIn_0.15s_ease]"
      onClick={onCancel}
    >
      <div
        className="bg-white rounded-lg p-6 w-[440px] max-w-[90vw] shadow-lg animate-[slideUp_0.15s_ease]"
        onClick={(e) => e.stopPropagation()}
      >
        <h3 className="text-base font-semibold mb-2 text-gray-900">{title}</h3>
        <p className="text-sm text-gray-500 leading-relaxed mb-6">{message}</p>
        <div className="flex gap-2 justify-end">
          <button
            className="inline-flex items-center justify-center px-4 py-2 bg-white border border-gray-200 text-gray-700 text-sm font-medium rounded-md hover:bg-gray-50 transition-colors"
            onClick={onCancel}
          >
            {cancelLabel}
          </button>
          <button
            className={`inline-flex items-center justify-center px-4 py-2 text-sm font-medium rounded-md transition-colors ${confirmBtnClass}`}
            onClick={onConfirm}
          >
            {confirmLabel}
          </button>
        </div>
      </div>
    </div>
  );
}
