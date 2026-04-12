import type { ReactNode } from 'react';

type BadgeVariant = 'default' | 'primary' | 'success' | 'danger' | 'warning' | 'info';

interface BadgeProps {
  children: ReactNode;
  variant?: BadgeVariant;
}

const variantClasses: Record<BadgeVariant, string> = {
  default: 'bg-gray-100 text-gray-600',
  primary: 'bg-blue-50 text-blue-600',
  success: 'bg-emerald-50 text-emerald-600',
  danger: 'bg-red-50 text-red-600',
  warning: 'bg-amber-50 text-amber-600',
  info: 'bg-blue-50 text-blue-600',
};

export default function Badge({ children, variant = 'default' }: BadgeProps) {
  return (
    <span
      className={`inline-flex items-center px-2 py-0.5 rounded-sm text-xs font-medium whitespace-nowrap ${variantClasses[variant]}`}
    >
      {children}
    </span>
  );
}
