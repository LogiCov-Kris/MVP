const assert = require('node:assert/strict');
const { chromium } = require('playwright');

(async () => {
  const browser = await chromium.launch({ executablePath: process.env.CHROMIUM_PATH || undefined, headless: true, args: ['--no-sandbox'] });
  try {
    const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
    const errors = [];
    page.setDefaultTimeout(20000);
    page.on('dialog', dialog => { console.log('Dialog: ' + dialog.message()); dialog.dismiss(); });
    page.on('pageerror', e => errors.push(e.message));
    const base = process.env.LOGICOV_TEST_URL || 'http://127.0.0.1:18301';
    await page.goto(base);
    await page.locator('input[name=email]').fill(process.env.LOGICOV_TEST_EMAIL);
    await page.locator('input[name=password]').fill(process.env.LOGICOV_TEST_PASSWORD);
    await page.locator('input[name=password]').press('Enter');
    await page.getByRole('button', { name: 'New financial review', exact: true }).waitFor();
    console.log('Signed in');
    const deals = await (await page.request.get(base + '/api/deals')).json();
    const reference = deals.find(d => d.title.includes('Occidental'));
    assert(reference, 'Onboard the OXY source pack first');
    const original = await (await page.request.get(base + '/api/deals/' + reference.id)).json();
    const title = 'Credit workflow validation ' + Date.now();
    const response = await page.request.post(base + '/api/deals', { headers: { 'X-LogiCov-Request': '1' }, data: { title, upload: null, record: original.record } });
    assert.equal(response.status(), 200);
    const created = await response.json();
    async function saveAction(label, route) {
      const changed = page.waitForResponse(r => r.url().endsWith('/api/deals/' + created.id + '/' + route) && r.request().method() === 'POST');
      await page.getByRole('button', { name: label, exact: true }).click();
      const response = await changed;
      assert.equal(response.status(), 200, await response.text());
      const updated = await response.json();
      await page.getByText(new RegExp('Revision ' + updated.revision + ' ·')).first().waitFor();
    }
    await page.reload();
    await page.getByRole('textbox', { name: 'Search borrowers', exact: true }).fill(title);
    await page.getByRole('button', { name: 'Open deal', exact: true }).click();
    console.log('Open judgments');
    await page.getByRole('button', { name: 'Judgments', exact: true }).click();
    await page.getByRole('textbox', { name: 'Ownership support rationale', exact: true }).fill('Reviewed ownership support against retained sources');
    await page.getByRole('textbox', { name: 'Reason for judgment changes', exact: true }).fill('Confirmed the source basis');
    await saveAction('Save judgments', 'judgments');
    await page.getByRole('textbox', { name: 'Additional EBITDA reduction (%)', exact: true }).fill('25');
    await saveAction('Add sensitivity', 'judgments');
    console.log('Edit recommendation');
    await page.getByLabel('Credit recommendation', { exact: true }).selectOption({ label: 'Recommended for approval with conditions' });
    await page.getByRole('textbox', { name: 'Recommendation rationale', exact: true }).fill('Local workflow test; no client credit decision');
    await saveAction('Save recommendation', 'recommendation');
    console.log('Edit policy');
    await page.getByRole('button', { name: 'Policy', exact: true }).click();
    for (const [name, value] of Object.entries({ 'Policy name': 'Validation policy', 'Policy version': '1', 'Policy source or reference': 'Synthetic test policy', 'Rule description': 'Maximum leverage', 'Threshold': '0.1', 'Reason for policy change': 'Verify a breach is visible' })) {
      await page.getByRole('textbox', { name, exact: true }).fill(value);
    }
    await saveAction('Save policy rule', 'policy');
    await page.getByText(/Maximum leverage:.*Breach/).waitFor();
    await page.getByLabel('Rule to edit', { exact: true }).selectOption({ label: 'Maximum leverage' });
    await page.getByRole('textbox', { name: 'Threshold', exact: true }).fill('99');
    await page.getByRole('textbox', { name: 'Reason for policy change', exact: true }).fill('Verify editing the rule and passing threshold');
    await saveAction('Save policy rule', 'policy');
    await page.getByText(/Maximum leverage:.*Pass/).waitFor();
    console.log('Edit narrative');
    await page.getByRole('button', { name: 'Memo', exact: true }).click();
    await page.getByLabel('Memo section', { exact: true }).selectOption({ label: 'Key Risk Factors and Mitigants' });
    await page.getByLabel('Section narrative', { exact: true }).fill('Risk: First risk. Mitigant: First response.\n\nRisk: Second risk. Mitigant: Second response.');
    await page.getByRole('button', { name: 'Move down risk 1', exact: true }).click();
    assert((await page.getByLabel('Section narrative', { exact: true }).inputValue()).startsWith('Risk: Second'));
    await page.getByRole('textbox', { name: 'Reason for narrative change', exact: true }).fill('Reordered risk priority');
    await saveAction('Save narrative', 'narratives');
    console.log('Verify history');
    await page.getByRole('button', { name: 'History', exact: true }).click();
    await page.getByText('Credit recommendation changed', { exact: true }).waitFor();
    await page.getByText('Narrative edited', { exact: true }).waitFor();
    const updated = await (await page.request.get(base + '/api/deals/' + created.id)).json();
    assert(updated.narratives['Key Risk Factors and Mitigants'].startsWith('Risk: Second'));
    assert.equal(updated.policy.rules.length, 1);
    assert.equal(updated.policy.rules[0].threshold, 99);
    assert.equal(updated.recommendation, 'CreditRecommendation.Conditional');
    assert(updated.record.inputs.stressScenarios.some(s => s.ebitdaChange === -0.25));
    assert.equal(updated.approval, null);
    await page.setViewportSize({ width: 390, height: 844 });
    await page.getByLabel('Review section', { exact: true }).selectOption({ label: 'Judgments' });
    for (const field of await page.locator('input, select').all()) {
      const box = await field.boundingBox();
      assert(box && box.x >= 0 && box.x + box.width <= 390, 'Saved-deal control outside mobile viewport');
    }
    if (process.env.LOGICOV_SCREENSHOT) await page.screenshot({ path: process.env.LOGICOV_SCREENSHOT });
    assert.deepEqual(errors, []);
    console.log('Reference UI passed: judgments, sensitivity, recommendation, policy breach/edit/pass, risk ordering, saved audit and mobile controls');
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
