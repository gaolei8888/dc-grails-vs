const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vscode = require('vscode');
const { chromium } = require('playwright-core');
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
async function until(predicate, name, timeout = 180000) {
  const end = Date.now() + timeout;
  while (Date.now() < end) { const value = await predicate(); if (value) return value; await delay(200); }
  throw new Error('Timed out: ' + name);
}
suite('Real Grails QA panel + Spring Security + browser forms', () => {
  let browser, page, frame;
  const artifacts = process.env.GRAILS_TEST_ARTIFACTS;
  const root = vscode.workspace.workspaceFolders[0].uri.fsPath;
  const reportFile = path.join(root, '.grails-qa/report.json');
  async function run(name) {
    const previous = fs.existsSync(reportFile) ? fs.readFileSync(reportFile, 'utf8') : '';
    await frame.locator('#run').click();
    assert.equal(await frame.locator('#password').inputValue(), '');
    const report = await until(() => {
      if (!fs.existsSync(reportFile)) return;
      const text = fs.readFileSync(reportFile, 'utf8');
      if (text === previous) return;
      try { return JSON.parse(text); } catch { return; }
    }, name + ' report');
    await until(() => frame.locator('#run').isEnabled(), 'panel ready');
    fs.writeFileSync(path.join(artifacts, name + '.json'), JSON.stringify(report, null, 2));
    await frame.locator('#result-title').scrollIntoViewIfNeeded();
    await page.screenshot({ path: path.join(artifacts, name + '.png') });
    return report;
  }
  function passed(report) {
    assert.equal(report.error, undefined);
    assert.equal(report.domain.tests, 22);
    assert.equal(report.domain.failures + report.domain.errors + report.domain.skipped, 0);
    assert.equal(report.ui.tests, 14);
    assert.equal(report.ui.failed + report.ui.skipped, 0);
    assert.equal(report.status, 'partial', 'unconfirmed views must remain coverage gaps');
  }
  suiteSetup(async () => {
    await vscode.extensions.getExtension('gaolei8888.grails-gradle-extension').activate();
    browser = await chromium.connectOverCDP('http://127.0.0.1:' + process.env.GRAILS_TEST_CDP_PORT);
    page = await until(() => browser.contexts()[0].pages().find(p => p.url().includes('workbench')), 'workbench');
    await vscode.commands.executeCommand('grails.qa');
    frame = await until(async () => {
      for (const candidate of page.frames()) if (await candidate.locator('#run').count()) return candidate;
    }, 'QA webview');
    await until(async () => (await frame.locator('#project').innerText()).includes(process.env.QA_EDITOR_MULTI ? '3 Domain' : '2 Domain'), 'project inventory');
  });
  suiteTeardown(async () => { if (browser) await browser.close(); });
  test('AI draft controls apply reviewed cases and Run QA executes them', async () => {
    const planPath = path.join(root, '.grails-qa/plan.json');
    const original = fs.readFileSync(planPath, 'utf8');
    const plan = JSON.parse(original);
    try {
      assert(await frame.locator('#show-browser').isChecked());
      await frame.locator('#ai-plan').click();
      await until(async () => (await frame.locator('#notice').innerText()).includes('No VS Code language model'), 'no provider guidance');
      assert.equal(fs.readFileSync(planPath, 'utf8'), original);
      fs.writeFileSync(path.join(root, '.grails-qa/ai-draft.json'), JSON.stringify({ version: 1, model: 'editor-fixture', gaps: [], cases: [
        { name: 'Reviewed browser case', useCase: 'Open the fixture form and verify its content is visible.', path: plan.pages[0].path,
          steps: [{ action: 'visible', selector: plan.pages[0].form || 'body' }] }
      ] }));
      await frame.locator('#apply-ai').click();
      await until(async () => (await frame.locator('#notice').innerText()).includes('AI browser cases saved'), 'draft applied');
      assert.deepEqual(JSON.parse(fs.readFileSync(planPath)).domains, plan.domains);
      await frame.locator('#target').selectOption('isolated');
      await frame.locator('#auth').selectOption('credentials');
      await frame.locator('#username').fill('qa-tester');
      await frame.locator('#password').fill('qa-password');
      await frame.locator('#remember').uncheck();
      const report = await run('ai-draft');
      assert.equal(report.error, undefined);
      assert.equal(report.domain.tests, 22);
      assert.equal(report.ui.tests, 15);
      assert.equal(report.ui.failed, 0);
      assert.equal(report.ui.cases.at(-1).name, 'Reviewed browser case');
      assert(report.ui.cases.at(-1).screenshot);
    } finally {
      fs.writeFileSync(planPath, original);
      await frame.locator('#target').selectOption('project');
    }
  });
  test('Tenant choices scope the user list and Cancel releases the process', async function () {
    if (!process.env.QA_EDITOR_MULTI) return this.skip();
    await frame.locator('#auth').selectOption('bypass');
    await frame.locator('#load-identities').click();
    await until(() => frame.locator('#tenant option[value="north"]').count(), 'tenant discovery');
    assert.equal(await frame.locator('#tenant').inputValue(), '');
    assert.equal(await frame.locator('#test-user option').count(), 1);
    await frame.locator('#tenant').selectOption('north');
    await until(() => frame.locator('#test-user option[value="north-only"]').count(), 'north users');
    assert.equal(await frame.locator('#test-user option[value="south-only"]').count(), 0);
    await frame.locator('#user-search').fill('no-such-user');
    await frame.locator('#search-identities').click();
    await until(async () => (await frame.locator('#identity-status').innerText()).includes('No usernames match'), 'filtered empty result');
    await frame.locator('#clear-user-search').click();
    await until(() => frame.locator('#test-user option[value="north-only"]').count(), 'clear filter restores users');
    await frame.locator('#test-user').selectOption('qa-tester');
    await frame.locator('#user-search').fill('stale-filter');
    await frame.locator('#tenant').selectOption('south');
    await until(() => frame.locator('#test-user option[value="south-only"]').count(), 'south users');
    assert.equal(await frame.locator('#test-user').inputValue(), '');
    assert.equal(await frame.locator('#user-search').inputValue(), '');
    assert.equal(await frame.locator('#test-user option[value="north-only"]').count(), 0);
    await frame.locator('#tenant').selectOption('empty');
    await until(async () => (await frame.locator('#notice').innerText()).includes('No available users'), 'empty tenant');
    assert.equal(await frame.locator('#test-user option').count(), 1);
    await page.screenshot({ path: path.join(artifacts, 'tenant-picker.png') });
    await frame.locator('#cancel').click();
    await until(() => !fs.existsSync(path.join(root, '.grails-qa/active.lock')), 'identity lock released');
  });
  test('Buttons detect Spring Security and restrict bypass to local targets', async () => {
    assert.equal(await frame.locator('#target').inputValue(), 'project');
    assert.equal(await frame.locator('#auth').inputValue(), 'credentials');
    assert.equal(await frame.locator('#bypass').isDisabled(), false);
    await frame.locator('#auth').selectOption('bypass');
    await frame.locator('#target').selectOption('existing');
    assert.equal(await frame.locator('#bypass').isDisabled(), true);
    assert.equal(await frame.locator('#auth').inputValue(), 'credentials');
    await frame.locator('#target').selectOption('isolated');
    await page.screenshot({ path: path.join(artifacts, 'qa-controls.png') });
  });
  test('Run QA logs in, tests H2 and submits all real forms', async () => {
    await frame.locator('#username').fill('qa-tester');
    await frame.locator('#password').fill('qa-password');
    await frame.locator('#remember').check();
    passed(await run('credentials'));
  });
  test('Blank credentials reuse VS Code SecretStorage for this target', async () => {
    await frame.locator('#username').fill('');
    passed(await run('saved-credentials'));
  });
  test('Skip authentication exercises forms on the temporary local instance', async () => {
    await frame.locator('#auth').selectOption('bypass');
    await frame.locator('#load-identities').click();
    await until(async () => await frame.locator('#test-user option[value="qa-tester"]').count(), 'test identity discovery');
    await frame.locator('#test-user').selectOption('qa-tester');
    assert.equal(await frame.locator('#tenant-row').isVisible(), false);
    assert.equal(await frame.locator('#password').isVisible(), false);
    const report = await run('bypass');
    passed(report);
    assert(report.gaps.some(g => /HTTP security filters were bypassed/.test(g.reason)));
  });
  test('Wrong password fails authentication and is never written to reports', async () => {
    await frame.locator('#auth').selectOption('credentials');
    await frame.locator('#username').fill('qa-tester');
    await frame.locator('#password').fill('deliberately-wrong-password');
    await frame.locator('#remember').uncheck();
    const report = await run('wrong-password');
    assert.equal(report.status, 'failed');
    assert.equal(report.ui.failed, 1);
    assert(!JSON.stringify(report).includes('deliberately-wrong-password'));
    await frame.locator('#forget').click();
  });
});
