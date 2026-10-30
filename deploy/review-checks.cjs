const assert = require('node:assert/strict');

module.exports = async function clearSyntheticChecks(page) {
  for (const label of ['Sanctions screening', 'Going-concern opinion', 'Financial statement currency', 'Borrower and holding-company identity', 'Dividend use of proceeds']) {
    await page.getByLabel(label + ' status', { exact: true }).selectOption({ label: 'Clear' });
    await page.getByRole('textbox', { name: label + ' evidence', exact: true }).fill('Synthetic verification fixture; no live screening or credit decision');
  }
  const saved = page.waitForResponse(r => r.url().endsWith('/checks') && r.request().method() === 'POST');
  await page.getByRole('button', { name: 'Save required checks', exact: true }).click();
  const response = await saved;
  assert.equal(response.status(), 200, await response.text());
  const deal = await response.json();
  await page.getByText(new RegExp('Revision ' + deal.revision + ' ·')).first().waitFor();
};
