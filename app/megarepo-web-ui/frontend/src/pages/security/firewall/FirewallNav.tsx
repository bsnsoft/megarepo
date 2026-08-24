import { NavLink } from 'react-router-dom';

/**
 * One strip across the top of every firewall screen.
 *
 * The five screens are one job split by verb — arm it, work the queue, write the
 * policy, decide the exemptions — and an operator moves between them constantly:
 * a held component leads to an exemption, an exemption leads to the policy that
 * held it. Making that a trip back through the sidebar would put four clicks
 * between a finding and the fix.
 */

const TABS: { to: string; label: string; end?: boolean }[] = [
  { to: '/admin/firewall', label: 'Overview', end: true },
  { to: '/admin/firewall/quarantine', label: 'Quarantine' },
  { to: '/admin/firewall/policies', label: 'Policies' },
  { to: '/admin/firewall/exemptions', label: 'Exemptions' },
];

export default function FirewallNav() {
  return (
    <nav aria-label="Repository Firewall" className="border-b border-gray-200">
      <ul className="flex gap-1 -mb-px">
        {TABS.map((tab) => (
          <li key={tab.to}>
            <NavLink
              to={tab.to}
              end={tab.end}
              className={({ isActive }) =>
                `inline-block px-4 py-2 text-sm font-medium border-b-2 transition-colors ${
                  isActive
                    ? 'border-blue-600 text-blue-700'
                    : 'border-transparent text-gray-500 hover:text-gray-800 hover:border-gray-300'
                }`
              }
            >
              {tab.label}
            </NavLink>
          </li>
        ))}
      </ul>
    </nav>
  );
}
