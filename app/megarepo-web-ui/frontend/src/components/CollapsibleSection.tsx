import { useState, type ReactNode } from 'react';

interface CollapsibleSectionProps {
  title: string;
  defaultOpen?: boolean;
  children: ReactNode;
}

export default function CollapsibleSection({ title, defaultOpen = true, children }: CollapsibleSectionProps) {
  const [open, setOpen] = useState(defaultOpen);

  return (
    <div className={`collapsible-section ${open ? 'open' : ''}`}>
      <button className="collapsible-section-header" onClick={() => setOpen(!open)}>
        <span className="collapsible-arrow">{open ? '\u25BE' : '\u25B8'}</span>
        <span>{title}</span>
      </button>
      {open && <div className="collapsible-section-body">{children}</div>}
    </div>
  );
}
