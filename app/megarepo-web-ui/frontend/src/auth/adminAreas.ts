/**
 * Which parts of the UI only work for an administrator.
 *
 * One list, used by both the navigation (Sidebar) and the route guard
 * (RequireRole), so a screen cannot end up hidden but reachable or reachable
 * but hidden.
 *
 * The entries mirror the `hasRole(ADMIN_ROLE)` matchers in the backend's
 * `SecurityConfig` — a page is listed here when the endpoints it lives on are
 * closed to a plain logged-in account:
 *
 * | route                  | server rule                          |
 * |------------------------|--------------------------------------|
 * | /admin/users           | /api/v1/security/users/**            |
 * | /admin/roles           | /api/v1/security/roles/**            |
 * | /admin/ldap            | /api/v1/security/ldap/**             |
 * | /admin/ssl             | /api/v1/security/ssl/**              |
 * | /admin/anonymous       | /api/v1/security/anonymous/**        |
 * | /admin/nvd-firewall    | /api/v1/security/nvd-firewall/**     |
 * | /admin/firewall/**     | /api/v1/admin/firewall/**, exemptions, rule-types |
 * | /admin/http-proxy      | /api/v1/system/**                    |
 * | /admin/license         | /api/v1/system/** (install / remove)  |
 *
 * Deliberately *not* listed: the repository, blob store, cleanup, routing,
 * status, task and audit screens. Their endpoints are `authenticated()` on the
 * server, so a non-administrator can use them today and hiding them would take
 * away something that works. If those endpoints are ever restricted, the route
 * belongs here in the same change — not before it.
 *
 * The read of `GET /api/v1/system/license` behind the sidebar and dashboard
 * licence banner is open to every logged-in user by design and is not gated
 * anywhere in this file; only the licence *administration* page is.
 */
export const ADMIN_ONLY_ROUTES = [
  '/admin/users',
  '/admin/roles',
  '/admin/ldap',
  '/admin/ssl',
  '/admin/anonymous',
  '/admin/nvd-firewall',
  '/admin/firewall',
  '/admin/http-proxy',
  '/admin/license',
] as const;

/**
 * True when `pathname` is one of the administrator-only routes or a page below
 * one (the policy editor at `/admin/firewall/policies/:id`, for instance).
 */
export function isAdminOnlyRoute(pathname: string): boolean {
  return ADMIN_ONLY_ROUTES.some(
    (route) => pathname === route || pathname.startsWith(`${route}/`),
  );
}
