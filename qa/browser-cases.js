/* global document */
// A small, validated browser action language; model output is never executable JS.
const actions = new Set(['click', 'fill', 'select', 'check', 'visible', 'text', 'url']);
function route(value) {
  if (typeof value !== 'string' || !value.startsWith('/') || value.startsWith('//') || /[\\\r\n]/.test(value)) throw new Error('Browser case paths must be application-relative URLs.');
  return value;
}
function string(value, name, max = 2000) {
  if (typeof value !== 'string' || !value.trim() || value.length > max) throw new Error('Invalid browser case ' + name);
  return value;
}
function validateBrowserCases(cases) {
  if (!Array.isArray(cases) || cases.length > 40) throw new Error('Expected at most 40 browser cases.');
  return cases.map(c => {
    if (!c || !Array.isArray(c.steps) || !c.steps.length || c.steps.length > 30) throw new Error('Each browser case needs 1–30 steps.');
    const steps = c.steps.map(step => {
      if (!step || !actions.has(step.action)) throw new Error('Unsupported browser action: ' + step?.action);
      const clean = { action: step.action };
      if (step.action === 'url') clean.value = route(step.value);
      else clean.selector = string(step.selector, 'selector');
      if (['fill', 'select', 'text'].includes(step.action)) {
        if (typeof step.value !== 'string' || step.value.length > 2000 || (step.action === 'text' && !step.value.trim())) throw new Error('Invalid browser step value.');
        clean.value = step.value;
      }
      if (step.action === 'check') {
        if (typeof step.value !== 'boolean') throw new Error('check requires a boolean value.');
        clean.value = step.value;
      }
      return clean;
    });
    if (!['visible', 'text', 'url'].includes(steps.at(-1).action)) throw new Error('Each browser case must end with an explicit assertion.');
    return { name: string(c.name, 'name', 200), useCase: string(c.useCase, 'useCase'), path: route(c.path), steps };
  });
}
async function executeSteps(page, scenario, options, result) {
  const origin = new URL(options.baseURL).origin;
  for (const [index, step] of scenario.steps.entries()) {
    result.pendingStep = `${index + 1}. ${step.action} ${step.selector || step.value}`;
    if (options.signal?.aborted) throw new Error('QA run cancelled.');
    if (new URL(page.url()).origin !== origin) throw new Error('Browser left the QA application.');
    const locator = step.selector ? page.locator(step.selector) : null;
    if (step.action === 'click') await locator.click();
    else if (step.action === 'fill') {
      if (await locator.getAttribute('type') === 'password') throw new Error('Use configured QA authentication for password fields.');
      await locator.fill(step.value);
    } else if (step.action === 'select') await locator.selectOption(step.value);
    else if (step.action === 'check') await locator.setChecked(step.value);
    else if (step.action === 'url') await page.waitForURL(url => url.origin === origin && url.pathname + url.search + url.hash === step.value);
    else {
      await locator.waitFor({ state: 'visible' });
      if (step.action === 'text') {
        await page.waitForFunction(({ selector, text }) => {
          const element = document.querySelector(selector);
          return element && element.textContent.includes(text);
        }, { selector: step.selector, text: step.value }, { timeout: options.timeout || 15000 });
      }
    }
    result.steps.push(`${index + 1}. ${step.action} ${step.selector || step.value}`);
    options.onOutput?.(`  ${result.steps.at(-1)}\n`);
    delete result.pendingStep;
  }
}
module.exports = { validateBrowserCases, executeSteps };
