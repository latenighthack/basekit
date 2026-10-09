import { test, expect } from '@playwright/test';
test('real PostHog captures one action and unified navigation events through the npm bridge', async ({ page }) => {
  await page.goto('/');
  await page.getByRole('button', {name: 'Edit', exact: true}).click();
  await page.getByRole('button', {name: 'Fail', exact: true}).click();
  await expect(page.locator('#error')).toContainText('probe failure');
  await page.evaluate(() => {
    window.bindingProbe.navigate();
    window.basekitMetrics.recordScreen('HOME', { entry: 'cold' });
  });
  expect(await page.evaluate(() => window.metricsErrors)).toEqual([]);
  const events = await page.evaluate(() => window.metricsEvents.filter(e => e.properties.app === 'browser-fixture'));
  expect(events.map(e => e.event)).toEqual(['basekit action invoked', '$screen', 'basekit navigation closed', 'basekit navigation responded', '$screen']);
  expect(events[0].properties.basekit_action).toBe('fail');
  expect(events[1].properties.$screen_name).toBe('DETAIL');
  expect(events[1].properties.basekit_source).toBe('HOME_ON_OPEN_DETAIL');
  expect(events[1].properties.nested).toEqual({ number: 2, flag: true });
  expect(events[4].properties.$screen_name).toBe('HOME');
  expect(events[4].properties.entry).toBe('cold');
});
test('generated package preserves typed actions, custom values and effect lifetimes', async ({ page }) => {
  const errors = [];
  page.on('pageerror', e => errors.push(e.message));
  await page.goto('/');
  await expect(page.locator('#note')).toHaveText('null');
  await page.getByRole('button', {name: 'Edit', exact: true}).click();
  await expect(page.locator('#note')).toHaveText('changed');
  await page.getByRole('button', {name: 'Clear', exact: true}).click();
  await expect(page.locator('#note')).toHaveText('null');
  await page.getByRole('button', {name: 'Custom', exact: true}).click();
  await expect(page.locator('#message')).toHaveText('custom');
  await page.getByRole('button', {name: 'Enum', exact: true}).click();
  await expect(page.locator('#failure')).toHaveText('RETRY');
  await page.getByRole('button', {name: 'Fail', exact: true}).click();
  await expect(page.locator('#error')).toContainText('probe failure');
  for (let i = 0; i < 3; i++) {
    await page.getByRole('button', {name: 'Mount', exact: true}).click();
    await page.getByRole('button', {name: 'Mount', exact: true}).click();
    await page.getByRole('button', {name: 'Edit', exact: true}).click();
    await expect(page.locator('#note')).toHaveText('changed');
  }
  expect(errors).toEqual([]);
});

test('rows reconnect on same-key replacement and unmount cancels observation and actions', async ({ page }) => {
  await page.goto('/');
  await expect(page.getByTestId('row')).toHaveCount(0);
  for (let i = 0; i < 3; i++) {
    await page.getByRole('button', { name: 'Populate', exact: true }).click();
    const row = page.getByTestId('row');
    await expect(row).toHaveText('Row child');
    await expect(row).toHaveAttribute('data-key', 'com.latenighthack.basekit.demo.BindingChildViewModel:child');
    await row.click();
    await expect(row).toHaveText('Selected child');
    await page.getByRole('button', { name: 'Populate', exact: true }).click();
    await expect(row).toHaveText('Row child');
    await row.click();
    await expect(row).toHaveText('Selected child');
    await page.getByRole('button', { name: 'Empty rows', exact: true }).click();
    await expect(row).toHaveCount(0);
  }
  await page.getByRole('button', { name: 'Static original', exact: true }).click();
  await expect(page.getByTestId('row')).toHaveText('static original');
  await page.getByRole('button', { name: 'Static replacement', exact: true }).click();
  await expect(page.getByTestId('row')).toHaveText('static replacement');
  await page.getByRole('button', { name: 'Wait', exact: true }).click();
  await expect.poll(() => page.evaluate(() => window.bindingProbe.startedActions)).toBe(1);
  await page.getByRole('button', { name: 'Mount', exact: true }).click();
  await expect.poll(() => page.evaluate(() => window.bindingProbe.cancelledActions)).toBe(1);
  await expect.poll(() => page.evaluate(() => window.bindingProbe.activeStateCollectors)).toBe(0);
});
