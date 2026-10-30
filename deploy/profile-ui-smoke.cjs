const assert = require('node:assert/strict');
const { chromium } = require('playwright');

(async () => {
  const browser = await chromium.launch({ executablePath: process.env.CHROMIUM_PATH || undefined, headless: true, args: ['--no-sandbox'] });
  try {
    const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
    page.setDefaultTimeout(30000);
    const errors = [];
    page.on('pageerror', e => errors.push(e.message));
    await page.goto(process.env.LOGICOV_TEST_URL || 'http://127.0.0.1:18301');
    await page.locator('input[name=email]').fill(process.env.LOGICOV_TEST_EMAIL);
    await page.locator('input[name=password]').fill(process.env.LOGICOV_TEST_PASSWORD);
    await page.locator('input[name=password]').press('Enter');
    await page.getByRole('button', { name: 'New financial review', exact: true }).click();
    await page.locator('input[name=borrower]').fill('Private profile validation ' + Date.now());
    await page.locator('input[name=years]').fill('2025,2024,2023');
    const chooser = page.waitForEvent('filechooser');
    await page.getByRole('button', { name: 'Choose statement', exact: true }).click();
    await (await chooser).setFiles({ name: 'private-financials.csv', mimeType: 'text/csv', buffer: Buffer.from(
      'Account,2025,2024,2023\nRevenue,200,180,160\nOperatingCashFlow,22,20,18\nCashCapex,5,4,3\nInterestExpense,3,3,3\nDebtPrincipal,40,35,30\nCurrentDebt,5,5,5\nLongTermDebtCarrying,35,30,25\nCash,10,8,6\nTotalEquity,60,55,50\nDividendsPaid,2,2,2\n') });
    await page.getByRole('button', { name: 'Preview Golden Grid', exact: true }).click();
    await page.getByRole('button', { name: 'Save to workspace', exact: true }).click();
    await page.getByRole('button', { name: 'Judgments', exact: true }).click();
    const source = page.waitForEvent('filechooser');
    await page.getByRole('button', { name: 'Attach supporting document', exact: true }).click();
    await (await source).setFiles({ name: 'facility-terms.txt', mimeType: 'text/plain', buffer: Buffer.from(
      'Synthetic review source: senior revolver, $20 million commitment, $10 million availability; maximum net leverage 4.0x. EBITDA: 2023 $20 million, 2024 $25 million, 2025 $30 million. Independent ownership; largest customer share 40%; no commodity-price-linked earnings.') });
    await page.getByLabel('Credit profile source', { exact: true }).selectOption({ label: 'facility-terms.txt' });
    const fields = {
      'Source pages or section': 'Facility terms and earnings summary', 'Borrower reference': 'PRIVATE', 'Latest fiscal year': '2025',
      'Three annual EBITDA amounts, oldest first (USD millions)': '20,25,30', 'Facility type': 'Senior revolver',
      'Maturity date or stated target': 'October 26, 2029', 'Administrative agent or direct lender': 'Example lender',
      'Facility commitment (USD millions)': '20', 'Undrawn availability (USD millions)': '10',
      'Covenant threshold (multiple or fraction)': '4', 'Largest concentration (%)': '40',
      'Concentration basis': 'Largest customer revenue share', 'Commodity-price-linked share (%)': '0',
      'Ownership support rationale': 'Independent ownership', 'Profile review explanation': 'Analyst verified the synthetic supporting terms',
    };
    for (const [name, value] of Object.entries(fields)) await page.getByRole('textbox', { name, exact: true }).fill(value);
    await page.getByRole('button', { name: 'Build credit analysis', exact: true }).click();
    await page.getByText('Credit analysis', { exact: true }).waitFor();
    await page.getByText(/Net leverage: 1\.00x/).waitFor();
    await page.getByRole('button', { name: 'Memo', exact: true }).click();
    await require('./review-checks.cjs')(page);
    await page.getByRole('textbox', { name: 'Approval explanation or policy exception', exact: true }).fill('Synthetic profile verification without institution policy');
    await page.getByRole('button', { name: 'Approve reviewed inputs', exact: true }).click();
    await page.getByRole('button', { name: 'Generate memo', exact: true }).click();
    await page.getByRole('button', { name: /^Download memo PDF/ }).waitFor({ timeout: 180000 });
    assert.deepEqual(errors, []);
    console.log('Private profile UI passed: multi-year statement, supporting source, sourced credit terms, deterministic ratios, approval and committee PDF');
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
