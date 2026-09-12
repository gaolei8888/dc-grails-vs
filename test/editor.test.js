const assert = require('assert');
const fs = require('fs');
const path = require('path');
const http = require('http');
const vscode = require('vscode');
const { chromium } = require('playwright-core');
const target = process.env.GRAILS_TESTBED;
const artifacts = process.env.GRAILS_TEST_ARTIFACTS;
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
async function until(predicate, description, timeout = 30000) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) { const value = await predicate(); if (value) return value; await delay(100); }
  throw new Error('Timed out: ' + description);
}
function request(route) {
  return new Promise((resolve, reject) => {
    const req = http.get(`http://127.0.0.1:${process.env.GRAILS_TEST_HTTP_PORT}${route}`, response => {
      let body = '';
      response.on('data', data => { body += data; });
      response.on('end', () => resolve({ status: response.statusCode, body }));
    });
    req.setTimeout(90000, () => req.destroy(new Error('HTTP timeout')));
    req.on('error', reject);
  });
}
suite('Real VS Code + Grails debugger', () => {
  let browser, page, tracker, session;
  const events = [], messages = [];
  const source = relative => vscode.Uri.file(path.join(target, relative));
  const waitStop = (after, reason) => until(() => events.slice(after).find(e => e.event === 'stopped' && (!reason || e.body.reason === reason)), 'debug stop ' + (reason || ''));
  const stack = stop => session.customRequest('stackTrace', { threadId: stop.body.threadId, startFrame: 0, levels: 30 });
  async function screenshot(name) { await delay(600); await page.screenshot({ path: path.join(artifacts, name + '.png') }); }
  suiteSetup(async () => {
    assert(target, 'Use npm run test:editor with GRAILS_TESTBED configured');
    await vscode.extensions.getExtension('gaolei8888.grails-gradle-extension').activate();
    vscode.debug.removeBreakpoints(vscode.debug.breakpoints);
    tracker = vscode.debug.registerDebugAdapterTrackerFactory('groovy', {
      createDebugAdapterTracker() { return {
        onDidSendMessage(message) { messages.push(message); if (message.type === 'event') events.push(message); },
        onWillReceiveMessage(message) { messages.push(message); }
      }; }
    });
    browser = await chromium.connectOverCDP('http://127.0.0.1:' + process.env.GRAILS_TEST_CDP_PORT);
    page = await until(() => browser.contexts()[0].pages().find(p => p.url().includes('workbench')), 'workbench page');
  });
  suiteTeardown(async () => {
    fs.writeFileSync(path.join(artifacts, 'dap-transcript.json'), JSON.stringify(messages, null, 2));
    vscode.debug.removeBreakpoints(vscode.debug.breakpoints);
    if (session) await vscode.debug.stopDebugging(session);
    if (tracker) tracker.dispose();
    if (browser) await browser.close();
  });
  teardown(async function () {
    if (this.currentTest.state === 'failed' && page) {
      await screenshot('failure');
      fs.writeFileSync(path.join(artifacts, 'failure-dom.html'), await page.content());
      // Release a paused target so a failure cannot strand subsequent requests.
      vscode.debug.removeBreakpoints(vscode.debug.breakpoints);
      if (session) await session.customRequest('continue', { threadId: events.filter(e => e.event === 'stopped').at(-1)?.body.threadId });
    }
  });
  test('Attach command accepts the target through the real input box', async () => {
    const command = vscode.commands.executeCommand('grails.attach');
    const input = page.locator('.quick-input-widget input');
    await input.waitFor({ state: 'visible' });
    await input.fill('127.0.0.1:' + process.env.GRAILS_TEST_DEBUG_PORT);
    await input.press('Enter');
    await command;
    session = await until(() => vscode.debug.activeDebugSession, 'attached session');
    assert.strictEqual(session.type, 'groovy');
    await until(async () => { try { return (await request('/')).status < 500; } catch { return false; } }, 'Grails HTTP ready', 110000);
  });
  test('GSP language, breakpoint gutter, hit and step over in the editor', async () => {
    const uri = source('grails-app/views/spike/page.gsp');
    const document = await vscode.workspace.openTextDocument(uri);
    assert.strictEqual(document.languageId, 'gsp');
    const editor = await vscode.window.showTextDocument(document);
    editor.selection = new vscode.Selection(3, 0, 3, 0);
    await vscode.commands.executeCommand('editor.debug.action.toggleBreakpoint');
    await until(() => messages.some(m => m.type === 'response' && m.command === 'setBreakpoints'), 'VS Code sent breakpoint');
    const before = events.length;
    const response = request('/gsppage');
    const stop = await waitStop(before, 'breakpoint');
    let frames = await stack(stop);
    assert.strictEqual(frames.stackFrames[0].line, 4);
    assert(frames.stackFrames[0].source.path.endsWith('page.gsp'));
    await screenshot('gsp-breakpoint');
    const next = events.length;
    await vscode.commands.executeCommand('workbench.action.debug.stepOver');
    const stepped = await waitStop(next, 'step');
    frames = await stack(stepped);
    assert.notStrictEqual(frames.stackFrames[0].line, 4, 'step over must leave the page line');
    await screenshot('gsp-step');
    vscode.debug.removeBreakpoints(vscode.debug.breakpoints);
    await session.customRequest('continue', { threadId: stepped.body.threadId });
    assert.strictEqual((await response).status, 200);
  });
  test('Instance data breakpoint reports two successive field writes', async () => {
    const uri = source('grails-app/services/dapspike/SpikeService.groovy');
    const bp = new vscode.SourceBreakpoint(new vscode.Location(uri, new vscode.Position(56, 0)));
    vscode.debug.addBreakpoints([bp]);
    await delay(400);
    const before = events.length;
    const first = request('/bump');
    const stop = await waitStop(before, 'breakpoint');
    const frames = await stack(stop);
    const scopes = await session.customRequest('scopes', { frameId: frames.stackFrames[0].id });
    let fieldContainer;
    for (const scope of scopes.scopes) {
      const vars = await session.customRequest('variables', { variablesReference: scope.variablesReference });
      const self = vars.variables.find(v => v.name === 'this');
      if (self) { fieldContainer = self.variablesReference; break; }
    }
    assert(fieldContainer, 'this instance is expandable');
    const info = await session.customRequest('dataBreakpointInfo', { variablesReference: fieldContainer, name: 'touches' });
    assert(info.dataId);
    assert.strictEqual(info.canPersist, false);
    // Use the actual Variables pane menu so this covers the client UI as well
    // as the adapter's watchpoint protocol.
    const selfRow = page.locator('.debug-variables .monaco-list-row').filter({ hasText: 'this' }).first();
    if (await selfRow.getAttribute('aria-expanded') !== 'true') await selfRow.locator('.monaco-tl-twistie').click();
    const fieldLabel = page.locator('.debug-variables .monaco-list-row').filter({ hasText: /touches/ }).first();
    await fieldLabel.click({ button: 'right' });
    await screenshot('data-menu');
    await page.getByRole('menuitem', { name: /Break on Value Change/ }).click({ delay: 150 });
    await until(() => messages.some(m => m.type === 'response' && m.command === 'setDataBreakpoints' && m.body?.breakpoints?.[0]?.verified), 'UI armed data breakpoint');
    vscode.debug.removeBreakpoints([bp]);
    let next = events.length;
    await session.customRequest('continue', { threadId: stop.body.threadId });
    const write1 = await waitStop(next, 'data breakpoint');
    assert.match(write1.body.description || write1.body.text, /0 -> 1/);
    await screenshot('data-breakpoint');
    await session.customRequest('continue', { threadId: write1.body.threadId });
    assert.match((await first).body, /touches=1/);
    next = events.length;
    const second = request('/bump');
    const write2 = await waitStop(next, 'data breakpoint');
    assert.match(write2.body.description || write2.body.text, /1 -> 2/);
    await session.customRequest('setDataBreakpoints', { breakpoints: [] });
    await session.customRequest('continue', { threadId: write2.body.threadId });
    assert.match((await second).body, /touches=2/);
  });
  test('Exception context is read from a real stop; no-model flow stays usable', async () => {
    await session.customRequest('setExceptionBreakpoints', { filters: ['caught', 'uncaught'] });
    const before = events.length;
    const response = request('/spike/editor-test');
    const stop = await waitStop(before, 'exception');
    const uri = await vscode.commands.executeCommand('grails.showExceptionContext');
    assert(uri, 'context document returned');
    const document = await vscode.workspace.openTextDocument(uri);
    assert.match(document.getText(), /IllegalStateException/);
    assert.match(document.getText(), /deliberate failure: editor-test/);
    assert.match(document.getText(), /SpikeService.groovy/);
    assert.match(document.getText(), /throw new IllegalStateException/);
    assert(!document.getText().includes('C:\\\\Users'), 'does not include absolute workspace paths');
    await screenshot('exception-context');
    const noModel = await vscode.commands.executeCommand('grails.explainException');
    assert.match((await vscode.workspace.openTextDocument(noModel)).getText(), /No language model is available/);
    await session.customRequest('setExceptionBreakpoints', { filters: [] });
    await session.customRequest('continue', { threadId: stop.body.threadId });
    assert.strictEqual((await response).status, 500);
    await until(async () => (await page.getByText('Explain Exception', { exact: true }).count()) === 0, 'exception status cleared');
  });
});
