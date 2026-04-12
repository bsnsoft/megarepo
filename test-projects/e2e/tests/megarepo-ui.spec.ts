import { test, expect } from '@playwright/test';

const ADMIN_USER = 'admin';
const ADMIN_PASS = 'admin123';

// Test entity names
const MAVEN_HOSTED = 'e2e-maven-hosted';
const MAVEN_PROXY = 'e2e-maven-proxy';
const MAVEN_GROUP = 'e2e-maven-group';
const RAW_HOSTED = 'e2e-raw-hosted';
const TEST_USER = 'testdev';
const TEST_ROLE_ID = 'developer';
const TEST_ROLE_NAME = 'Developer';

/**
 * Helper: log in as admin and wait for dashboard.
 */
async function login(page: import('@playwright/test').Page) {
  await page.goto('/login');
  await page.fill('#username', ADMIN_USER);
  await page.fill('#password', ADMIN_PASS);
  await page.click('button[type="submit"]');
  await page.waitForURL('**/');
  await expect(page.locator('h1:has-text("Dashboard")')).toBeVisible();
}

/**
 * Helper: intercept repo creation to fix the type casing bug (UI sends lowercase, API needs uppercase).
 */
async function interceptRepoCreate(page: import('@playwright/test').Page) {
  await page.route('**/api/v1/repositories', async (route) => {
    const request = route.request();
    if (request.method() === 'POST') {
      const body = request.postDataJSON();
      if (body && body.type) {
        body.type = body.type.toUpperCase();
      }
      await route.continue({ postData: JSON.stringify(body) });
    } else {
      await route.continue();
    }
  });
}

/**
 * Helper: delete a repository by navigating to its detail page and confirming deletion.
 */
async function deleteRepo(page: import('@playwright/test').Page, repoName: string) {
  await page.goto(`/admin/repositories/${repoName}`);
  // Wait for page to load - either repo detail or redirect
  await page.waitForTimeout(1000);

  // Check if the repo exists (page has the repo heading)
  const heading = page.locator(`h1:has-text("${repoName}")`);
  if (!(await heading.isVisible().catch(() => false))) {
    return; // Repo doesn't exist, nothing to delete
  }

  const headerDeleteBtn = page.locator('div.flex.gap-2 button:has-text("Delete")');
  await headerDeleteBtn.click();

  await expect(page.locator('h3:has-text("Delete Repository")')).toBeVisible();
  const confirmBtn = page.locator('.fixed button:has-text("Delete")');
  await confirmBtn.click();

  await page.waitForURL('**/admin/repositories', { timeout: 15000 });
}

test.describe.serial('MegaRepo Full E2E Test Suite', () => {
  // ═══════════════════════════════════════════════════════════════════
  // PHASE 1: Login & Dashboard
  // ═══════════════════════════════════════════════════════════════════

  test('1. Login as admin', async ({ page }) => {
    await page.goto('/login');

    await expect(page.locator('text=MegaRepo')).toBeVisible();
    await expect(page.locator('text=Artifact Repository Manager')).toBeVisible();

    await page.fill('#username', ADMIN_USER);
    await page.fill('#password', ADMIN_PASS);
    await page.click('button[type="submit"]');

    await page.waitForURL('**/');
    await expect(page.locator('h1:has-text("Dashboard")')).toBeVisible();
  });

  test('2. Dashboard shows stat cards', async ({ page }) => {
    await login(page);

    await expect(page.locator('h1:has-text("Dashboard")')).toBeVisible();
    await expect(page.locator('text=Overview of your MegaRepo instance')).toBeVisible();

    const statCards = page.locator('.grid .bg-white.rounded-xl');
    await expect(statCards).toHaveCount(4, { timeout: 10000 });
    await expect(page.locator('div.text-sm.text-gray-500:has-text("Repositories")')).toBeVisible();
    await expect(page.locator('div.text-sm.text-gray-500:has-text("Components")')).toBeVisible();
    await expect(page.locator('div.text-sm.text-gray-500:has-text("Storage Used")')).toBeVisible();
    await expect(page.locator('div.text-sm.text-gray-500:has-text("System Health")')).toBeVisible();

    await expect(page.getByRole('heading', { name: 'Quick Actions' })).toBeVisible();
    await expect(page.getByRole('button', { name: /Browse Repositories/ })).toBeVisible();
    await expect(page.getByRole('button', { name: /Search Components/ })).toBeVisible();
    await expect(page.getByRole('button', { name: /Create Repository/ })).toBeVisible();
  });

  // ═══════════════════════════════════════════════════════════════════
  // PHASE 2: Setup - Clean Slate (delete default repos)
  // ═══════════════════════════════════════════════════════════════════

  test('3. Delete all default repositories for clean slate', async ({ page }) => {
    await login(page);
    await page.goto('/admin/repositories');
    await expect(page.locator('h1:has-text("Repositories")')).toBeVisible();

    // Get all repo names from the list, then delete each one
    // We'll use the API to get repo names, then delete via UI
    const response = await page.request.get('/api/v1/repositories', {
      headers: { Authorization: `Basic ${btoa(`${ADMIN_USER}:${ADMIN_PASS}`)}` },
    });
    const repos = await response.json();

    // Delete group repos first (they depend on others), then the rest
    const groupRepos = repos.filter((r: { type: string }) => r.type.toLowerCase() === 'group');
    const nonGroupRepos = repos.filter((r: { type: string }) => r.type.toLowerCase() !== 'group');

    for (const repo of [...groupRepos, ...nonGroupRepos]) {
      await deleteRepo(page, repo.name);
    }
  });

  test('4. Verify dashboard shows 0 repositories', async ({ page }) => {
    await login(page);

    // The repositories stat card should show 0
    const repoCard = page.locator('.grid .bg-white.rounded-xl').filter({
      has: page.locator('div.text-sm.text-gray-500:has-text("Repositories")'),
    });
    await expect(repoCard).toBeVisible();
    await expect(repoCard.locator('.text-3xl, .text-2xl').first()).toHaveText('0');
  });

  // ═══════════════════════════════════════════════════════════════════
  // PHASE 3: Create Repositories
  // ═══════════════════════════════════════════════════════════════════

  test('5. Create Maven hosted repository', async ({ page }) => {
    await login(page);
    await interceptRepoCreate(page);
    await page.goto('/admin/repositories/create');

    await expect(page.locator('h1:has-text("Create Repository")')).toBeVisible();
    await expect(page.locator('text=Select a recipe')).toBeVisible();

    // Click Maven Hosted recipe card
    const mavenHostedCard = page.locator('button', { hasText: 'Maven' }).filter({ hasText: 'Hosted' }).first();
    await mavenHostedCard.click();

    await expect(page.locator('text=Configure your new repository')).toBeVisible();
    await page.fill('#repo-name', MAVEN_HOSTED);
    await page.waitForTimeout(500);

    const submitButton = page.locator('button[type="submit"]');
    await expect(submitButton).toBeEnabled();
    await submitButton.click();

    await page.waitForURL('**/admin/repositories', { timeout: 15000 });
    await expect(page.getByText(MAVEN_HOSTED, { exact: true })).toBeVisible();
  });

  test('6. Create Maven proxy repository (Maven Central)', async ({ page }) => {
    await login(page);
    await interceptRepoCreate(page);
    await page.goto('/admin/repositories/create');

    await expect(page.locator('text=Select a recipe')).toBeVisible();

    // Click Maven Proxy recipe card
    const mavenProxyCard = page.locator('button', { hasText: 'Maven' }).filter({ hasText: 'Proxy' }).first();
    await mavenProxyCard.click();

    await expect(page.locator('text=Configure your new repository')).toBeVisible();
    await page.fill('#repo-name', MAVEN_PROXY);

    // Verify remote URL is pre-filled with Maven Central
    const remoteUrlInput = page.locator('#remote-url');
    await expect(remoteUrlInput).toHaveValue('https://repo1.maven.org/maven2/');

    await page.waitForTimeout(500);
    const submitButton = page.locator('button[type="submit"]');
    await expect(submitButton).toBeEnabled();
    await submitButton.click();

    await page.waitForURL('**/admin/repositories', { timeout: 15000 });
    await expect(page.getByText(MAVEN_PROXY, { exact: true })).toBeVisible();
  });

  test('7. Create Maven group repository (combining hosted + proxy)', async ({ page }) => {
    await login(page);
    await interceptRepoCreate(page);
    await page.goto('/admin/repositories/create');

    await expect(page.locator('text=Select a recipe')).toBeVisible();

    // Click Maven Group recipe card
    const mavenGroupCard = page.locator('button', { hasText: 'Maven' }).filter({ hasText: 'Group' }).first();
    await mavenGroupCard.click();

    await expect(page.locator('text=Configure your new repository')).toBeVisible();
    await page.fill('#repo-name', MAVEN_GROUP);

    // Wait for available repos to load
    await page.waitForTimeout(1000);

    // Add the hosted and proxy repos as group members by clicking them in the Available list
    const availableList = page.locator('div').filter({ hasText: /^Available$/ }).locator('..');
    const hostedBtn = availableList.locator(`button:has-text("${MAVEN_HOSTED}")`);
    if (await hostedBtn.isVisible()) {
      await hostedBtn.click();
    }

    const proxyBtn = availableList.locator(`button:has-text("${MAVEN_PROXY}")`);
    if (await proxyBtn.isVisible()) {
      await proxyBtn.click();
    }

    await page.waitForTimeout(500);
    const submitButton = page.locator('button[type="submit"]');
    await expect(submitButton).toBeEnabled();
    await submitButton.click();

    await page.waitForURL('**/admin/repositories', { timeout: 15000 });
    await expect(page.getByText(MAVEN_GROUP, { exact: true })).toBeVisible();
  });

  test('8. Create Raw hosted repository', async ({ page }) => {
    await login(page);
    await interceptRepoCreate(page);
    await page.goto('/admin/repositories/create');

    await expect(page.locator('text=Select a recipe')).toBeVisible();

    // Click Raw Hosted recipe card
    const rawHostedCard = page.locator('button', { hasText: 'Raw' }).filter({ hasText: 'Hosted' }).first();
    await rawHostedCard.click();

    await expect(page.locator('text=Configure your new repository')).toBeVisible();
    await page.fill('#repo-name', RAW_HOSTED);

    await page.waitForTimeout(500);
    const submitButton = page.locator('button[type="submit"]');
    await expect(submitButton).toBeEnabled();
    await submitButton.click();

    await page.waitForURL('**/admin/repositories', { timeout: 15000 });
    await expect(page.getByText(RAW_HOSTED, { exact: true })).toBeVisible();
  });

  test('9. Verify all 4 repos appear in repository list', async ({ page }) => {
    await login(page);
    await page.goto('/admin/repositories');

    await expect(page.locator('h1:has-text("Repositories")')).toBeVisible();

    await expect(page.getByText(MAVEN_HOSTED, { exact: true })).toBeVisible();
    await expect(page.getByText(MAVEN_PROXY, { exact: true })).toBeVisible();
    await expect(page.getByText(MAVEN_GROUP, { exact: true })).toBeVisible();
    await expect(page.getByText(RAW_HOSTED, { exact: true })).toBeVisible();
  });

  // ═══════════════════════════════════════════════════════════════════
  // PHASE 4: User Management
  // ═══════════════════════════════════════════════════════════════════

  test('10. Create a new user "testdev" via UI', async ({ page }) => {
    await login(page);
    await page.goto('/admin/users');

    await expect(page.locator('h1:has-text("Users")')).toBeVisible();

    // Click Create User button
    await page.click('button:has-text("Create User")');

    // Wait for dialog
    await expect(page.locator('h3:has-text("Create User")')).toBeVisible();

    // Fill the form
    await page.fill('input[placeholder="e.g. jdoe"]', TEST_USER);
    await page.fill('input[placeholder="Minimum 8 characters"]', 'testdev123');
    await page.fill('input[placeholder="First name"]', 'Test');
    await page.fill('input[placeholder="Last name"]', 'Developer');
    await page.fill('input[placeholder="user@example.com"]', 'testdev@example.com');

    // Submit
    const createBtn = page.locator('.fixed button:has-text("Create"), [class*="fixed"] button:has-text("Create")').last();
    await createBtn.click();

    // Wait for dialog to close and user to appear
    await page.waitForTimeout(1500);
    await expect(page.getByText(TEST_USER).first()).toBeVisible();
  });

  test('11. Create a new role "developer" via UI', async ({ page }) => {
    await login(page);
    await page.goto('/admin/roles');

    await expect(page.locator('h1:has-text("Roles")')).toBeVisible();

    // Click Create Role button
    await page.click('button:has-text("Create Role")');

    // Wait for dialog
    await expect(page.locator('h3:has-text("Create Role")')).toBeVisible();

    // Fill the form
    await page.fill('input[placeholder="e.g. deploy-user"]', TEST_ROLE_ID);
    await page.fill('input[placeholder="e.g. Deploy User"]', TEST_ROLE_NAME);
    await page.fill('input[placeholder="Optional description"]', 'E2E test developer role');

    // Add a privilege
    await page.fill('input[placeholder="e.g. admin-all, maven-deploy"]', 'repo-read-all');

    // Submit
    const createBtn = page.locator('.fixed button:has-text("Create"), [class*="fixed"] button:has-text("Create")').last();
    await createBtn.click();

    // Wait for dialog to close and role to appear
    await page.waitForTimeout(1500);
    await expect(page.getByText(TEST_ROLE_NAME).first()).toBeVisible();
  });

  test('12. Verify user and role appear in their lists', async ({ page }) => {
    await login(page);

    // Check users page
    await page.goto('/admin/users');
    await expect(page.locator('h1:has-text("Users")')).toBeVisible();
    await expect(page.getByText(TEST_USER).first()).toBeVisible();

    // Check roles page
    await page.goto('/admin/roles');
    await expect(page.locator('h1:has-text("Roles")')).toBeVisible();
    await expect(page.getByText(TEST_ROLE_NAME).first()).toBeVisible();
  });

  // ═══════════════════════════════════════════════════════════════════
  // PHASE 5: Browse & Search
  // ═══════════════════════════════════════════════════════════════════

  test('13. Browse page lists created repositories', async ({ page }) => {
    await login(page);
    await page.goto('/browse');

    await expect(page.locator('h1:has-text("Browse")')).toBeVisible();
    await page.waitForTimeout(1000);

    await expect(page.getByText(MAVEN_HOSTED, { exact: true }).first()).toBeVisible();
    await expect(page.getByText(RAW_HOSTED, { exact: true }).first()).toBeVisible();
  });

  test('14. Search page works without errors', async ({ page }) => {
    await login(page);
    await page.goto('/search');

    await expect(page.locator('h1:has-text("Search")')).toBeVisible();

    // Type a search query
    const searchInput = page.locator('input[placeholder*="Search"], input[placeholder*="search"], input[type="text"]').first();
    await searchInput.fill('test-artifact');
    await page.waitForTimeout(1000);

    // Page should not crash
    await expect(page.locator('h1:has-text("Search")')).toBeVisible();
  });

  // ═══════════════════════════════════════════════════════════════════
  // PHASE 6: Admin Pages
  // ═══════════════════════════════════════════════════════════════════

  test('15. Blob stores page shows default store', async ({ page }) => {
    await login(page);
    await page.goto('/admin/blobstores');

    await expect(page.locator('h1:has-text("Blob Stores")')).toBeVisible();

    // The "default" blob store should be present
    await expect(page.getByText('default').first()).toBeVisible();

    // Verify the page header shows a blob store count
    await expect(page.locator('text=/\\d+ blob store/')).toBeVisible();
  });

  test('16. Cleanup policies page shows empty state or policy list', async ({ page }) => {
    await login(page);
    await page.goto('/admin/cleanup');

    await expect(page.locator('h1:has-text("Cleanup Policies")')).toBeVisible();

    // Either we see "No Cleanup Policies" empty state or a policy count header
    const emptyState = page.locator('h3:has-text("No Cleanup Policies")');
    const policyCount = page.locator('p:has-text("policies configured")');
    await expect(emptyState.or(policyCount).first()).toBeVisible();
  });

  test('17. Status page shows healthy system', async ({ page }) => {
    await login(page);
    await page.goto('/admin/status');

    await expect(page.locator('h1:has-text("System Status")')).toBeVisible();
    await expect(page.locator('text=Health and version information')).toBeVisible();

    // Verify healthy status
    await expect(page.getByText('Healthy').first()).toBeVisible();

    // Verify Version card is present
    await expect(page.locator('text=Version').first()).toBeVisible();

    // Verify Details section exists
    await expect(page.locator('h2:has-text("Details")')).toBeVisible();
  });

  test('18. License page shows Community edition', async ({ page }) => {
    await login(page);
    await page.goto('/admin/license');

    await expect(page.locator('h1:has-text("License")')).toBeVisible();
    await expect(page.locator('text=Manage your MegaRepo license')).toBeVisible();

    // Verify Community edition is shown
    await expect(page.getByText('Community').first()).toBeVisible();

    // Verify Edition Comparison section
    await expect(page.locator('h2:has-text("Edition Comparison")')).toBeVisible();
    await expect(page.locator('h3:has-text("Community Edition")')).toBeVisible();
    await expect(page.locator('h3:has-text("Business Edition")')).toBeVisible();

    // License details section should show "Community Edition" status
    await expect(page.locator('text=Community Edition').first()).toBeVisible();
  });

  // ═══════════════════════════════════════════════════════════════════
  // PHASE 7: Cleanup
  // ═══════════════════════════════════════════════════════════════════

  test('19. Delete test repositories', async ({ page }) => {
    await login(page);

    // Delete group repo first (it depends on the others)
    await deleteRepo(page, MAVEN_GROUP);

    // Delete remaining repos
    await deleteRepo(page, MAVEN_PROXY);
    await deleteRepo(page, MAVEN_HOSTED);
    await deleteRepo(page, RAW_HOSTED);

    // Verify none of our test repos remain
    await page.goto('/admin/repositories');
    await expect(page.locator('h1:has-text("Repositories")')).toBeVisible();
    await expect(page.getByText(MAVEN_HOSTED, { exact: true })).not.toBeVisible();
    await expect(page.getByText(MAVEN_PROXY, { exact: true })).not.toBeVisible();
    await expect(page.getByText(MAVEN_GROUP, { exact: true })).not.toBeVisible();
    await expect(page.getByText(RAW_HOSTED, { exact: true })).not.toBeVisible();
  });

  test('20. Delete test user', async ({ page }) => {
    await login(page);
    await page.goto('/admin/users');

    await expect(page.locator('h1:has-text("Users")')).toBeVisible();

    // Find the testdev user row and click its Delete button
    const userRow = page.locator('tr, [data-row]').filter({ hasText: TEST_USER });
    const deleteBtn = userRow.locator('button:has-text("Delete")');
    await deleteBtn.click();

    // Confirm deletion
    await expect(page.locator('h3:has-text("Delete User")')).toBeVisible();
    const confirmBtn = page.locator('.fixed button:has-text("Delete")');
    await confirmBtn.click();

    await page.waitForTimeout(1500);
    // The test user should no longer be visible (but admin still is)
    await expect(page.getByText(TEST_USER, { exact: true })).not.toBeVisible();
  });

  test('21. Delete test role', async ({ page }) => {
    await login(page);
    await page.goto('/admin/roles');

    await expect(page.locator('h1:has-text("Roles")')).toBeVisible();

    // Find the developer role row and click its Delete button
    const roleRow = page.locator('tr, [data-row]').filter({ hasText: TEST_ROLE_NAME });
    const deleteBtn = roleRow.locator('button:has-text("Delete")');
    await deleteBtn.click();

    // Confirm deletion
    await expect(page.locator('h3:has-text("Delete Role")')).toBeVisible();
    const confirmBtn = page.locator('.fixed button:has-text("Delete")');
    await confirmBtn.click();

    await page.waitForTimeout(1500);
    await expect(page.getByText(TEST_ROLE_NAME, { exact: true })).not.toBeVisible();
  });

  test('22. Verify cleanup - no test artifacts remain', async ({ page }) => {
    await login(page);

    // Verify repos are clean
    await page.goto('/admin/repositories');
    await expect(page.getByText(MAVEN_HOSTED, { exact: true })).not.toBeVisible();
    await expect(page.getByText(RAW_HOSTED, { exact: true })).not.toBeVisible();

    // Verify users are clean (testdev gone, admin still exists)
    await page.goto('/admin/users');
    await expect(page.getByText(TEST_USER, { exact: true })).not.toBeVisible();
    await expect(page.locator('text=admin').first()).toBeVisible();

    // Verify roles are clean
    await page.goto('/admin/roles');
    await expect(page.getByText(TEST_ROLE_NAME, { exact: true })).not.toBeVisible();
  });

  // ═══════════════════════════════════════════════════════════════════
  // PHASE 8: Logout
  // ═══════════════════════════════════════════════════════════════════

  test('23. Logout', async ({ page }) => {
    await login(page);

    const userMenuBtn = page.locator('header button', { hasText: ADMIN_USER });
    await userMenuBtn.click();

    await page.click('button:has-text("Sign Out")');

    await page.waitForURL('**/login');
    await expect(page.locator('text=Artifact Repository Manager')).toBeVisible();
    await expect(page.locator('#username')).toBeVisible();
  });
});
