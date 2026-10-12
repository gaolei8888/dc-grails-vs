const assert = require('assert');
const fs = require('fs');
const path = require('path');
const { runProject } = require(process.env.GRAILS_QA_ENGINE || '../qa/engine');
async function main() {
  process.env.QA_FIXTURE_REST = '1';
  require('./prepare-qa-fixture');
  const root = path.resolve(__dirname, '../.vscode-test/qa-fixture');
  // This fixture's own test database is H2, with a distinct environment URL.
  // It supplies its own JDBC driver, just like the application under test must.
  fs.appendFileSync(path.join(root, 'build.gradle'), '\ndependencies { runtimeOnly "com.h2database:h2" }\n');
  for (const mode of ['credentials', 'bypass']) {
    const report = await runProject(root, { domain: false, ui: { launch: true },
      auth: { mode }, username: 'qa-tester', password: mode === 'credentials' ? 'qa-password' : undefined, onOutput: text => process.stdout.write(text) });
    assert.equal(report.error, undefined);
    assert.equal(report.uiDatabase, 'project');
    assert.equal(report.ui?.tests, 14); assert.equal(report.ui?.failed, 0);
    assert(report.ui.cases.every(c => c.useCase));
    const log = fs.readFileSync(path.join(report.artifacts, 'application.log'), 'utf8');
    assert(log.includes('QA fixture verified project test database'));
    assert(log.includes('QA fixture verified REST tokenStorageService dependency'));
    assert(!log.includes('\x1b'), 'output logs must not contain terminal escape codes');
    assert(!fs.readFileSync(path.join(report.artifacts, 'qa-overrides.groovy'), 'utf8').includes('dataSource'));
    console.log('Verified project test database without datasource overrides: ' + mode);
  }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
