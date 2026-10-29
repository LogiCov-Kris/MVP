const assert = require('node:assert/strict');
const { chromium } = require('playwright');

(async () => {
  const browser = await chromium.launch({
    executablePath: process.env.CHROMIUM_PATH || undefined,
    headless: true,
    args: ['--no-sandbox'],
  });
  try {
    const page = await browser.newPage({ viewport: { width: 1280, height: 800 } });
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await page.goto(process.env.LOGICOV_TEST_URL || 'http://127.0.0.1:18300/');
    await page.locator('input[name=email]').waitFor();
    if (process.env.LOGICOV_SCREENSHOT) await page.screenshot({ path: process.env.LOGICOV_SCREENSHOT + '.login.png' });
    await page.locator('input[name=email]').fill(process.env.LOGICOV_TEST_EMAIL);
    await page.locator('input[name=password]').fill('incorrect-password');
    await page.getByText('Sign in', { exact: true }).click();
    await page.getByText('Email or password is incorrect.', { exact: true }).waitFor();
    await page.locator('input[name=password]').fill(process.env.LOGICOV_TEST_PASSWORD);
    await page.locator('input[name=password]').press('Enter');
    await page.getByText('Deal workspace', { exact: true }).last().waitFor();
    await page.reload();
    await page.getByRole('button', { name: 'New financial review', exact: true }).click();
    await page.getByText('Import financial statements', { exact: true }).waitFor();
    if (process.env.LOGICOV_SCREENSHOT) await page.screenshot({ path: process.env.LOGICOV_SCREENSHOT + '.upload.png' });
    await page.getByRole('button', { name: 'Preview Golden Grid', exact: true }).click();
    await page.getByText('Choose a financial statement to continue.', { exact: true }).waitFor();
    await page.setViewportSize({ width: 390, height: 844 });
    await page.waitForTimeout(200);
    for (const field of await page.locator('input, select').all()) {
      const box = await field.boundingBox();
      assert(box && box.x >= 0 && box.x + box.width <= 390, 'Import control outside mobile viewport');
    }
    if (process.env.LOGICOV_SCREENSHOT) await page.screenshot({ path: process.env.LOGICOV_SCREENSHOT + '.mobile.png' });
    await page.setViewportSize({ width: 1280, height: 900 });
    await page.locator('input[name=borrower]').fill('Browser Example');
    await page.locator('input[name=years]').fill('2025');
    const chooser = page.waitForEvent('filechooser');
    await page.getByText('Choose statement', { exact: true }).click();
    await (await chooser).setFiles({ name: 'financials.csv', mimeType: 'text/csv',
      buffer: Buffer.from('Account,2025\nRevenue,1200\nCash,50\nTotalAssets,100\nTotalLiabilities,60\nTotalEquity,40\n') });
    await page.getByText('Preview Golden Grid', { exact: true }).click();
    await page.getByText('Cited values: 5  ·  Review issues: 0', { exact: true }).waitFor();
    await page.getByText('Download cited grid and issues', { exact: true }).waitFor();
    await page.getByText('Revenue', { exact: true }).filter({ visible: true }).first().click();
    await page.getByText('Revenue · FY2025', { exact: true }).waitFor();
    await page.getByText('USD millions', { exact: true }).filter({ visible: true }).first().click();
    await page.waitForTimeout(200);
    const cashRow = await page.getByText('Cash', { exact: true }).filter({ visible: true }).first().boundingBox();
    const revenueRow = await page.getByText('Revenue', { exact: true }).filter({ visible: true }).first().boundingBox();
    assert(cashRow && revenueRow && cashRow.y < revenueRow.y, 'Numeric sorting should put 50 before 1200');
    assert.equal(await page.getByText('Items requiring review', { exact: true }).count(), 0);
    const downloaded = page.waitForEvent('download');
    await page.getByText('Download cited grid and issues', { exact: true }).click();
    const download = await downloaded;
    assert.equal(download.suggestedFilename(), 'import.json');
    const fs = require('node:fs/promises');
    const result = JSON.parse(await fs.readFile(await download.path(), 'utf8'));
    assert.equal(result.grid.entity, 'Browser Example');
    assert.equal(result.grid.values.length, 5);
    assert.equal(result.issues.length, 0);
    if (process.env.LOGICOV_SCREENSHOT) await page.screenshot({ path: process.env.LOGICOV_SCREENSHOT });
    await page.getByText('Revenue', { exact: true }).filter({ visible: true }).first().scrollIntoViewIfNeeded();
    if (process.env.LOGICOV_SCREENSHOT) await page.screenshot({ path: process.env.LOGICOV_SCREENSHOT + '.grid.png' });
    await page.setViewportSize({ width: 390, height: 844 });
    await page.waitForTimeout(200);
    for (const field of await page.locator('input').all()) {
      const box = await field.boundingBox();
      assert(box && box.x >= 0 && box.x + box.width <= 390, 'Import field outside mobile viewport');
    }
    await page.setViewportSize({ width: 1280, height: 900 });
    await page.getByRole('button', { name: 'Back to import', exact: true }).click();
    await page.getByRole('region', { name: 'Statement upload' }).evaluate(element => {
      const transfer = new DataTransfer();
      transfer.items.add(new File(['Account,2025\nRevenue,1200\nUnknown row,5\n'], 'review-needed.csv', { type: 'text/csv' }));
      element.dispatchEvent(new DragEvent('drop', { bubbles: true, dataTransfer: transfer }));
    });
    await page.getByText('review-needed.csv', { exact: true }).waitFor();
    await page.getByLabel('Statement units', { exact: true }).selectOption({ label: 'USD thousands' });
    await page.getByRole('button', { name: 'Preview Golden Grid', exact: true }).click();
    await page.getByText('Cited values: 1  ·  Review issues: 1', { exact: true }).waitFor();
    await page.getByText('Items requiring review', { exact: true }).waitFor();
    await page.getByText('1.2', { exact: true }).filter({ visible: true }).first().waitFor();
    page.once('dialog', dialog => dialog.dismiss());
    await page.getByRole('button', { name: 'Back to import', exact: true }).click();
    await page.getByText('Items requiring review', { exact: true }).waitFor();
    page.once('dialog', dialog => dialog.accept());
    assert.equal(await page.evaluate(() => localStorage.getItem('token')), null);
    assert.equal(await page.evaluate(() => document.cookie.includes('logicov=')), false);
    await page.getByText('Sign out', { exact: true }).click();
    await page.locator('input[name=password]').waitFor();
    assert.equal(await page.locator('input[name=password]').inputValue(), '');
    await page.setViewportSize({ width: 390, height: 844 });
    await page.waitForTimeout(200);
    for (const field of await page.locator('input').all()) {
      const box = await field.boundingBox();
      assert(box && box.x >= 0 && box.x + box.width <= 390, 'Field outside mobile viewport');
    }
    assert.deepEqual(errors, []);
    console.log('Browser smoke passed: error handling, login, reload, logout, cookie isolation, upload, drag and drop, unit selection, validation, source detail, review issues, discard protection, download and mobile layout');
  } finally {
    await browser.close();
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
