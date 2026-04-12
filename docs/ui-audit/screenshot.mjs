import { chromium } from 'playwright';

const BASE = 'http://localhost:8080';
const OUT = 'docs/ui-audit/screenshots';

const pages = [
  { name: '01-login', path: '/login', noAuth: true },
  { name: '02-dashboard', path: '/' },
  { name: '03-browse', path: '/browse' },
  { name: '04-search', path: '/search' },
  { name: '05-upload', path: '/upload' },
  { name: '06-repositories', path: '/admin/repositories' },
  { name: '07-repo-create', path: '/admin/repositories/create' },
  { name: '08-blobstores', path: '/admin/blobstores' },
  { name: '09-cleanup', path: '/admin/cleanup' },
  { name: '10-routing-rules', path: '/admin/routing-rules' },
  { name: '11-users', path: '/admin/users' },
  { name: '12-roles', path: '/admin/roles' },
  { name: '13-ldap', path: '/admin/ldap' },
  { name: '14-ssl', path: '/admin/ssl' },
  { name: '15-anonymous', path: '/admin/anonymous' },
  { name: '16-status', path: '/admin/status' },
  { name: '17-tasks', path: '/admin/tasks' },
  { name: '18-audit', path: '/admin/audit' },
  { name: '19-license', path: '/admin/license' },
  { name: '20-account', path: '/account' },
];

const browser = await chromium.launch();
const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
const page = await context.newPage();

// Login first
await page.goto(`${BASE}/login`);
await page.fill('input[id="username"]', 'admin');
await page.fill('input[id="password"]', 'admin123');
await page.screenshot({ path: `${OUT}/01-login.png`, fullPage: true });
await page.click('button[type="submit"]');
await page.waitForURL('**/');
await page.waitForTimeout(1000);

// Save auth cookies
const cookies = await context.cookies();

for (const p of pages) {
  if (p.name === '01-login') continue; // already captured
  try {
    await page.goto(`${BASE}${p.path}`, { waitUntil: 'networkidle', timeout: 10000 });
    await page.waitForTimeout(500);
    await page.screenshot({ path: `${OUT}/${p.name}.png`, fullPage: true });
    console.log(`OK: ${p.name}`);
  } catch (e) {
    console.log(`FAIL: ${p.name} - ${e.message}`);
  }
}

await browser.close();
console.log('Done! Screenshots in', OUT);
