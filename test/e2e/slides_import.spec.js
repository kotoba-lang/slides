const { test, expect } = require('@playwright/test');
const fs = require('fs');
const path = require('path');

test('imports and re-exports pptx in browser', async ({ page }) => {
  const downloads = '/tmp/kotoba-slides-downloads';
  fs.rmSync(downloads, { recursive: true, force: true });
  fs.mkdirSync(downloads, { recursive: true });

  const errors = [];
  page.on('console', msg => {
    if (msg.type() === 'error') errors.push(msg.text());
  });
  page.on('pageerror', err => errors.push(err.message));

  await page.goto('http://localhost:4173/', { waitUntil: 'networkidle' });
  await expect(page.locator('text=kotoba-lang/slides')).toBeVisible();
  await expect(page.locator('#pptx-file')).toHaveCount(1);

  await page.setInputFiles('#pptx-file', path.resolve('docs/sample.pptx'));
  await expect(page.locator('#status')).toContainText(/slides/);
  await page.click('#mode-edn');
  await expect(page.locator('#deck-edn')).toHaveValue(/:slides\/import/);
  await expect(page.locator('#deck-edn')).toHaveValue(/:slides\/format :pptx/);

  const downloadPromise = page.waitForEvent('download');
  await page.click('#download-pptx');
  const download = await downloadPromise;
  const out = path.join(downloads, 'browser-import-export.pptx');
  await download.saveAs(out);
  expect(fs.statSync(out).size).toBeGreaterThan(1000);
  expect(errors).toEqual([]);
});
