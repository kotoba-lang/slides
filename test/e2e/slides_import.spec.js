const { test, expect } = require('@playwright/test');
const childProcess = require('child_process');
const fs = require('fs');
const path = require('path');

function unzipText(pptxPath, entryPath) {
  return childProcess.execFileSync('unzip', ['-p', pptxPath, entryPath], {
    encoding: 'utf8'
  });
}

function escapeRegex(text) {
  return text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

async function expectNoBrowserErrors(page, errors) {
  await expect(page.locator('#error')).toHaveText('');
  expect(errors).toEqual([]);
}

test.beforeEach(async ({ page }) => {
  await page.goto('/', { waitUntil: 'networkidle' });
  await expect(page.locator('text=kotoba-lang/slides')).toBeVisible();
  await expect(page.locator('#pptx-file')).toHaveCount(1);
});

test('imports pptx, edits a shape, and writes edited pptx xml', async ({ page }) => {
  const downloads = '/tmp/kotoba-slides-downloads';
  fs.rmSync(downloads, { recursive: true, force: true });
  fs.mkdirSync(downloads, { recursive: true });

  const errors = [];
  page.on('console', msg => {
    if (msg.type() === 'error') errors.push(msg.text());
  });
  page.on('pageerror', err => errors.push(err.message));

  await page.setInputFiles('#pptx-file', path.resolve('docs/sample.pptx'));
  await expect(page.locator('#status')).toContainText(/slides/);
  await expect(page.locator('[data-shape="0"]').first()).toBeVisible();
  await page.locator('[data-shape="0"]').first().click();
  await expect(page.locator('#shape-text')).toBeVisible();
  await page.locator('#shape-text').fill('Browser Edited PPTX Title');
  await expect(page.locator('[data-shape="0"]').first()).toContainText('Browser Edited PPTX Title');

  await page.click('#mode-edn');
  await expect(page.locator('#deck-edn')).toHaveValue(/Browser Edited PPTX Title/);
  await expect(page.locator('#deck-edn')).toHaveValue(/:slides\/format :pptx/);
  await expect(page.locator('#deck-edn')).toHaveValue(/:slides\/text-extraction :drawingml-runs/);

  const downloadPromise = page.waitForEvent('download');
  await page.click('#download-pptx');
  const download = await downloadPromise;
  const out = path.join(downloads, 'browser-import-export.pptx');
  await download.saveAs(out);
  expect(fs.statSync(out).size).toBeGreaterThan(1000);
  expect(unzipText(out, 'ppt/slides/slide1.xml')).toMatch(/Browser Edited PPTX Title/);
  expect(unzipText(out, 'ppt/theme/theme1.xml')).toMatch(/a:theme/);
  await expectNoBrowserErrors(page, errors);
});

test('applies EDN components and exports editable pptx text', async ({ page }) => {
  const downloads = '/tmp/kotoba-slides-edn-downloads';
  fs.rmSync(downloads, { recursive: true, force: true });
  fs.mkdirSync(downloads, { recursive: true });

  const errors = [];
  page.on('console', msg => {
    if (msg.type() === 'error') errors.push(msg.text());
  });
  page.on('pageerror', err => errors.push(err.message));

  const deckEdn = `{:slides/id "component-deck"
 :slides/title "Component Deck"
 :slides/width 10
 :slides/height 5.625
 :slides/design {:slides/components {:hero {:slides/shape :text
                                             :slides/text-style :title
                                             :slides/x 1.0
                                             :slides/y 0.8
                                             :slides/w 8.0
                                             :slides/h 1.0}}
                 :slides/text-styles {:title {:slides/font-size 42
                                               :slides/color "123456"
                                               :slides/bold true}}}
 :slides/slides [{:slides/id "slide-1"
                  :slides/title "EDN Component"
                  :slides/shapes [{:slides/id "hero"
                                   :slides/component :hero
                                   :slides/text "EDN Component Title"}]}]}`;

  await page.click('#mode-edn');
  await page.locator('#deck-edn').fill(deckEdn);
  await page.click('#apply-edn');
  await expect(page.locator('[data-shape="0"]')).toContainText('EDN Component Title');
  await page.click('#mode-edn');
  await expect(page.locator('#deck-edn')).toHaveValue(/:slides\/component :hero/);

  const downloadPromise = page.waitForEvent('download');
  await page.click('#download-pptx');
  const download = await downloadPromise;
  const out = path.join(downloads, 'edn-component-export.pptx');
  await download.saveAs(out);

  const slideXml = unzipText(out, 'ppt/slides/slide1.xml');
  expect(slideXml).toMatch(/EDN Component Title/);
  expect(slideXml).toMatch(/sz="4200"/);
  expect(slideXml).toMatch(new RegExp(escapeRegex('123456')));
  await expectNoBrowserErrors(page, errors);
});
