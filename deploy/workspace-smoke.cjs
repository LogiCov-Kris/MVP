const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const { chromium } = require('playwright');

(async () => {
  const browser = await chromium.launch({ executablePath: process.env.CHROMIUM_PATH || undefined, headless: true, args: ['--no-sandbox'] });
  try {
    const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
    const errors = [];
    page.on('pageerror', e => errors.push(e.message));
    await page.goto(process.env.LOGICOV_TEST_URL || 'http://127.0.0.1:18301/');
    await page.locator('input[name=email]').fill(process.env.LOGICOV_TEST_EMAIL);
    await page.locator('input[name=password]').fill(process.env.LOGICOV_TEST_PASSWORD);
    await page.locator('input[name=password]').press('Enter');
    await page.getByRole('button', { name: 'New financial review', exact: true }).click();
    await page.locator('input[name=borrower]').fill('Saved review ' + Date.now());
    const chooser = page.waitForEvent('filechooser');
    await page.getByRole('button', { name: 'Choose statement', exact: true }).click();
    await (await chooser).setFiles({ name: 'retained.csv', mimeType: 'text/csv', buffer: Buffer.from('Revenue,120\nCash,10\n') });
    await page.getByRole('button', { name: 'Preview Golden Grid', exact: true }).click();
    const savedResponse = page.waitForResponse(r => r.url().endsWith('/api/deals') && r.request().method() === 'POST');
    await page.getByRole('button', { name: 'Save to workspace', exact: true }).click();
    const saved = await (await savedResponse).json();
    await page.getByText('Inspect a value', { exact: true }).waitFor();
    if (process.env.LOGICOV_OTHER_EMAIL) {
      const other = await browser.newContext();
      const base = process.env.LOGICOV_TEST_URL || 'http://127.0.0.1:18301';
      const login = await other.request.post(base + '/api/auth/login', { headers: { 'X-LogiCov-Request': '1' }, data: {
        email: process.env.LOGICOV_OTHER_EMAIL, password: process.env.LOGICOV_TEST_PASSWORD,
      }});
      assert.equal(login.status(), 200);
      assert.equal((await other.request.get(base + '/api/deals/' + saved.id)).status(), 404);
      assert.equal((await other.request.get(base + '/api/deals/' + saved.id + '/files/' + saved.documents[0].id)).status(), 404);
      assert.equal((await other.request.post(base + '/api/deals/' + saved.id + '/approve', {
        headers: { 'X-LogiCov-Request': '1' }, data: { revision: saved.revision, reason: 'Cross-tenant attempt' },
      })).status(), 404);
      await other.close();
    }
    await page.getByText('Revenue', { exact: true }).filter({ visible: true }).first().click();
    await page.getByRole('textbox', { name: 'Corrected amount (USD millions)', exact: true }).fill('125');
    await page.getByRole('textbox', { name: 'Reason for correction', exact: true }).fill('Analyst reviewed the source and corrected transcription');
    await page.getByRole('button', { name: 'Save correction', exact: true }).click();
    await page.getByText('125', { exact: true }).filter({ visible: true }).first().waitFor();
    await page.getByRole('button', { name: 'Memo', exact: true }).click();
    assert.equal(await page.getByRole('button', { name: 'Generate memo', exact: true }).getAttribute('aria-disabled'), 'true');
    await page.getByRole('button', { name: 'Approve reviewed inputs', exact: true }).click();
    await page.getByText('requirement failed: A review reason is required (up to 2000 characters)', { exact: true }).waitFor();
    await page.getByRole('textbox', { name: 'Approval explanation or policy exception', exact: true }).fill('Financial review approved; institution policy not yet supplied');
    await page.getByRole('button', { name: 'Approve reviewed inputs', exact: true }).click();
    await page.getByRole('button', { name: 'Generate memo', exact: true }).click();
    const pdf = page.getByRole('button', { name: /^Download memo PDF/ });
    await pdf.waitFor({ timeout: 180000 });
    const downloaded = page.waitForEvent('download');
    await pdf.click();
    const download = await downloaded;
    const bytes = await fs.readFile(await download.path());
    assert.equal(bytes.subarray(0, 5).toString(), '%PDF-');
    const zipped = page.waitForEvent('download');
    await page.getByRole('button', { name: /^Download review bundle/ }).click();
    const zip = await zipped;
    assert((await fs.readFile(await zip.path())).length > bytes.length);
    if (process.env.LOGICOV_SCREENSHOT) await page.screenshot({ path: process.env.LOGICOV_SCREENSHOT, fullPage: true });
    await page.getByRole('button', { name: 'History', exact: true }).click();
    await page.getByText('Grid corrected', { exact: true }).waitFor();
    await page.getByText('Approved', { exact: true }).waitFor();
    await page.getByText('Memo generated', { exact: true }).waitFor();
    await page.getByRole('button', { name: 'Financials', exact: true }).click();
    const source = page.waitForEvent('download');
    await page.getByRole('button', { name: 'Download retained.csv', exact: true }).click();
    assert.equal(await fs.readFile(await (await source).path(), 'utf8'), 'Revenue,120\nCash,10\n');
    await page.reload();
    await page.getByRole('button', { name: 'New financial review', exact: true }).waitFor();
    assert.deepEqual(errors, []);
    console.log('Workspace browser passed: save, correct, approve, generate PDF, portable bundle, audit, original source and reload');
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
