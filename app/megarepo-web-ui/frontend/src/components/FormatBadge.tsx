import Badge from './Badge';

const formatColors: Record<string, 'primary' | 'success' | 'warning' | 'info' | 'danger'> = {
  maven2: 'primary',
  pypi: 'success',
  npm: 'danger',
  nuget: 'info',
  raw: 'info',
  docker: 'primary',
};

interface FormatBadgeProps {
  format: string;
}

export default function FormatBadge({ format }: FormatBadgeProps) {
  const variant = formatColors[format] || 'default';
  const label =
    format === 'maven2' ? 'Maven' : format === 'nuget' ? 'NuGet' : format.charAt(0).toUpperCase() + format.slice(1);
  return <Badge variant={variant}>{label}</Badge>;
}
