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
  await expect(page.locator('#failure')).toHaveText('none');
  await page.getByRole('button', {name: 'Enum', exact: true}).click();
  await expect(page.locator('#failure')).toHaveText('try-again');
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

test('a throwing state or list goes to the view model handler and keeps what is shown', async ({ page }) => {
  const errors = [];
  page.on('pageerror', e => errors.push(e.message));
  await page.goto('/');
  await page.getByRole('button', { name: 'Populate', exact: true }).click();
  await expect(page.getByTestId('row')).toHaveText('Row child');
  await page.getByRole('button', { name: 'Edit', exact: true }).click();
  await expect(page.locator('#note')).toHaveText('changed');
  await page.getByRole('button', { name: 'Fail state', exact: true }).click();
  await expect(page.locator('#note')).toHaveText('failed: gone');
  await page.getByRole('button', { name: 'Fail rows', exact: true }).click();
  await expect.poll(() => page.evaluate(() => window.bindingProbe.handledFailures)).toEqual(['state: gone', 'rows: rows gone']);
  await expect(page.getByTestId('row')).toHaveText('Row child');
  expect(errors).toEqual([]);
});

test('a hot state and list render their real values on the first render, with one collection under StrictMode', async ({ page }) => {
  await page.goto('/');
  await expect(page.locator('#render-hot')).toHaveText('hot:0:Row one,Row two');
  const renders = await page.evaluate(() => window.renders.hot);
  expect(renders.length).toBeGreaterThan(0);
  expect(renders.filter(r => r.greeting !== 'hot' || r.rows !== 2)).toEqual([]);
  // The hot probe is bound by two components, each mounted twice by StrictMode.
  const counts = await page.evaluate(() => [window.renderProbes.hot.startedStateCollectors, window.renderProbes.hot.startedRowCollectors]);
  expect(counts).toEqual([1, 1]);
});

test('a delayed source shows the initial state first, then its value', async ({ page }) => {
  await page.goto('/');
  await expect(page.locator('#render-delayed')).toHaveText('hot:0:Row one,Row two');
  const renders = await page.evaluate(() => window.renders.delayed);
  expect(renders[0]).toEqual({ greeting: 'initial', count: -1, rows: 0 });
  expect(renders[renders.length - 1]).toEqual({ greeting: 'hot', count: 0, rows: 2 });
  expect(await page.evaluate(() => window.renderProbes.delayed.startedStateCollectors)).toBe(1);
});

test('the binding object and its functions keep their identity until state changes', async ({ page }) => {
  await page.goto('/');
  await expect(page.locator('#render-hot')).toHaveText('hot:0:Row one,Row two');
  await page.evaluate(() => { window.identity.sameObject.length = 0; window.identity.sameIncrement.length = 0; window.identity.sameRows.length = 0; });
  await page.getByRole('button', { name: 'Rerender', exact: true }).click();
  await page.getByRole('button', { name: 'Rerender', exact: true }).click();
  let identity = await page.evaluate(() => window.identity);
  expect(identity.sameObject.length).toBeGreaterThan(0);
  expect(identity.sameObject.every(Boolean)).toBe(true);
  expect(identity.sameIncrement.every(Boolean)).toBe(true);
  await page.evaluate(() => { window.identity.sameObject.length = 0; window.identity.sameIncrement.length = 0; window.identity.sameRows.length = 0; });
  await page.getByRole('button', { name: 'Increment', exact: true }).click();
  await expect(page.locator('#render-hot')).toHaveText('hot:1:Row one,Row two');
  identity = await page.evaluate(() => window.identity);
  expect(identity.sameObject).toContain(false);
  expect(identity.sameIncrement.every(Boolean)).toBe(true);
  expect(identity.sameRows.every(Boolean)).toBe(true);
});

test('a render abandoned in a transition leaves no collection after the grace period', async ({ page }) => {
  await page.goto('/');
  await page.getByRole('button', { name: 'Abandon', exact: true }).click();
  await expect.poll(() => page.evaluate(() => window.renderProbes.abandoned.startedStateCollectors)).toBeGreaterThan(0);
  await expect(page.locator('#committed')).toHaveText('current');
  await expect.poll(() => page.evaluate(() => [window.renderProbes.abandoned.activeStateCollectors, window.renderProbes.abandoned.activeRowCollectors])).toEqual([0, 0]);
  await expect(page.locator('#committed')).toHaveText('current');
});
