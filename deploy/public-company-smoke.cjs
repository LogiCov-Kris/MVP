const { chromium } = require('playwright');
const assert = require('node:assert/strict');

(async () => {
  const base = process.env.LOGICOV_TEST_URL;
  assert(base && process.env.LOGICOV_TEST_EMAIL && process.env.LOGICOV_TEST_PASSWORD, 'Set test URL and account credentials');
  const browser = await chromium.launch({headless: true, ...(process.env.CHROMIUM ? {executablePath: process.env.CHROMIUM} : {})});
  try {
    const context = await browser.newContext({baseURL: base, viewport: {width: 1440, height: 1100}});
    const page = await context.newPage();
    page.setDefaultTimeout(60000);
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await page.goto('/');
    await page.getByRole('button', {name: 'Use a password instead', exact: true}).click();
    await page.locator('input[name=email]').fill(process.env.LOGICOV_TEST_EMAIL);
    await page.locator('input[name=password]').fill(process.env.LOGICOV_TEST_PASSWORD);
    await page.locator('input[name=password]').press('Enter');
    await page.getByRole('button', {name: 'New Credit File', exact: true}).waitFor();
    const evidence = [];
    for (const ticker of ['TRGP', 'VST', 'LYB', 'FDUS', 'EXE']) {
      await page.getByRole('button', {name: 'New Credit File', exact: true}).click();
      await page.getByRole('button', {name: 'Public company', exact: true}).click();
      await page.getByPlaceholder('Ticker or SEC CIK', {exact: true}).fill(ticker);
      const lookup = page.waitForResponse(r => r.url().endsWith('/api/deals/public-lookup'));
      await page.getByRole('button', {name: 'Find company', exact: true}).click();
      const issuer = await (await lookup).json();
      assert.equal(issuer.ticker, ticker);
      await page.getByRole('button', {name: 'Import SEC financials', exact: true}).waitFor();
      const creation = page.waitForResponse(r => r.url().endsWith('/api/deals/public'));
      await page.getByRole('button', {name: 'Import SEC financials', exact: true}).click();
      let deal = await (await creation).json();
      assert(deal.id, 'Import must return its saved credit file');
      const deadline = Date.now() + 15 * 60000;
      while (deal.stage === 'Importing' && Date.now() < deadline) {
        await page.waitForTimeout(3000);
        const response = await context.request.get(`/api/deals/${deal.id}`);
        assert.equal(response.status(), 200);
        deal = await response.json();
      }
      assert.equal(deal.stage, 'Review', deal.failure || 'Import did not complete');
      assert.equal(deal.publicIssuer.cik, issuer.cik);
      assert.equal(deal.record, null, 'Intake must not invent a credit profile');
      assert(deal.grid.values.length > 0);
      assert(deal.documents.some(d => d.name === 'sec-companyfacts.json'));
      assert(deal.documents.some(d => d.name === 'sec-source-manifest.json'));
      evidence.push({ticker, id: deal.id, values: deal.grid.values.length, sources: deal.documents.length, issues: deal.issues});
      await page.reload();
      await page.getByRole('button', {name: 'New Credit File', exact: true}).waitFor();
    }
    assert.deepEqual(errors, []);
    console.log(JSON.stringify(evidence, null, 2));
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
