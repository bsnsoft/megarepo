import type { ReactNode } from 'react';

interface EmptyStateProps {
  icon?: ReactNode;
  title: string;
  description?: string;
  action?: ReactNode;
}

function DefaultIcon() {
  return (
    <svg width="48" height="48" viewBox="0 0 48 48" fill="none" className="text-gray-300">
      <rect x="8" y="6" width="32" height="36" rx="3" stroke="currentColor" strokeWidth="2" />
      <path d="M16 18h16M16 24h12M16 30h8" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
    </svg>
  );
}

export default function EmptyState({ icon, title, description, action }: EmptyStateProps) {
  return (
    <div className="flex flex-col items-center text-center py-16 px-5">
      <div className="mb-4">{icon ?? <DefaultIcon />}</div>
      <h3 className="text-base font-semibold text-gray-900 mb-2">{title}</h3>
      {description && (
        <p className="text-sm text-gray-500 max-w-[400px]">{description}</p>
      )}
      {action && <div className="mt-5">{action}</div>}
    </div>
  );
}
