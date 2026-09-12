const { test } = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const { registerExceptionExplainer } = require('../../lib/explain-exception');
const { collectExceptionContext } = require('../../lib/exception-context');

class Emitter {
  listeners = new Set();
  event = listener => { this.listeners.add(listener); return { dispose: () => this.listeners.delete(listener) }; };
  fire(value) { for (const listener of this.listeners) listener(value); }
  dispose() { this.listeners.clear(); }
}
class Cancellation {
  emitter = new Emitter();
  token = { isCancellationRequested: false, onCancellationRequested: this.emitter.event };
  cancel() { this.token.isCancellationRequested = true; this.emitter.fire(); }
  dispose() { this.emitter.dispose(); }
}
function fixture(options = {}) {
  const commands = new Map(), contents = new Map(), errors = [], requests = [], prompts = [];
  const root = path.resolve('unit-fixture');
  const file = path.join(root, 'grails-app', 'services', 'Demo.groovy');
  const uri = value => ({ scheme: value.startsWith('grails-exception:') ? 'grails-exception' : 'file', fsPath: value, toString: () => value });
  let provider, tracker, visible = false;
  const session = { id: 'one', type: 'groovy', customRequest: async (command, args) => {
    requests.push({ command, args });
    if (options.onRequest) await options.onRequest(command);
    if (command === 'exceptionInfo') return { exceptionId: 'java.lang.IllegalStateException', description: 'test failure', breakMode: 'always' };
    if (command === 'stackTrace') return { stackFrames: options.frames || [
      { name: 'Demo.boom', source: { path: file }, line: 3 },
      { name: 'Framework.invoke', source: { path: path.resolve('external', 'Framework.java') }, line: 1 }
    ] };
    throw new Error('Unexpected DAP request: ' + command);
  } };
  const model = { name: 'Test model', vendor: 'test', family: 'test', id: 'test-model', maxInputTokens: 10000,
    countTokens: async () => 100,
    sendRequest: async messages => {
      prompts.push(messages);
      return { text: (async function* () { yield 'Likely cause: '; yield 'a deliberate exception.'; })() };
    }, ...options.model };
  const api = {
    EventEmitter: Emitter, CancellationTokenSource: Cancellation,
    Uri: { file: uri, parse: uri }, StatusBarAlignment: { Left: 1 }, ViewColumn: { Beside: 2 }, ProgressLocation: { Notification: 1 },
    env: { language: 'zh-cn' }, LanguageModelChatMessage: { User: content => ({ role: 1, content }) },
    lm: { selectChatModels: async () => options.noModels ? [] : [model] },
    commands: {
      executeCommand: async (name, ...args) => { if (name !== 'setContext') return commands.get(name)(...args); },
      registerCommand: (name, handler) => { commands.set(name, handler); return { dispose() {} }; }
    },
    debug: { activeDebugSession: session,
      registerDebugAdapterTrackerFactory: (_type, factory) => { tracker = factory.createDebugAdapterTracker(session); return { dispose() {} }; },
      onDidChangeActiveDebugSession: new Emitter().event, onDidTerminateDebugSession: new Emitter().event
    },
    workspace: {
      getWorkspaceFolder: source => source.fsPath.startsWith(root + path.sep) ? { uri: uri(root) } : undefined,
      openTextDocument: async source => {
        if (source.scheme === 'grails-exception') return { uri: source, getText: () => provider.provideTextDocumentContent(source) };
        const lines = ['class Demo {', '  void boom() {', '    throw new IllegalStateException("test failure")', '  }', '}'];
        return { lineCount: lines.length, isDirty: true, lineAt: i => ({ text: lines[i] }) };
      },
      registerTextDocumentContentProvider: (_scheme, value) => { provider = value; return { dispose() {} }; },
      onDidCloseTextDocument: new Emitter().event
    },
    window: {
      createStatusBarItem: () => ({ show() { visible = true; }, hide() { visible = false; }, dispose() {} }),
      showTextDocument: async document => { contents.set(document.uri.toString(), document); },
      showQuickPick: async items => options.cancelPicker ? undefined : items[0],
      showInformationMessage: async text => errors.push(text), showErrorMessage: async text => errors.push(text),
      withProgress: async (_options, callback) => callback({}, new Cancellation().token)
    }
  };
  const context = { subscriptions: [] };
  registerExceptionExplainer(api, context);
  tracker.onDidSendMessage({ type: 'event', event: 'stopped', body: { reason: 'exception', threadId: 7 } });
  return { api, model, session, root, file, errors, requests, prompts, contents,
    get tracker() { return tracker; }, get visible() { return visible; },
    run: name => commands.get(name || 'grails.explainException')(),
    text: () => [...contents.values()].map(document => document.getText()).join('\n'),
    dispose: () => context.subscriptions.forEach(d => d.dispose()) };
}

test('Streams model response with bounded, read-only exception context', async () => {
  const f = fixture();
  try {
    await f.run();
    assert.match(f.text(), /Likely cause: a deliberate exception/);
    assert.match(f.prompts[0][0].content, /zh-cn/);
    assert.match(f.prompts[0][0].content, /throw new IllegalStateException/);
    assert(!f.prompts[0][0].content.includes('Framework.invoke'));
    assert(!f.prompts[0][0].content.includes(f.root));
    assert.deepEqual(f.requests.map(r => r.command), ['exceptionInfo', 'stackTrace']);
    assert.match(f.text(), /"unsaved": true/);
    assert.equal(f.errors.length, 0);
  } finally { f.dispose(); }
});
test('No provider preserves a usable local snapshot and never sends', async () => {
  const f = fixture({ noModels: true });
  await f.run();
  assert.match(f.text(), /No language model is available/);
  assert.match(f.text(), /test failure/);
  assert.equal(f.prompts.length, 0);
  f.dispose();
});
test('Cancelling the model picker sends nothing', async () => {
  const f = fixture({ cancelPicker: true });
  await f.run();
  assert.equal(f.prompts.length, 0);
  assert.equal(f.contents.size, 0);
  f.dispose();
});
test('Resuming during collection discards stale frames', async () => {
  let f;
  f = fixture({ onRequest: command => { if (command === 'stackTrace') f.tracker.onWillReceiveMessage({ type: 'request', command: 'continue' }); } });
  await f.run();
  assert.equal(f.prompts.length, 0);
  assert.equal(f.contents.size, 0);
  assert.equal(f.visible, false);
  f.dispose();
});
test('Resuming during a stream cancels it and labels the partial result', async () => {
  let f, token;
  f = fixture({ model: { sendRequest: async (_messages, _options, cancellation) => {
    token = cancellation;
    return { text: (async function* () {
      yield 'Partial explanation.';
      f.tracker.onWillReceiveMessage({ type: 'request', command: 'next' });
      yield 'Must not be shown.';
    })() };
  } } });
  await f.run();
  assert(token.isCancellationRequested);
  assert.match(f.text(), /Partial explanation/);
  assert.match(f.text(), /Cancelled/);
  assert(!f.text().includes('Must not be shown'));
  f.dispose();
});
test('Provider denial and stream errors are reported without modifying source', async () => {
  for (const streaming of [false, true]) {
    const f = fixture({ model: { sendRequest: async () => {
      if (!streaming) throw new Error('permission denied');
      return { text: (async function* () { yield 'First fragment'; throw new Error('connection lost'); })() };
    } } });
    await f.run();
    assert.match(f.text(), streaming ? /First fragment[\s\S]*connection lost/ : /permission denied/);
    f.dispose();
  }
});
test('Token budget sheds source before exceeding the model input limit', async () => {
  const f = fixture({ model: { maxInputTokens: 1000,
    countTokens: async message => JSON.parse(message.content.slice(message.content.indexOf('{'))).snippets.length ? 2000 : 200 } });
  await f.run();
  assert.equal(f.prompts.length, 1);
  assert.match(f.prompts[0][0].content, /"snippets": \[\]/);
  assert.match(f.prompts[0][0].content, /test failure/);
  f.dispose();
});
test('Context-only command never requests models', async () => {
  const f = fixture();
  f.api.lm.selectChatModels = () => { throw new Error('must not request a provider'); };
  await f.run('grails.showExceptionContext');
  assert.match(f.text(), /Exception context/);
  assert.equal(f.errors.length, 0);
  f.dispose();
});
test('Another session or a non-exception stop cannot reuse an exception', async () => {
  const f = fixture();
  f.api.debug.activeDebugSession = { id: 'two', type: 'groovy' };
  await f.run();
  assert.match(f.errors[0], /Pause on a Groovy exception first/);
  f.api.debug.activeDebugSession = f.session;
  f.tracker.onDidSendMessage({ type: 'event', event: 'stopped', body: { reason: 'breakpoint', threadId: 8 } });
  await f.run();
  assert.equal(f.prompts.length, 0);
  f.dispose();
});
test('Context excludes non-workspace files and rejects invalidated stops', async () => {
  const f = fixture({ frames: [{ name: 'Secrets', source: { path: path.resolve('outside', 'Secrets.groovy') }, line: 1 }] });
  const snapshot = await collectExceptionContext(f.api, f.session, { threadId: 7 }, () => true);
  assert.deepEqual(snapshot.frames, []);
  await assert.rejects(collectExceptionContext(f.api, f.session, { threadId: 7 }, () => false), /no longer paused/);
  f.dispose();
});
