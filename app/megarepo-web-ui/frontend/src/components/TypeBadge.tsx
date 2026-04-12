import Badge from './Badge';

const typeVariants: Record<string, 'info' | 'success' | 'warning'> = {
  hosted: 'info',
  proxy: 'warning',
  group: 'success',
};

interface TypeBadgeProps {
  type: string;
}

export default function TypeBadge({ type }: TypeBadgeProps) {
  const variant = typeVariants[type] || 'default';
  const label = type.charAt(0).toUpperCase() + type.slice(1);
  return <Badge variant={variant}>{label}</Badge>;
}
