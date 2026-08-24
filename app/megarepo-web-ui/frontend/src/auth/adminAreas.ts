/**
 * Which parts of the UI only work for an administrator.
 *
 * One list, used by both the navigation (Sidebar) and the route guard
 * (RequireRole), so a screen cannot end up hidden but reachable or reachable
 * but hidden.
 *
 * ————————————————————————————————————————————————————————————————————————
 * This file is user experience, not security. It decides what a session is
 * *shown*; it decides nothing about what a session may *do*. The authority is
 * the server — `SecurityConfig`'s filter chain enforces every rule below for
 * itself, against a caller who never runs this code. Nothing here may ever
 * become the reason a server-side check is left out.
 *
 * The direction of the dependency follows from that: the chain is written
 * first and this list mirrors it. A route belongs here when, and only when,
 * the endpoints its page lives on are closed to a plain logged-in account.
 * Listing more than the chain closes takes away a screen that works.
 * ————————————————————————————————————————————————————————————————————————
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
 * | /admin/tasks           | /api/v1/tasks/**                     |
 * | /admin/blobstores      | /api/v1/blobstores/**                |
 * | /admin/cleanup         | /api/v1/cleanup-policies/**          |
 * | /admin/routing-rules   | /api/v1/routing-rules/**             |
 * | /admin/audit           | /api/v1/audit/**                     |
 *
 * The lower five are the operational screens. Each of them is a page with one
 * subject and one prefix: the tasks page reads and writes `/tasks`, the blob
 * store page `/blobstores`, and so on for cleanup policies, routing rules and
 * the audit log. The chain closed those prefixes whole — reads included, for
 * reasons stated there (a blob store listing carries S3 credentials; an audit
 * row carries who fetched what from which address) — so there is nothing left
 * on those pages for a non-administrator to do, and a whole-route guard is the
 * honest answer.
 *
 * Deliberately *not* listed, and each for a reason:
 *
 *   * `/admin/repositories`, `/admin/repositories/create`,
 *     `/admin/repositories/:name` and `.../edit`. Only `DELETE` on a
 *     repository is administrator-only; creating, updating and reading are
 *     open on purpose, because the documented provisioning recipes
 *     (admin-guide.md §5, migration-from-nexus.md §2.2, test-projects/setup.sh)
 *     do exactly that with a non-administrative account. Taking those screens
 *     away would remove a workflow the server still serves. What is gated
 *     there is gated per element instead — see below.
 *   * `/admin/status`, `/browse/**`, `/search`, `/upload`, `/account`. Their
 *     endpoints are `authenticated()`; every logged-in account may use them.
 *
 * The read of `GET /api/v1/system/license` behind the sidebar and dashboard
 * licence banner is open to every logged-in user by design and is not gated
 * anywhere in this file; only the licence *administration* page is.
 *
 * ## Element-level gating
 *
 * Where a page is open but one control on it is not, the control is hidden
 * with `useAuth().isAdmin` at the call site rather than listed here. Those
 * places, all of them tied to a verb-precise rule in the chain:
 *
 * | control                                    | server rule                        |
 * |--------------------------------------------|------------------------------------|
 * | Delete component (ComponentDetailPage)     | DELETE /api/v1/components/*        |
 * | Delete asset (ComponentDetailPage)         | DELETE /api/v1/assets/*            |
 * | Delete repository (list + detail page)     | DELETE /api/v1/repositories/*      |
 * | Bootstrap from Nexus / Import Preset / Export YAML | /api/v1/admin/**           |
 * | Blob store picker (RepositoryCreatePage)   | GET /api/v1/blobstores/**          |
 *
 * The first two sit under `/browse`, which every logged-in account opens for
 * its actual purpose. Gating the route there would take browsing away to hide
 * a button.
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
  '/admin/tasks',
  '/admin/blobstores',
  '/admin/cleanup',
  '/admin/routing-rules',
  '/admin/audit',
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
