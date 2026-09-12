// Proves the frozen plan catches a real change instead of regenerating expectations.
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const { planProject, runProject } = require(process.env.GRAILS_QA_ENGINE || '../qa/engine');
const root = path.resolve(__dirname, '../.vscode-test/qa-fixture');
async function main() {
  require('./prepare-qa-fixture');
  const options = { ui: false, onOutput: text => process.stdout.write(text) };
  await planProject(root, options);
  const file = path.join(root, 'grails-app/domain/qaapp/QaRecord.groovy');
  const original = fs.readFileSync(file, 'utf8');
  const baseline = fs.readFileSync(path.join(root, '.grails-qa/plan.json'), 'utf8');
  try {
    fs.writeFileSync(file, original.replace('quantity min: 1', 'quantity min: 0'));
    const changed = await runProject(root, options);
    assert.equal(changed.status, 'failed');
    assert(changed.domain.cases.some(c => c.status === 'failed' && /quantity min below minimum/.test(c.name)));
    assert.equal(fs.readFileSync(path.join(root, '.grails-qa/plan.json'), 'utf8'), baseline);
  } finally { fs.writeFileSync(file, original); }
  const restored = await runProject(root, options);
  assert.equal(restored.domain.tests, 22);
  assert.equal(restored.domain.failures + restored.domain.errors + restored.domain.skipped, 0);
  console.log('Frozen-plan regression detected; restored source passes all 22 Domain/H2 cases.');
  const ui = await runProject(root, { ...options, domain: false, username: 'qa-tester', ui: { launch: true, database: 'h2' }, auth: { mode: 'bypass' } });
  assert.equal(ui.ui?.tests, 14);
  assert.equal(ui.ui?.failed, 0);
  assert(fs.readFileSync(path.join(ui.artifacts, 'application.log'), 'utf8').includes('QA fixture verified temporary H2 connection'));
  console.log('Actual JDBC connection verified temporary H2; bypass passed all 14 UI cases.');
}
main().catch(error => { console.error(error); process.exitCode = 1; });
