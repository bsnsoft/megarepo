import { Link, Outlet } from 'react-router-dom';
import type { ReactNode } from 'react';
import { useAuth } from './AuthContext';

interface Props {
  /** Role id the session has to carry, e.g. `nx-admin`. */
  role: string;
  /** Guarded content. Omitted for a layout route, which renders its `Outlet`. */
  children?: ReactNode;
}

/**
 * The screen a member of staff without the role gets instead of the page.
 *
 * Rendered in place, so the address bar still shows what was asked for — a
 * redirect to the dashboard here looks like the click was lost. It replaces
 * what used to happen: the page mounted, fired its requests, and filled up
 * with "You don't have permission to perform this action." from every one of
 * them.
 */
export function AccessDenied() {
  return (
    <div className="flex flex-col items-center justify-center py-20 px-4">
      <div className="w-12 h-12 rounded-full bg-slate-100 flex items-center justify-center mb-4">
        <svg
          width="24"
          height="24"
          viewBox="0 0 24 24"
          fill="none"
          stroke="#475569"
          strokeWidth="2"
          strokeLinecap="round"
          strokeLinejoin="round"
          aria-hidden="true"
        >
          <rect x="4" y="11" width="16" height="10" rx="2" />
          <path d="M8 11V7a4 4 0 0 1 8 0v4" />
        </svg>
      </div>
      <h1 className="text-base font-semibold text-gray-900 mb-1">Administrators only</h1>
      <p className="text-sm text-gray-500 mb-5 text-center max-w-md">
        This page is part of MegaRepo's administration. Your account does not have the
        administrator role, so there is nothing here for it to show. Ask an administrator of this
        instance if you need access.
      </p>
      <Link
        to="/"
        className="inline-flex items-center gap-1.5 px-4 py-2 bg-blue-600 hover:bg-blue-700 text-white text-sm font-medium rounded-md transition-colors"
      >
        Back to dashboard
      </Link>
    </div>
  );
}

/**
 * Route guard for the administration screens. Sits inside `ProtectedRoute`,
 * which has already established that somebody is logged in; this only asks
 * whether they hold `role`, and shows {@link AccessDenied} when they do not.
 *
 * Being a route element rather than a check inside each page is what makes a
 * pasted URL behave like a click in the navigation.
 *
 * As everywhere on this side of the wire, the check is presentation. The server
 * refuses the same requests on its own — see the note on `hasRole` in
 * AuthContext.
 */
export default function RequireRole({ role, children }: Props) {
  const { hasRole } = useAuth();

  if (!hasRole(role)) {
    return <AccessDenied />;
  }

  return <>{children ?? <Outlet />}</>;
}
