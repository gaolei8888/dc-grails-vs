const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { decimal, propertyCases, createPlan } = require('../../qa/rules');
const { sameOrigin, uiCases } = require('../../qa/ui');
const { redactor, startGradle } = require('../../qa/process');
const { scanProject, readConfig } = require('../../qa/project');
const { credentialKey } = require('../../lib/qa-panel');
const { junit, runProject } = require(process.env.GRAILS_QA_ENGINE || '../../qa/engine');
const { startupFailure } = require('../../qa/startup');
function fixture(t) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'grails-qa-test-'));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  for (const dir of ['grails-app/domain/demo', 'grails-app/views/book', '.grails-qa']) fs.mkdirSync(path.join(root, dir), { recursive: true });
  fs.writeFileSync(path.join(root, 'build.gradle'), '');
  return root;
}
test('QA startup diagnostics distinguish fatal failures from harmless service warnings', () => {
  assert.equal(startupFailure('Connection refused\nStarted Application in 2 seconds'), null);
  assert.match(startupFailure('Application run failed\nliquibase.exception.DatabaseException\nCaused by: org.h2.jdbc.JdbcSQLSyntaxErrorException: Column B not found'), /migration SQL/);
  assert.match(startupFailure('APPLICATION FAILED TO START\nCaused by: example.StartupException: unavailable'), /StartupException: unavailable/);
  assert.match(startupFailure("APPLICATION FAILED TO START\nA component required a bean named 'tokenStorageService' that could not be found."), /missing Spring bean tokenStorageService/);
});
test('QA removes ANSI colors and hyperlinks while preserving errors and redacting secrets', () => {
  const safe = redactor(['secret']);
  assert.equal(safe('\x1b[31mERROR\x1b[0m migration failed: secret'), 'ERROR migration failed: [redacted]');
  assert.equal(safe('\x1b]8;;https://example.org\x07report\x1b]8;;\x07'), 'report');
});
test('QA writes a failed report when startup fails but the process remains alive', { skip: process.platform !== 'win32' }, async t => {
  const root = fixture(t);
  fs.writeFileSync(path.join(root, 'gradlew.bat'), '@echo off\r\necho Application run failed\r\necho Caused by: example.StartupException: fixture failure\r\nping -n 30 127.0.0.1 >nul\r\n');
  fs.writeFileSync(path.join(root, '.grails-qa/plan.json'), JSON.stringify({ version: 1, domains: [], pages: [{ path: '/' }], gaps: [] }));
  const start = Date.now();
  const report = await runProject(root, { ui: { launch: true } });
  assert.equal(report.status, 'failed');
  assert.match(report.error, /fixture failure/);
  assert(Date.now() - start < 10000, 'must not wait for process exit or the five minute timeout');
  assert.equal(JSON.parse(fs.readFileSync(path.join(root, '.grails-qa/report.json'))).startedAt, report.startedAt);
  assert(!fs.existsSync(path.join(root, '.grails-qa/active.lock')));
});
test('QA runs the workspace wrapper when Windows disables current-directory executable lookup', { skip: process.platform !== 'win32' }, async t => {
  const root = fixture(t);
  const directory = path.join(root, 'workspace with spaces'); fs.mkdirSync(directory);
  fs.writeFileSync(path.join(directory, 'gradlew.bat'), '@echo off\r\necho QA_WRAPPER_OK %~1\r\n');
  const previous = process.env.NoDefaultCurrentDirectoryInExePath;
  process.env.NoDefaultCurrentDirectoryInExePath = '1';
  try {
    const result = await startGradle(directory, ['argument with spaces']).done;
    assert.equal(result.code, 0, result.text);
    assert.match(result.text, /QA_WRAPPER_OK argument with spaces/);
  } finally {
    if (previous === undefined) delete process.env.NoDefaultCurrentDirectoryInExePath;
    else process.env.NoDefaultCurrentDirectoryInExePath = previous;
  }
});
test('QA decimal boundaries preserve precision beyond JavaScript integers', () => {
  assert.equal(decimal('123456789012345678', 1), '123456789012345679');
  assert.equal(decimal('-0.01', 1, 2), '0.00');
  assert.equal(decimal('1.25', -1, 2), '1.24');
  assert.throws(() => decimal('1e40'));
});
test('QA rules record unsupported custom validators and associations as gaps', () => {
  const gaps = [];
  const rows = propertyCases({ name: 'name', type: 'java.lang.String', nullable: false, constraints: { minSize: '3', validator: { unsupported: 'Closure' } } }, gaps, 'demo.Book');
  assert(rows.some(r => r.value === 'xx' && r.reject));
  assert(rows.some(r => r.value === 'xxx' && !r.reject));
  assert(gaps.some(g => /validator/.test(g.reason)));
  assert.equal(propertyCases({ name: 'owner', type: 'demo.User', constraints: {} }, gaps, 'demo.Book').length, 0);
  assert(gaps.some(g => /Association/.test(g.reason)));
});
test('QA scanner never turns an unconfirmed conventional URL into a page test', t => {
  const root = fixture(t);
  fs.writeFileSync(path.join(root, 'grails-app/domain/demo/Book.groovy'), 'package demo\nclass Book { String name }');
  fs.writeFileSync(path.join(root, 'grails-app/views/book/create.gsp'), '<g:form><g:textField name="name" /></g:form>');
  const project = scanProject(root);
  assert.equal(project.domains[0].fqn, 'demo.Book');
  const plan = createPlan({ domains: [] }, project);
  assert.equal(plan.pages.length, 0);
  assert(plan.gaps.some(g => /route not confirmed/.test(g.reason)));
});
test('QA datasource detection ignores excluded-datasources and comments but detects real keys', t => {
  const root = fixture(t);
  const conf = path.join(root, 'grails-app/conf'); fs.mkdirSync(conf);
  const yaml = path.join(conf, 'application.yml');
  fs.writeFileSync(yaml, 'monitoring:\n  excluded-datasources:\n    - reports\n# datasources: example\n');
  assert.equal(scanProject(root).multipleDataSources, false);
  for (const text of ['dataSources:\n  reports: {}', 'environments:\n  test:\n    "datasources": {}']) {
    fs.writeFileSync(yaml, text);
    assert.equal(scanProject(root).multipleDataSources, true);
  }
  fs.writeFileSync(yaml, '');
  const groovy = path.join(conf, 'application.groovy');
  fs.writeFileSync(groovy, '// dataSources { example }\n/* datasources = [:] */');
  assert.equal(scanProject(root).multipleDataSources, false);
  for (const text of ['dataSources { reports {} }', 'environments { test { dataSources.reports.url = "jdbc:h2:mem:test" } }']) {
    fs.writeFileSync(groovy, text);
    assert.equal(scanProject(root).multipleDataSources, true);
  }
});
test('QA refreshes a stale datasource capability gap without replacing frozen expectations', async t => {
  const root = fixture(t);
  const planFile = path.join(root, '.grails-qa/plan.json');
  const baseline = JSON.stringify({ version: 1, domains: [], pages: [], gaps: [{ reason: 'Multiple data sources detected. Automatic local app launch is disabled; configure a prepared QA server.' }] });
  fs.writeFileSync(planFile, baseline);
  const result = await runProject(root, { ui: false });
  assert(!result.gaps.some(g => /Multiple data sources/.test(g.reason)));
  assert.equal(fs.readFileSync(planFile, 'utf8'), baseline);
});
test('QA form submission needs explicit result contracts and field attribution', () => {
  const domain = { name: 'demo.Book', persistence: true, seed: { name: 'valid' }, rows: [{ property: 'name', reject: true, value: '' }] };
  const page = { path: '/book/create', form: 'form', domain: domain.name };
  assert(uiCases({ domains: [domain], pages: [page] }).some(c => c.kind === 'gap'));
  page.success = { selector: '#saved' }; page.error = { selector: '.errors' };
  assert.equal(uiCases({ domains: [domain], pages: [page] }).filter(c => c.kind === 'submit').length, 1);
  page.error.fieldSelector = '[data-field="{field}"]';
  assert.equal(uiCases({ domains: [domain], pages: [page] }).filter(c => c.kind === 'submit').length, 2);
});
test('QA login URLs cannot cross origins or embed credentials', () => {
  const base = 'http://127.0.0.1:1234';
  assert.equal(sameOrigin(base, '/login/auth'), base + '/login/auth');
  for (const route of ['https://example.org', '//evil.invalid', 'http://user:pass@127.0.0.1:1234', 'javascript:alert(1)']) assert.throws(() => sameOrigin(base, route));
});
test('QA credentials are scoped per project and server; logs redact encoded secrets', () => {
  assert.notEqual(credentialKey('a', 'http://one'), credentialKey('a', 'http://two'));
  assert.notEqual(credentialKey('a', 'http://one'), credentialKey('b', 'http://one'));
  assert.equal(redactor(['p@ss'])('p@ss p%40ss'), '[redacted] [redacted]');
});
test('QA rejects credentials written into configuration', t => {
  const root = fixture(t);
  fs.writeFileSync(path.join(root, '.grails-qa/config.json'), JSON.stringify({ auth: { password: 'secret' } }));
  assert.throws(() => readConfig(root), /SecretStorage/);
});
test('QA cannot bypass authentication on an existing server', async t => {
  const root = fixture(t);
  fs.writeFileSync(path.join(root, '.grails-qa/plan.json'), JSON.stringify({ version: 1, domains: [], pages: [{ path: '/' }], gaps: [] }));
  const report = await runProject(root, { auth: { mode: 'bypass' }, ui: { launch: false, baseURL: 'http://127.0.0.1:1' } });
  assert.equal(report.status, 'failed');
  assert.match(report.error, /only available/);
});
test('QA refuses concurrent operations without overwriting an existing lock', async t => {
  const root = fixture(t);
  const file = path.join(root, '.grails-qa/active.lock');
  fs.writeFileSync(file, 'owner');
  await assert.rejects(runProject(root), /Another QA operation/);
  assert.equal(fs.readFileSync(file, 'utf8'), 'owner');
});
test('QA JUnit aggregation preserves failed/skipped outcomes and readable details', t => {
  const root = fixture(t);
  fs.writeFileSync(path.join(root, 'TEST.xml'), '<testsuite tests="3" failures="1" errors="0" skipped="1"><testcase name="good"/><testcase name="bad"><failure message="first&#10;second &amp; third"/></testcase><testcase name="unknown"><skipped/></testcase></testsuite>');
  const result = junit(root);
  assert.equal(result.tests, 3); assert.equal(result.failures, 1); assert.equal(result.skipped, 1);
  assert.equal(result.cases[1].detail, 'first\nsecond & third');
  assert.equal(result.cases[2].status, 'skipped');
});
