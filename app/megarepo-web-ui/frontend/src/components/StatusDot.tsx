interface StatusDotProps {
  status: 'online' | 'offline' | 'unknown';
  label?: string;
}

const dotColorMap: Record<string, string> = {
  online: 'bg-green-500',
  offline: 'bg-red-500',
  unknown: 'bg-slate-400',
};

export default function StatusDot({ status, label }: StatusDotProps) {
  return (
    <span className="inline-flex items-center gap-1.5">
      <span className={`inline-block w-2 h-2 rounded-full shrink-0 ${dotColorMap[status]}`} />
      {label && <span className="text-sm text-slate-700">{label}</span>}
    </span>
  );
}
