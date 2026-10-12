const fs = require('fs');
const path = require('path');
const net = require('net');
const crypto = require('crypto');
const { scanProject, readConfig, walk } = require('./project');
const { createPlan } = require('./rules');
const { writeProbe, writeSpecs } = require('./generate');
const { startGradle, redactor } = require('./process');
const { startupFailure } = require('./startup');
const { describeDomainCases } = require('./use-case');
const { validateBrowserCases } = require('./browser-cases');
const init = path.join(__dirname, 'templates/qa.init.gradle');
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
function folder(root, phase) {
  const dir = path.join(root, '.grails-qa/runs', new Date().toISOString().replace(/[:.]/g, '-') + '-' + phase + '-' + Math.random().toString(16).slice(2, 8));
  fs.mkdirSync(dir, { recursive: true }); return dir;
}
function check(signal) { if (signal?.aborted) throw new Error('QA run cancelled.'); }
function junit(directory) {
  const totals = { tests: 0, failures: 0, errors: 0, skipped: 0 };
  const cases = [];
  for (const file of walk(directory, '.xml')) {
    const xml = fs.readFileSync(file, 'utf8');
    const attrs = /<testsuite\s+([^>]+)/.exec(xml)?.[1] || '';
    for (const key of Object.keys(totals)) totals[key] += Number(new RegExp(`\\b${key}="(\\d+)"`).exec(attrs)?.[1] || 0);
    for (const match of xml.matchAll(/<testcase\s+([^>]+?)(?:\/>|>([\s\S]*?)<\/testcase>)/g)) {
      const decode = s => s.replace(/&#(x[0-9a-f]+|\d+);/gi, (_, code) => String.fromCodePoint(code[0].toLowerCase() === 'x' ? parseInt(code.slice(1), 16) : Number(code))).replace(/&quot;/g, '"').replace(/&apos;/g, "'").replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&amp;/g, '&');
      cases.push({ name: decode(/\bname="([^"]*)"/.exec(match[1])?.[1] || ''),
        status: /<failure|<error/.test(match[2] || '') ? 'failed' : /<skipped/.test(match[2] || '') ? 'skipped' : 'passed',
        detail: decode(/<(?:failure|error|skipped)[^>]*message="([^"]*)"/.exec(match[2] || '')?.[1] || '') });
    }
  }
  return { ...totals, cases };
}
async function gradleTests(root, directory, options) {
  check(options.signal);
  const job = startGradle(root, ['-I', init, `-PgrailsQaSources=${path.join(directory, 'src')}`, `-PgrailsQaOutput=${directory}`, 'grailsQaTest', '--no-daemon'],
    { ...options, logFile: path.join(directory, 'gradle.log') });
  const outcome = await job.done;
  check(options.signal);
  return { ...junit(path.join(directory, 'junit')), exitCode: outcome.code, artifacts: directory };
}
async function planProject(root, options = {}) {
  const project = scanProject(root), config = readConfig(project.root);
  const directory = folder(project.root, 'inventory');
  let inventory = { domains: [], gaps: [] };
  if (project.domains.length) {
    writeProbe(project, path.join(directory, 'src'));
    const outcome = await gradleTests(project.root, directory, options);
    if (outcome.exitCode !== 0) throw new Error('Domain inventory failed. Check ' + path.join(directory, 'gradle.log'));
    inventory = JSON.parse(fs.readFileSync(path.join(directory, 'inventory.json'), 'utf8'));
  }
  const plan = createPlan(inventory, project, config);
  fs.writeFileSync(path.join(project.root, '.grails-qa/plan.json'), JSON.stringify(plan, null, 2));
  options.onOutput?.(`Saved frozen plan: ${plan.domains.length} Domain classes, ${plan.pages.length} UI pages, ${plan.gaps.length} coverage gaps.\n`);
  return plan;
}
async function freePort() {
  const server = net.createServer();
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolve); });
  const port = server.address().port;
  await new Promise(resolve => server.close(resolve)); return port;
}
async function launchQaApp(project, options, directory) {
  const database = options.ui?.database || 'project';
  if (!['project', 'h2'].includes(database)) throw new Error('Choose the project test database or temporary H2.');
  if (database === 'h2' && project.multipleDataSources) throw new Error('Temporary H2 supports one datasource. Choose the project test environment for this application.');
  if (options.auth?.mode === 'bypass' && project.security !== 'grails-spring-security') throw new Error('Authentication bypass requires the Grails Spring Security plugin; custom Spring Security is not automatically disabled.');
  const port = await freePort();
  const args = ['-I', init, `-PgrailsQaPort=${port}`, `-PgrailsQaOutput=${directory}`, `-PgrailsQaDatabase=${database}`, 'bootRun', '--no-daemon'];
  let identityToken;
  if (options.auth?.mode === 'bypass') {
    if (!options.username && !options.discoverIdentities) throw new Error('Skip authentication needs a test username to provide currentUser. No password is required.');
    identityToken = crypto.randomBytes(32).toString('hex');
    const support = path.join(directory, 'identity');
    fs.mkdirSync(path.join(support, 'src'), { recursive: true });
    fs.mkdirSync(path.join(support, 'resources/META-INF'), { recursive: true });
    fs.copyFileSync(path.join(__dirname, 'templates/QaIdentityInitializer.groovy'), path.join(support, 'src/QaIdentityInitializer.groovy'));
    fs.copyFileSync(path.join(__dirname, 'templates/QaIdentityCatalog.groovy'), path.join(support, 'src/QaIdentityCatalog.groovy'));
    fs.writeFileSync(path.join(support, 'resources/META-INF/spring.factories'), 'org.springframework.context.ApplicationContextInitializer=org.grailsqa.runtime.QaIdentityInitializer\n');
    args.push('-PgrailsQaBypass=true');
  }
  const job = startGradle(project.root, args, { ...options, logFile: path.join(directory, 'application.log'),
    secrets: [...(options.secrets || []), identityToken],
    env: { GRAILS_QA_IDENTITY_TOKEN: identityToken || '', GRAILS_QA_IDENTITY_USER: identityToken ? options.username || '' : '',
      GRAILS_QA_DISCOVER: options.discoverIdentities ? 'true' : '', GRAILS_QA_TENANT: options.tenant || '' } });
  let processError;
  job.done.catch(error => { processError = error; });
  const deadline = Date.now() + 300000;
  try {
    while (true) {
      check(options.signal);
      if (processError) throw processError;
      const failure = startupFailure(job.output);
      if (failure) throw new Error(failure);
      if (/Grails application running|Started \S*Application in/.test(job.output)) break;
      if (job.child.exitCode !== null || Date.now() > deadline) throw new Error('QA application did not become ready. Check application.log.');
      await delay(500);
    }
    return { baseURL: `http://127.0.0.1:${port}`, identityToken, stop: job.stop };
  } catch (error) { job.stop(); throw error; }
}
async function runProject(root, options = {}) {
  options = { ...options, secrets: [options.username, options.password] };
  const project = scanProject(root), config = readConfig(project.root);
  const file = path.join(project.root, '.grails-qa/plan.json');
  const plan = fs.existsSync(file) ? JSON.parse(fs.readFileSync(file, 'utf8')) : await planProject(root, options);
  if (plan.version !== 1 || !Array.isArray(plan.domains) || !Array.isArray(plan.pages)) throw new Error('Unsupported QA plan. Generate a new plan.');
  validateBrowserCases(plan.browserCases || []);
  const directory = folder(project.root, 'run');
  const redact = redactor([options.username, options.password]);
  const report = { version: 1, startedAt: new Date().toISOString(), planCreatedAt: plan.createdAt, artifacts: directory,
    ...(plan.ai ? { ai: plan.ai } : {}),
    oracle: plan.oracle, authentication: options.auth?.mode || config.auth?.mode || 'none',
    gaps: plan.gaps.filter(g => g.reason !== 'Multiple data sources detected. Automatic local app launch is disabled; configure a prepared QA server.'), domain: null, ui: null };
  // Launch capability is current project state, not a frozen test expectation.
  for (const domain of project.domains) if (!plan.domains.some(d => d.name === domain.fqn)) report.gaps.push({ domain: domain.fqn, reason: 'New Domain is absent from frozen plan. Generate a new plan to include it.' });
  let app = options.preparedApp;
  try {
    if (options.domain !== false && plan.domains.length) {
      const domainDir = path.join(directory, 'domain');
      writeSpecs(plan, path.join(domainDir, 'src'));
      report.domain = await gradleTests(project.root, domainDir, options);
      describeDomainCases(plan, report.domain.cases);
    }
    check(options.signal);
    const ui = { ...config.ui, ...options.ui };
    report.uiDatabase = ui.launch ? ui.database || 'project' : 'existing-server';
    const auth = { ...config.auth, ...options.auth };
    if (options.ui !== false && (plan.pages.length || plan.browserCases?.length)) {
      if (auth.mode === 'bypass' && !ui.launch) throw new Error('Bypass is only available for a QA instance started by this tool. Existing servers require credentials.');
      if (ui.launch && !app) app = await launchQaApp(project, { ...options, ui, auth }, directory);
      if (options.tenant) report.tenant = options.tenant;
      const baseURL = app?.baseURL || ui.baseURL;
      if (!baseURL) throw new Error('UI tests need a QA base URL or ui.launch=true.');
      const { runUi } = require('./ui');
      report.ui = await runUi(plan, { ...ui, baseURL, auth, username: options.username, password: options.password,
        signal: options.signal, directory, onOutput: options.onOutput, identityToken: app?.identityToken, tenant: options.tenant });
    } else if (options.ui !== false) report.gaps.push({ reason: 'No confirmed UI pages. Add ui.pages to .grails-qa/config.json and generate a plan.' });
  } catch (error) { report.error = redact(error.message); }
  finally { app?.stop(); }
  if (report.authentication === 'bypass') report.gaps.push({ reason: 'Password login and HTTP security filters were bypassed only for this local QA instance. Requests use the selected test user and existing roles; authentication and authorization coverage is excluded.' });
  const failures = report.error || report.domain?.exitCode || report.ui?.failed;
  const incomplete = report.gaps.length || report.domain?.skipped || report.ui?.skipped || (!report.domain?.tests && !report.ui?.tests);
  report.status = failures ? 'failed' : incomplete ? 'partial' : 'passed';
  report.finishedAt = new Date().toISOString();
  fs.writeFileSync(path.join(directory, 'report.json'), redact(JSON.stringify(report, null, 2)));
  fs.writeFileSync(path.join(project.root, '.grails-qa/report.json'), redact(JSON.stringify(report, null, 2)));
  options.onOutput?.(`QA ${report.status}: Domain ${report.domain?.tests || 0}, UI ${report.ui?.tests || 0}; report ${path.join(directory, 'report.json')}\n`);
  if (report.domain) options.onOutput?.(`Domain results: ${report.domain.tests - report.domain.failures - report.domain.errors - report.domain.skipped} passed, ${report.domain.failures + report.domain.errors} failed, ${report.domain.skipped} skipped.\n`);
  if (report.error) options.onOutput?.(`${report.ui ? 'QA error' : 'UI not run / QA execution error'}: ${report.error}\n`);
  return report;
}
function acquire(root) {
  root = fs.realpathSync(root);
  const directory = path.join(root, '.grails-qa'); fs.mkdirSync(directory, { recursive: true });
  const lock = path.join(directory, 'active.lock');
  let handle;
  try { handle = fs.openSync(lock, 'wx'); }
  catch (error) {
    if (error.code !== 'EEXIST') throw error;
    throw new Error('Another QA operation owns .grails-qa/active.lock. Wait for it to finish; after a crashed run, remove the lock only once its process has stopped.');
  }
  fs.writeFileSync(handle, JSON.stringify({ pid: process.pid, startedAt: new Date().toISOString() }));
  let released = false;
  return { root, release() { if (!released) { released = true; fs.closeSync(handle); fs.unlinkSync(lock); } } };
}
async function exclusive(root, operation) {
  const owned = acquire(root);
  try { return await operation(owned.root); } finally { owned.release(); }
}
async function openIdentitySession(root, options = {}) {
  const owned = acquire(root);
  let app, stopped = false;
  const stop = () => { if (!stopped) { stopped = true; app?.stop(); owned.release(); } };
  try {
    const project = scanProject(owned.root);
    app = await launchQaApp(project, { ...options, discoverIdentities: true, auth: { mode: 'bypass' } }, folder(owned.root, 'identities'));
    const abort = () => stop();
    options.signal?.addEventListener('abort', abort, { once: true });
    if (options.signal?.aborted) { stop(); throw new Error('QA identity discovery cancelled.'); }
    const close = () => { options.signal?.removeEventListener('abort', abort); stop(); };
    async function catalog(tenant = '', query = '', offset = 0, exact) {
      if (stopped) throw new Error('Reload test users: the QA process has stopped.');
      const url = new URL('/__grails_qa/identities', app.baseURL);
      url.search = new URLSearchParams({ tenant, query, offset: String(offset) }).toString();
      if (exact !== undefined) url.searchParams.set('exact', exact);
      const response = await fetch(url, { redirect: 'manual', headers: { 'X-Grails-QA-Identity': app.identityToken },
        signal: options.signal ? AbortSignal.any([options.signal, AbortSignal.timeout(30000)]) : AbortSignal.timeout(30000) });
      if (response.status >= 300 && response.status < 400) throw new Error('Test identity discovery was redirected. The QA discovery endpoint did not bypass the application security chain.');
      if (!response.headers.get('content-type')?.includes('application/json')) throw new Error(`Test identity discovery returned HTTP ${response.status} instead of JSON. Check QA application startup and filter registration.`);
      const result = await response.json();
      if (!response.ok) throw new Error(result.error || 'Could not load test identities.');
      return result;
    }
    return { catalog, stop: close, async run(runOptions) {
      if (stopped) throw new Error('Reload test users before running QA.');
      try {
        const available = await catalog(runOptions.tenant || '', '', 0, runOptions.username || '');
        if (!available.users.includes(runOptions.username)) throw new Error('The selected test user is no longer available in this tenant. Reload test users.');
        return await runProject(owned.root, { ...runOptions, ui: { ...runOptions.ui, launch: true, database: options.ui?.database || 'project' },
          auth: { mode: 'bypass' }, preparedApp: { ...app, stop: close } });
      } finally { close(); }
    } };
  } catch (error) { stop(); throw error; }
}
module.exports = {
  async applyAiDraft(root, draft) {
    return exclusive(root, resolved => {
      const cases = validateBrowserCases(draft.cases);
      if (draft.version !== 1 || !cases.length || !Array.isArray(draft.gaps) || draft.gaps.some(g => typeof g !== 'string')) throw new Error('Invalid AI draft.');
      const file = path.join(resolved, '.grails-qa/plan.json');
      if (!fs.existsSync(file)) throw new Error('Generate the base test plan first, then apply the AI draft.');
      const plan = JSON.parse(fs.readFileSync(file, 'utf8'));
      if (plan.version !== 1 || !Array.isArray(plan.domains) || !Array.isArray(plan.pages) || !Array.isArray(plan.gaps)) throw new Error('Unsupported base test plan.');
      plan.browserCases = cases;
      plan.ai = { model: draft.model, generatedAt: draft.createdAt, appliedAt: new Date().toISOString() };
      plan.gaps = plan.gaps.filter(g => g.source !== 'ai').concat(draft.gaps.map(reason => ({ source: 'ai', reason })));
      fs.copyFileSync(file, file + '.before-ai.json');
      fs.writeFileSync(file + '.tmp', JSON.stringify(plan, null, 2));
      fs.renameSync(file + '.tmp', file);
      return plan;
    });
  },
  planProject: (root, options) => exclusive(root, resolved => planProject(resolved, options)),
  runProject: (root, options) => exclusive(root, resolved => runProject(resolved, options)),
  launchQaApp, junit, openIdentitySession
};
