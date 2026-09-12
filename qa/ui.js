const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { redactor } = require('./process');
const { validateBrowserCases, executeSteps } = require('./browser-cases');

function browserLibrary() {
  try { return require('playwright-core'); }
  catch { return require('../dist/qa-browser'); }
}
function sameOrigin(base, route) {
  const url = new URL(route, base);
  if (!['http:', 'https:'].includes(url.protocol) || url.origin !== new URL(base).origin || url.username || url.password) {
    throw new Error('QA page/login routes must stay on the configured application origin.');
  }
  return url.toString();
}
function browserOptions(options) {
  const result = { headless: options.headless !== false };
  if (options.executablePath) result.executablePath = options.executablePath;
  else if (options.channel) result.channel = options.channel;
  else if (process.platform === 'win32' && fs.existsSync('C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe')) result.channel = 'msedge';
  else if (process.platform === 'darwin' && fs.existsSync('/Applications/Google Chrome.app')) result.channel = 'chrome';
  return result;
}
async function login(context, options) {
  const { auth = {}, username, password } = options;
  if (auth.mode !== 'credentials') return;
  if (!username || !password) throw new Error('Provide the QA username and password through the UI or credential environment variables.');
  const page = await context.newPage();
  try {
    const loginURL = sameOrigin(options.baseURL, auth.loginPath || '/login/auth');
    const response = await page.goto(loginURL, { waitUntil: 'domcontentloaded' });
    if (!response || response.status() >= 400) throw new Error('Login page did not load successfully.');
    sameOrigin(options.baseURL, page.url());
    const userField = page.locator(auth.usernameSelector || 'input[name="username"], input[name="j_username"]').first();
    const passwordField = page.locator(auth.passwordSelector || 'input[name="password"], input[name="j_password"]').first();
    const action = await passwordField.evaluate(element => element.form?.action);
    if (action) sameOrigin(options.baseURL, action);
    await userField.fill(username);
    await passwordField.fill(password);
    const submit = page.locator(auth.submitSelector || 'button[type="submit"], input[type="submit"]').first();
    if (auth.successSelector) {
      await submit.click(); await page.locator(auth.successSelector).waitFor({ state: 'visible' });
    } else {
      await Promise.all([
        page.waitForURL(url => url.origin === new URL(options.baseURL).origin && !/\/(?:login|auth)(?:\/|$)/i.test(url.pathname)),
        submit.click()
      ]);
    }
  } finally { await page.close(); }
}
function uiCases(plan) {
  const cases = [];
  for (const page of plan.pages) {
    cases.push({ kind: 'page', name: `Page ${page.path}`, page,
      useCase: `Open ${page.path} and verify that the page loads and configured page markers and fields are visible.` });
    const domain = plan.domains.find(d => d.name === page.domain);
    if (!page.form) continue;
    if (!domain || !domain.persistence || !page.success?.selector || !page.error?.selector) {
      cases.push({ kind: 'gap', name: `Form ${page.path}`, page,
        useCase: `Verify form submission on ${page.path}; currently blocked by missing fixtures or result assertions.`,
        reason: 'Submission needs a Domain fixture and explicit success/error selectors. Configure ui.pages and fixtures.' });
      continue;
    }
    cases.push({ kind: 'submit', name: `Submit valid ${page.path}`, page, values: { ...domain.seed, ...page.values }, expected: page.success,
      useCase: `Fill ${page.path} with the valid fixture, submit the form, and verify the configured success result.` });
    const fields = page.fields || Object.keys(domain.seed);
    if (!page.error.fieldSelector) cases.push({ kind: 'gap', name: `Field errors ${page.path}`, page,
      useCase: `Verify invalid inputs on ${page.path} are rejected for the field under test; currently blocked by missing field error selectors.`,
      reason: 'Configure error.fieldSelector with {field} to attribute rejection to the field under test.' });
    for (const row of domain.rows.filter(r => r.reject && fields.includes(r.property) && page.error.fieldSelector)) {
      cases.push({ kind: 'submit', name: `Reject ${page.path}: ${row.property} ${row.rule} ${row.label}`, page,
        useCase: `Fill ${page.path} with ${row.label || 'an invalid value'} for ${row.property} (${row.rule || 'field validation'}); verify the browser blocks that input or submission, or the application shows an error for that field.`,
        values: { ...domain.seed, ...page.values, [row.property]: row.value }, invalidField: row.property, expected: page.error });
    }
  }
  for (const scenario of validateBrowserCases(plan.browserCases || [])) {
    cases.push({ ...scenario, kind: 'browser', page: { path: scenario.path } });
  }
  return cases;
}
async function fillForm(page, spec, values) {
  const form = page.locator(spec.form);
  if (await form.count() !== 1) throw new Error('Form selector must identify exactly one form: ' + spec.form);
  const fields = spec.fields || Object.keys(values);
  const restricted = [];
  for (const name of fields) {
    if (!(name in values)) throw new Error('No fixture value for form field ' + name);
    const selector = spec.fieldSelectors?.[name] || `[name=${JSON.stringify(name)}]`;
    const field = form.locator(selector);
    if (await field.count() !== 1) throw new Error('Field selector must identify exactly one control: ' + name);
    const tag = await field.evaluate(element => ({ tag: element.tagName, type: element.type }));
    const value = values[name];
    if (tag.type === 'checkbox') await field.setChecked(Boolean(value));
    else if (tag.tag === 'SELECT') await field.selectOption(String(value ?? ''));
    else {
      const text = String(value ?? '');
      await field.fill(text);
      const actual = await field.inputValue();
      if (actual !== text) {
        const maximum = Number(await field.getAttribute('maxlength'));
        if (maximum > 0 && text.length > maximum && actual === text.slice(0, maximum)) restricted.push(name);
        else throw new Error('The control transformed the fixture value for ' + name + '; configure a compatible UI fixture.');
      }
    }
  }
  return { form, restricted };
}
function uniqueValues(plan, scenario) {
  const values = { ...scenario.values };
  const domain = plan.domains.find(d => d.name === scenario.page.domain);
  for (const name of domain?.unique || []) {
    if (name === scenario.invalidField) continue;
    const property = domain.properties.find(p => p.name === name);
    const c = property.constraints;
    if (property.type === 'java.lang.String' && !c.inList && !c.matches && !c.email && !c.url) {
      const min = Number(c.minSize || c.size?.from || 1), max = Number(c.maxSize || c.size?.to || 32);
      values[name] = crypto.randomBytes(16).toString('hex').slice(0, Math.min(32, max)).padEnd(min, 'q');
    }
  }
  return values;
}
async function runUi(plan, options) {
  const scenarios = uiCases(plan);
  const { chromium } = browserLibrary();
  const redact = redactor([options.username, options.password, options.identityToken]);
  const results = [];
  const browser = await chromium.launch(browserOptions(options));
  const abort = () => { void browser.close(); };
  options.signal?.addEventListener('abort', abort, { once: true });
  const directory = path.join(options.directory, 'ui'); fs.mkdirSync(directory, { recursive: true });
  async function identity(context, restrictBrowser = false) {
    if (!options.identityToken && !restrictBrowser) return;
    // Never send the per-run capability to third-party requests or redirects.
    const origin = new URL(options.baseURL).origin;
    await context.route('**/*', async route => {
      if (new URL(route.request().url()).origin !== origin) {
        if (restrictBrowser && (route.request().isNavigationRequest() || !['GET', 'HEAD'].includes(route.request().method()))) return route.abort('blockedbyclient');
        return route.continue();
      }
      if (!options.identityToken) return route.continue();
      const response = await route.fetch({ maxRedirects: 0, headers: {
        ...route.request().headers(), 'X-Grails-QA-Identity': options.identityToken,
        ...(options.username ? { 'X-Grails-QA-User': encodeURIComponent(options.username) } : {}),
        ...(options.tenant ? { 'X-Grails-QA-Tenant': encodeURIComponent(options.tenant) } : {})
      } });
      await route.fulfill({ response });
    });
  }
  try {
    if (options.signal?.aborted) throw new Error('QA run cancelled.');
    const context = await browser.newContext({ baseURL: options.baseURL, viewport: { width: 1280, height: 900 } });
    await identity(context);
    context.setDefaultTimeout(options.timeout || 15000);
    await login(context, options);
    const authenticatedState = await context.storageState(); // memory only; never write session cookies to reports
    await context.close();
    for (const [index, scenario] of scenarios.entries()) {
      if (options.signal?.aborted) throw new Error('QA run cancelled.');
      if (scenario.kind === 'gap') { results.push({ name: scenario.name, useCase: scenario.useCase, status: 'skipped', detail: scenario.reason }); continue; }
      const isolated = await browser.newContext({ baseURL: options.baseURL, storageState: authenticatedState, viewport: { width: 1280, height: 900 }, serviceWorkers: 'block' });
      await identity(isolated, scenario.kind === 'browser');
      isolated.setDefaultTimeout(options.timeout || 15000);
      const page = await isolated.newPage();
      const errors = [];
      page.on('pageerror', error => errors.push(error.message));
      page.on('response', response => { if (response.status() >= 500 && new URL(response.url()).origin === new URL(options.baseURL).origin) errors.push(`HTTP ${response.status()} ${new URL(response.url()).pathname}`); });
      const result = { name: scenario.name, useCase: scenario.useCase, status: 'passed', steps: [] };
      let capture = false;
      try {
        const response = await page.goto(sameOrigin(options.baseURL, scenario.page.path), { waitUntil: 'domcontentloaded' });
        if (!response || response.status() >= 400) throw new Error(`Page returned HTTP ${response?.status() || 'no response'}`);
        sameOrigin(options.baseURL, page.url());
        if (/\/(?:login|auth)(?:\/|$)/i.test(new URL(page.url()).pathname) && !/\/(?:login|auth)(?:\/|$)/i.test(scenario.page.path)) {
          const advice = options.auth?.mode === 'bypass'
            ? 'The selected QA identity did not satisfy the application login checks. Check the test username, required roles and application-specific session requirements.'
            : options.auth?.mode === 'credentials'
              ? 'The login session did not grant access to this page. Check the test account, required roles and application login configuration.'
              : 'Use Log in with test credentials for protected pages.';
          throw new Error('Page redirected to authentication. ' + advice);
        }
        capture = true;
        result.steps.push('Opened ' + scenario.page.path);
        if (scenario.kind === 'page') {
          if (scenario.page.selector) await page.locator(scenario.page.selector).waitFor({ state: 'visible' });
          for (const name of scenario.page.fields || []) {
            await page.locator(scenario.page.fieldSelectors?.[name] || `[name=${JSON.stringify(name)}]`).first().waitFor({ state: 'visible' });
          }
        } else if (scenario.kind === 'browser') {
          await executeSteps(page, scenario, options, result);
        } else {
          const { form, restricted } = await fillForm(page, scenario.page, uniqueValues(plan, scenario));
          result.steps.push('Filled fixture fields');
          // Prevent an unchanged, already visible error/success marker from
          // passing the test without any submission actually taking place.
          if (await page.locator(scenario.expected.selector).isVisible()) throw new Error('Expected result marker is visible before submitting; choose a more specific success/error selector.');
          const nativeInvalid = await form.evaluate(element => !element.checkValidity());
          if (restricted.includes(scenario.invalidField)) {
            result.steps.push('Browser maxlength prevented the target boundary value');
            result.validationLayer = 'browser';
          } else if (restricted.length) throw new Error('A different field truncated the valid fixture.');
          else if (nativeInvalid && scenario.invalidField) {
            const field = form.locator(scenario.page.fieldSelectors?.[scenario.invalidField] || `[name=${JSON.stringify(scenario.invalidField)}]`);
            if (await field.evaluate(element => element.validity.valid)) throw new Error('A different field failed browser validation; this is not evidence for the planned boundary.');
            result.steps.push('Browser rejected the target field');
            result.validationLayer = 'browser';
          } else {
            if (nativeInvalid) throw new Error('Generated valid fixture fails browser validation. Adjust the fixture.');
            const submit = form.locator(scenario.page.submitSelector || 'button[type="submit"], input[type="submit"]').first();
            const action = await form.evaluate(element => element.action);
            sameOrigin(options.baseURL, action);
            await submit.click();
            await page.locator(scenario.expected.selector).waitFor({ state: 'visible' });
            if (scenario.invalidField) await page.locator(scenario.expected.fieldSelector.replaceAll('{field}', scenario.invalidField)).waitFor({ state: 'visible' });
            if (scenario.expected.text && !(await page.locator(scenario.expected.selector).innerText()).includes(scenario.expected.text)) throw new Error('Result marker does not contain expected text.');
            result.steps.push('Submitted form and checked result');
            result.validationLayer = 'application';
          }
        }
        if (errors.length) throw new Error(errors.join('; '));
      } catch (error) { result.status = 'failed'; result.detail = redact((result.pendingStep ? `Step ${result.pendingStep}: ` : '') + error.message); }
      finally {
        // Never screenshot login forms or rejected-login pages containing credentials.
        if (capture && !page.isClosed() && !/\/(?:login|auth)(?:\/|$)/i.test(new URL(page.url()).pathname)) {
          const screenshot = path.join(directory, `${String(index + 1).padStart(3, '0')}-${result.status}.png`);
          try { await page.screenshot({ path: screenshot, fullPage: true }); result.screenshot = screenshot; } catch { /* closed on cancellation */ }
        }
        await isolated.close();
      }
      results.push(result); options.onOutput?.(`UI ${result.status}: ${result.name}\n  Use case: ${result.useCase}\n${result.detail ? '  Reason: ' + result.detail + '\n' : ''}`);
    }
  } catch (error) {
    results.push({ name: 'Browser setup / authentication / execution',
      useCase: 'Start the browser, establish the configured login session, and execute the planned page and form checks.',
      status: 'failed', detail: redact(error.message) });
  } finally { options.signal?.removeEventListener('abort', abort); await browser.close(); }
  return { tests: results.length, failed: results.filter(r => r.status === 'failed').length,
    skipped: results.filter(r => r.status === 'skipped').length, cases: results };
}
module.exports = { runUi, uiCases, sameOrigin, browserOptions };
