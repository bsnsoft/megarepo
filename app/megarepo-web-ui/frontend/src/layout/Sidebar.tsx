import { useState, useEffect } from 'react';
import { NavLink, useLocation } from 'react-router-dom';
import { api } from '../api/client';
import type { LicenseStatus, StatusCheck } from '../types/api';

interface SidebarSection {
  label: string;
  children: { to: string; icon: string; label: string; end?: boolean }[];
}

const browseLinks = [
  { to: '/', icon: 'grid', label: 'Dashboard' },
  { to: '/browse', icon: 'folder', label: 'Browse' },
  { to: '/search', icon: 'search', label: 'Search' },
  { to: '/upload', icon: 'upload', label: 'Upload' },
];

const adminSections: SidebarSection[] = [
  {
    label: 'Repository',
    children: [
      { to: '/admin/repositories', icon: 'database', label: 'Repositories' },
      { to: '/admin/blobstores', icon: 'hard-drive', label: 'Blob Stores' },
      { to: '/admin/cleanup', icon: 'trash', label: 'Cleanup Policies' },
      { to: '/admin/routing-rules', icon: 'shuffle', label: 'Routing Rules' },
    ],
  },
  {
    label: 'Security',
    children: [
      { to: '/admin/users', icon: 'users', label: 'Users' },
      { to: '/admin/roles', icon: 'shield', label: 'Roles' },
      { to: '/admin/ldap', icon: 'server', label: 'LDAP' },
      { to: '/admin/ssl', icon: 'lock', label: 'SSL Certificates' },
      { to: '/admin/anonymous', icon: 'eye', label: 'Anonymous Access' },
      // `end`, or the overview stays highlighted on every firewall sub-page.
      { to: '/admin/firewall', icon: 'shield-check', label: 'Repository Firewall', end: true },
      { to: '/admin/firewall/quarantine', icon: 'pause-circle', label: 'Quarantine' },
      { to: '/admin/firewall/policies', icon: 'clipboard-list', label: 'Firewall Policies' },
      { to: '/admin/firewall/exemptions', icon: 'unlock', label: 'Exemptions' },
      { to: '/admin/nvd-firewall', icon: 'shield-alert', label: 'NVD Firewall' },
    ],
  },
  {
    label: 'System',
    children: [
      { to: '/admin/status', icon: 'activity', label: 'Status' },
      { to: '/admin/tasks', icon: 'clock', label: 'Tasks' },
      { to: '/admin/http-proxy', icon: 'globe', label: 'HTTP' },
      { to: '/admin/audit', icon: 'file-text', label: 'Audit Log' },
      { to: '/admin/license', icon: 'key', label: 'License' },
    ],
  },
];

function SidebarIcon({ name }: { name: string }) {
  const paths: Record<string, string> = {
    'grid': 'M3 3h7v7H3zM14 3h7v7h-7zM3 14h7v7H3zM14 14h7v7h-7z',
    'folder': 'M3 7v10a2 2 0 002 2h14a2 2 0 002-2V9a2 2 0 00-2-2h-6l-2-2H5a2 2 0 00-2 2z',
    'search': 'M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z',
    'upload': 'M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-8l-4-4m0 0L8 8m4-4v12',
    'database': 'M4 7v10c0 2.21 3.582 4 8 4s8-1.79 8-4V7M4 7c0 2.21 3.582 4 8 4s8-1.79 8-4M4 7c0-2.21 3.582-4 8-4s8 1.79 8 4m0 5c0 2.21-3.582 4-8 4s-8-1.79-8-4',
    'hard-drive': 'M2 17h20M2 17a2 2 0 01-2-2V5a2 2 0 012-2h20a2 2 0 012 2v10a2 2 0 01-2 2M2 17v2a2 2 0 002 2h16a2 2 0 002-2v-2M6 19h.01M10 19h.01',
    'trash': 'M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16',
    'users': 'M17 20h5v-2a3 3 0 00-5.356-1.857M17 20H7m10 0v-2c0-.656-.126-1.283-.356-1.857M7 20H2v-2a3 3 0 015.356-1.857M7 20v-2c0-.656.126-1.283.356-1.857m0 0a5.002 5.002 0 019.288 0M15 7a3 3 0 11-6 0 3 3 0 016 0zm6 3a2 2 0 11-4 0 2 2 0 014 0zM7 10a2 2 0 11-4 0 2 2 0 014 0z',
    'shield': 'M9 12l2 2 4-4m5.618-4.016A11.955 11.955 0 0112 2.944a11.955 11.955 0 01-8.618 3.04A12.02 12.02 0 003 9c0 5.591 3.824 10.29 9 11.622 5.176-1.332 9-6.03 9-11.622 0-1.042-.133-2.052-.382-3.016z',
    'server': 'M5 12H3m18 0h-2M5 12a2 2 0 01-2-2V6a2 2 0 012-2h14a2 2 0 012 2v4a2 2 0 01-2 2M5 12a2 2 0 00-2 2v4a2 2 0 002 2h14a2 2 0 002-2v-4a2 2 0 00-2-2m-7-4h.01M17 16h.01',
    'lock': 'M12 15v2m-6 4h12a2 2 0 002-2v-6a2 2 0 00-2-2H6a2 2 0 00-2 2v6a2 2 0 002 2zm10-10V7a4 4 0 00-8 0v4h8z',
    'eye': 'M15 12a3 3 0 11-6 0 3 3 0 016 0z M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z',
    'activity': 'M22 12h-4l-3 9L9 3l-3 9H2',
    'clock': 'M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z',
    'file-text': 'M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z',
    'shuffle': 'M8 7h12m0 0l-4-4m4 4l-4 4m0 6H4m0 0l4 4m-4-4l4-4',
    'key': 'M15 7a2 2 0 012 2m4 0a6 6 0 01-7.743 5.743L11 17H9v2H7v2H4a1 1 0 01-1-1v-2.586a1 1 0 01.293-.707l5.964-5.964A6 6 0 1121 9z',
    'globe': 'M21 12a9 9 0 11-18 0 9 9 0 0118 0zM3.6 9h16.8M3.6 15h16.8M12 3a15 15 0 010 18M12 3a15 15 0 000 18',
    'shield-alert': 'M12 2l8.618 3.04A12.02 12.02 0 0121 9c0 5.591-3.824 10.29-9 11.622C6.824 19.29 3 14.591 3 9c0-1.042.133-2.052.382-3.016L12 2zM12 8v4m0 4h.01',
    'shield-check': 'M12 2l8.618 3.04A12.02 12.02 0 0121 9c0 5.591-3.824 10.29-9 11.622C6.824 19.29 3 14.591 3 9c0-1.042.133-2.052.382-3.016L12 2zM9 12l2 2 4-4',
    'pause-circle': 'M10 9v6m4-6v6m7-3a9 9 0 11-18 0 9 9 0 0118 0z',
    'clipboard-list': 'M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2m-6 9h6m-6 4h6',
    'unlock': 'M12 15v2m-6 4h12a2 2 0 002-2v-6a2 2 0 00-2-2H6a2 2 0 00-2 2v6a2 2 0 002 2zm10-10V7a4 4 0 00-8 0',
    'user': 'M16 7a4 4 0 11-8 0 4 4 0 018 0zM12 14a7 7 0 00-7 7h14a7 7 0 00-7-7z',
    'sign-out': 'M17 16l4-4m0 0l-4-4m4 4H7m6 4v1a3 3 0 01-3 3H6a3 3 0 01-3-3V7a3 3 0 013-3h4a3 3 0 013 3v1',
  };
  return (
    <svg className="w-[18px] h-[18px] shrink-0" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
      <path d={paths[name] || 'M12 12h.01'} />
    </svg>
  );
}

function CollapsibleSection({ section }: { section: SidebarSection }) {
  const location = useLocation();
  // startsWith, not equality: the firewall entries have sub-routes (a policy
  // editor at /admin/firewall/policies/:id), and a section that collapses the
  // moment you open one of its pages loses the operator their place.
  const isChildActive = section.children.some((c) => location.pathname.startsWith(c.to));
  const [open, setOpen] = useState(isChildActive);

  return (
    <div className="mt-1">
      <button
        className="flex items-center gap-1.5 w-full bg-transparent border-none px-5 py-2 text-[11px] font-semibold uppercase tracking-widest text-slate-500 cursor-pointer transition-colors duration-150 hover:text-slate-300"
        onClick={() => setOpen(!open)}
      >
        <svg
          className={`w-3 h-3 shrink-0 transition-transform duration-150 ${open ? 'rotate-90' : ''}`}
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth="2"
          strokeLinecap="round"
          strokeLinejoin="round"
        >
          <path d="M9 18l6-6-6-6" />
        </svg>
        {section.label}
      </button>
      {open && (
        <div className="pb-1">
          {section.children.map((link) => (
            <NavLink
              key={link.to}
              to={link.to}
              end={link.end}
              className={({ isActive }) =>
                `flex items-center gap-2.5 pl-9 pr-5 py-2 text-sm font-medium transition-all duration-150 border-l-[3px] ${
                  isActive
                    ? 'bg-slate-800 text-white border-l-blue-500'
                    : 'border-l-transparent text-slate-400 hover:bg-white/5 hover:text-slate-200'
                }`
              }
            >
              <SidebarIcon name={link.icon} />
              <span>{link.label}</span>
            </NavLink>
          ))}
        </div>
      )}
    </div>
  );
}

export default function Sidebar() {
  const [license, setLicense] = useState<LicenseStatus | null>(null);
  const [version, setVersion] = useState<string | null>(null);

  useEffect(() => {
    api.get<LicenseStatus>('/system/license').then(setLicense).catch(() => {});
    api.get<StatusCheck>('/status/check').then((data) => setVersion(data.version)).catch(() => {});
  }, []);

  return (
    <aside className="w-60 min-w-60 h-full bg-slate-900 text-slate-400 flex flex-col overflow-y-auto border-r border-white/5">
      {/* Logo area */}
      <div className="px-5 py-5 border-b border-white/10 flex items-center gap-3">
        <img src="/icon.png" width="32" height="32" alt="MegaRepo" className="rounded-lg" />
        <div>
          <h2 className="text-[17px] font-bold text-white tracking-tight leading-tight">MegaRepo</h2>
          <span className="text-[10px] text-slate-500 tracking-wide block mt-0.5">BSNSoft Solutions GmbH</span>
          <span className="text-[10px] text-slate-500 block mt-0.5">
            {license?.licensed ? `Licensed to ${license.company}` : 'Community Edition'}
          </span>
        </div>
      </div>

      {/* Navigation */}
      <nav className="flex-1 py-3">
        <div className="px-5 pt-3 pb-2 text-[11px] font-semibold uppercase tracking-widest text-slate-500">Browse</div>
        {browseLinks.map((link) => (
          <NavLink
            key={link.to}
            to={link.to}
            end={link.to === '/'}
            className={({ isActive }) =>
              `flex items-center gap-2.5 px-5 py-2 text-sm font-medium transition-all duration-150 border-l-[3px] ${
                isActive
                  ? 'bg-slate-800 text-white border-l-blue-500'
                  : 'border-l-transparent text-slate-400 hover:bg-white/5 hover:text-slate-200'
              }`
            }
          >
            <SidebarIcon name={link.icon} />
            <span>{link.label}</span>
          </NavLink>
        ))}

        <div className="px-5 pt-5 pb-2 text-[11px] font-semibold uppercase tracking-widest text-slate-500">Administration</div>
        {adminSections.map((section) => (
          <CollapsibleSection key={section.label} section={section} />
        ))}
      </nav>

      {/* Version badge */}
      <div className="px-5 py-4 border-t border-white/10 text-[10px] text-slate-600">
        <div>{version ?? 'loading...'} <span className="ml-1 px-1.5 py-0.5 bg-slate-800 text-slate-400 rounded text-[9px] font-medium">beta</span></div>
        <a href="mailto:ticket@bsnsoft.de" className="block mt-2 text-slate-500 hover:text-slate-300 transition-colors">
          Support: ticket@bsnsoft.de
        </a>
      </div>
    </aside>
  );
}
