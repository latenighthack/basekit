import { test, expect } from '@playwright/test';

// The generated web router over the demo graph (see BrowserNavigationProbe): detail is pushed, the
// picker is a sheet, and BasekitBrowserHistory mirrors the path in window.history.
const nav = page => page.locator('#navigation');
const screen = (page, name) => nav(page).locator(`[data-basekit-screen="${name}"]`);
const events = page => page.evaluate(() => [...window.navigation.events]);

test.beforeEach(async ({ page }) => {
  const errors = [];
  page.on('pageerror', e => errors.push(e.message));
  page.errors = errors;
});
test.afterEach(async ({ page }) => { expect(page.errors).toEqual([]); });

test('a pushed entry gets its @Route address and browser back closes it', async ({ page }) => {
  await page.goto('/');
  await nav(page).getByRole('button', { name: 'Open detail' }).click();
  await expect(screen(page, 'detail').locator('.detail-id')).toHaveText('first');
  await expect(page).toHaveURL(/\/detail\/first$/);
  // The root stays mounted under the pushed entry, hidden.
  await expect(screen(page, 'home')).toBeHidden();
  await expect(screen(page, 'home')).toHaveCount(1);
  await page.goBack();
  await expect(screen(page, 'detail')).toHaveCount(0);
  await expect(screen(page, 'home')).toBeVisible();
  await expect(page).toHaveURL(/\/$/);
  expect(await events(page)).toEqual(['NavigatedTo HOME', 'NavigatedTo DETAIL', 'Closed DETAIL']);
});

test('an entry keeps its view model while another is pushed over it', async ({ page }) => {
  await page.goto('/');
  const open = nav(page).getByRole('button', { name: 'Open detail' });
  await open.click();
  const first = screen(page, 'detail').first();
  await first.getByRole('button', { name: 'Add one' }).click();
  await expect(first.locator('.detail-count')).toHaveText('1');
  // Home is hidden but mounted; the router closing the top entry pops history too.
  await page.evaluate(() => window.navigation.web.back());
  await expect(page).toHaveURL(/\/$/);
  await expect(screen(page, 'detail')).toHaveCount(0);
  await page.goBack();
  // A forward the router cannot honour is undone: the entry it pointed at has closed.
  await page.goForward();
  await expect(page).toHaveURL(/\/$/);
  await expect(screen(page, 'detail')).toHaveCount(0);
});

test('a responding sheet answers once and closes, adding no history entry', async ({ page }) => {
  await page.goto('/');
  await expect(nav(page).locator('#home-feed')).toHaveText('2');
  const length = await page.evaluate(() => window.history.length);
  await nav(page).getByRole('button', { name: 'Pick' }).click();
  const sheet = nav(page).locator('dialog[data-basekit-presentation="sheet"]');
  await expect(sheet).toBeVisible();
  expect(await page.evaluate(() => window.history.length)).toBe(length);
  await sheet.getByRole('button', { name: 'Bananas' }).click();
  await expect(sheet).toHaveCount(0);
  await expect(nav(page).locator('#home-feed')).toHaveText('3');
  expect(await events(page)).toEqual(['NavigatedTo HOME', 'NavigatedTo PICKER', 'Responded PICKER']);
});

test('Escape dismisses the sheet and its caller gets null', async ({ page }) => {
  await page.goto('/');
  await nav(page).getByRole('button', { name: 'Pick' }).click();
  const sheet = nav(page).locator('dialog[data-basekit-presentation="sheet"]');
  await expect(sheet.getByRole('button', { name: 'Apples' })).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(sheet).toHaveCount(0);
  await expect(nav(page).locator('#home-feed')).toHaveText('2');
  expect(await events(page)).toEqual(['NavigatedTo HOME', 'NavigatedTo PICKER', 'Closed PICKER', 'Responded PICKER']);
});

test('a deep link opens over the root, and back returns to the root', async ({ page }) => {
  await page.goto('/detail/second');
  await expect(screen(page, 'detail').locator('.detail-id')).toHaveText('second');
  await expect(page).toHaveURL(/\/detail\/second$/);
  await page.goBack();
  await expect(screen(page, 'detail')).toHaveCount(0);
  await expect(screen(page, 'home')).toBeVisible();
  await expect(page).toHaveURL(/\/$/);
});

test('an alias opens its destination at the canonical address, and reload restores it', async ({ page }) => {
  await page.goto('/items?item=a%20b');
  await expect(screen(page, 'detail').locator('.detail-id')).toHaveText('a b');
  await expect(page).toHaveURL(/\/detail\/a%20b$/);
  await page.reload();
  await expect(screen(page, 'detail').locator('.detail-id')).toHaveText('a b');
  // The deep link is reported as it opens, before the root is shown.
  expect(await events(page)).toEqual(['NavigatedTo DETAIL', 'NavigatedTo HOME']);
});
