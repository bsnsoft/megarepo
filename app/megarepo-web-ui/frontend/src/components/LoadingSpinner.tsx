interface LoadingSpinnerProps {
  size?: number;
  /** @deprecated No longer rendered. Kept for backward compatibility. */
  message?: string;
}

export default function LoadingSpinner({ size = 32 }: LoadingSpinnerProps) {
  return (
    <div className="flex items-center justify-center">
      <svg
        width={size}
        height={size}
        viewBox="0 0 24 24"
        fill="none"
        className="animate-spin text-blue-600"
      >
        <circle
          cx="12"
          cy="12"
          r="10"
          stroke="#E5E7EB"
          strokeWidth="3"
        />
        <path
          d="M12 2a10 10 0 0 1 10 10"
          stroke="currentColor"
          strokeWidth="3"
          strokeLinecap="round"
        />
      </svg>
    </div>
  );
}
