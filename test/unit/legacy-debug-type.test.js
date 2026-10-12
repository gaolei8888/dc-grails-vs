// The "groovy" debug type up to 0.1.29 is kept as an alias of "groovy-dap". These
// tests run extension.js against a mocked vscode: they check this extension's
// logic, not what VS Code does with two contributed debuggers.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const Module = require('node:module');

const extensionPath = require.resolve('../../extension.js');
const manifest = require('../../package.json');
const CONFLICT = 'a DebugAdapterDescriptorFactory can only be registered once per a type';

let current; // the mock the next require('vscode') hands out
const realLoad = Module._load;
Module._load = function (request, ...rest) {
  return request === 'vscode' ? current : realLoad.call(this, request, ...rest);
};

class Emitter {
  listeners = new Set();
  event = listener => { this.listeners.add(listener); return { dispose: () => this.listeners.delete(listener) }; };
  fire(value) { for (const listener of this.listeners) listener(value); }
  dispose() { this.listeners.clear(); }
}

/** A vscode whose debug type `taken` already has a factory, as when Code Groovy owns it. */
function activateWith({ taken = [], folder, session, input } = {}) {
  const factories = new Map(), providers = [], trackers = new Map(), commands = new Map();
  const lines = [], dialogs = [], started = [], statusItems = [];
  const root = folder || fs.mkdtempSync(path.join(os.tmpdir(), 'legacy-type-'));
  const jar = path.join(root, 'dist', 'groovy-dap.jar');
  fs.mkdirSync(path.dirname(jar), { recursive: true });
  fs.writeFileSync(jar, '');
  const dialog = async text => { dialogs.push(text); };
  current = {
    EventEmitter: Emitter,
    StatusBarAlignment: { Left: 1 }, ViewColumn: { Beside: 2 }, ProgressLocation: { Notification: 1 },
    TreeItem: class { constructor(label) { this.label = label; } },
    ThemeIcon: class { constructor(id) { this.id = id; } },
    TreeItemCollapsibleState: { None: 0, Expanded: 2 },
    DebugAdapterExecutable: class { constructor(command, args) { this.command = command; this.args = args; } },
    DebugConfigurationProviderTriggerKind: { Initial: 1, Dynamic: 2 },
    Uri: { file: value => ({ fsPath: value }), parse: value => ({ fsPath: value }) },
    env: { language: 'en', openExternal() {} },
    extensions: { getExtension: () => undefined },
    commands: {
      executeCommand: async () => undefined,
      registerCommand: (name, handler) => { commands.set(name, handler); return { dispose() {} }; }
    },
    debug: {
      activeDebugSession: session,
      registerDebugAdapterDescriptorFactory(type, factory) {
        if (taken.includes(type) || factories.has(type)) throw new Error(CONFLICT);
        factories.set(type, factory);
        return { dispose() {} };
      },
      registerDebugConfigurationProvider(type, provider, kind) { providers.push({ type, provider, kind }); return { dispose() {} }; },
      registerDebugAdapterTrackerFactory(type, factory) { trackers.set(type, factory); return { dispose() {} }; },
      onDidChangeActiveDebugSession: new Emitter().event,
      onDidTerminateDebugSession: new Emitter().event,
      startDebugging: async (_folder, config) => { started.push(config); return true; }
    },
    workspace: {
      workspaceFolders: [{ uri: { fsPath: root }, name: 'target', index: 0 }],
      getConfiguration: () => ({ get: (_key, fallback) => fallback }),
      onDidChangeWorkspaceFolders: new Emitter().event,
      onDidCloseTextDocument: new Emitter().event,
      registerTextDocumentContentProvider: () => ({ dispose() {} })
    },
    window: {
      createOutputChannel: () => ({ appendLine: line => lines.push(line), show() {}, dispose() {} }),
      createStatusBarItem: () => { const item = { show() {}, hide() {}, dispose() {} }; statusItems.push(item); return item; },
      registerTreeDataProvider: () => ({ dispose() {} }),
      showWarningMessage: dialog, showInformationMessage: dialog, showErrorMessage: dialog,
      showInputBox: async () => input,
      activeTextEditor: undefined
    }
  };
  delete require.cache[extensionPath]; // fresh module state per activation
  const extension = require(extensionPath);
  const context = {
    subscriptions: [], asAbsolutePath: relative => path.join(root, relative),
    workspaceState: { get: (_key, fallback) => fallback, update() {} }
  };
  extension.activate(context);
  return { factories, providers, trackers, commands, lines, dialogs, started, statusItems, root, context };
}

test('both debug types get an adapter factory on a normal activation', () => {
  const { factories, trackers, lines } = activateWith();
  assert.deepEqual([...factories.keys()].sort(), ['groovy', 'groovy-dap']);
  assert.deepEqual([...trackers.keys()].sort(), ['groovy', 'groovy-dap']);
  for (const type of ['groovy', 'groovy-dap']) {
    assert.equal(typeof trackers.get(type).createDebugAdapterTracker({ id: type }).onDidSendMessage, 'function');
  }
  assert.deepEqual(lines, [], 'nothing to log when the alias is ours');
});

test('another extension owning "groovy" costs the alias only: one log line, no dialog', () => {
  const { factories, providers, trackers, dialogs, lines, statusItems, commands } = activateWith({ taken: ['groovy'] });
  assert.deepEqual([...factories.keys()], ['groovy-dap']);
  assert.deepEqual([...trackers.keys()], ['groovy-dap'], 'no tracker on a type that is not ours');
  assert.ok(!providers.some(p => p.type === 'groovy'), 'no resolver on a type that is not ours');
  assert.equal(statusItems.length, activateWith().statusItems.length, 'same status bar items as when nothing conflicts');
  assert.ok(statusItems.length >= 4);
  assert.ok(commands.has('grails.debug') && commands.has('grails.attach'));
  assert.deepEqual(dialogs, []);
  assert.deepEqual(lines, ['[grails] legacy debug type "groovy" is owned by another extension; use "type": "groovy-dap" in launch.json']);
});

test('the primary groovy-dap conflict still warns and does not abort activation', () => {
  const { factories, dialogs } = activateWith({ taken: ['groovy-dap'] });
  assert.ok(factories.has('groovy'), 'the alias is independent of the primary');
  assert.equal(dialogs.length, 1);
  assert.match(dialogs[0], /already registered the "groovy-dap" debug type/);
});

test('a "groovy" configuration resolves like a "groovy-dap" one and logs a hint', () => {
  const { providers, lines, root } = activateWith();
  fs.mkdirSync(path.join(root, 'grails-app', 'controllers'), { recursive: true });
  const folder = { uri: { fsPath: root } };
  const resolve = type => {
    const provider = providers.find(p => p.type === type && p.kind === undefined).provider;
    return provider.resolveDebugConfiguration(folder, { type, request: 'attach', name: 'old', port: 5005 });
  };
  const legacy = resolve('groovy'), current = resolve('groovy-dap');
  assert.deepEqual({ ...legacy, type: 'x' }, { ...current, type: 'x' });
  assert.deepEqual(legacy.sourcePaths, [path.join(root, 'grails-app', 'controllers')]);
  assert.equal(lines.filter(line => /legacy "groovy" debug type/.test(line)).length, 1);
  assert.ok(!providers.some(p => p.type === 'groovy' && p.kind === 2), 'the dynamic provider offers groovy-dap only');
});

test('both types start the same adapter process', (t) => {
  const { factories } = activateWith();
  const make = type => factories.get(type).createDebugAdapterDescriptor({});
  const legacy = make('groovy'), current = make('groovy-dap');
  if (!current) return t.skip('no JDK 17+ found on this machine');
  assert.deepEqual(legacy, current);
  assert.ok(current.args.includes('jdk.jdi'));
});

test('grails.attach sees a legacy-typed session as ours (double-attach guard)', async () => {
  const session = { type: 'groovy', configuration: { type: 'groovy', port: 5005, hostName: 'localhost' } };
  const { commands, dialogs, started } = activateWith({ session, input: 'localhost:5005' });
  await commands.get('grails.attach')();
  assert.deepEqual(started, [], 'no second session');
  assert.deepEqual(dialogs, ['A debugger is already attached to localhost:5005.']);
});

test('grails.attach still starts a session when the active one is not ours', async () => {
  const session = { type: 'java', configuration: { port: 5005 } };
  const { commands, started } = activateWith({ session, input: 'localhost:5005' });
  await commands.get('grails.attach')();
  assert.equal(started.length, 1);
  assert.equal(started[0].type, 'groovy-dap');
});

test('package.json: one debugger offers templates, the legacy one offers none', () => {
  const byType = Object.fromEntries(manifest.contributes.debuggers.map(d => [d.type, d]));
  assert.deepEqual(Object.keys(byType).sort(), ['groovy', 'groovy-dap']);
  const withTemplates = manifest.contributes.debuggers.filter(d => d.initialConfigurations || d.configurationSnippets);
  assert.deepEqual(withTemplates.map(d => d.type), ['groovy-dap']);
  assert.match(byType.groovy.label, /legacy/);
  assert.notEqual(byType.groovy.label, byType['groovy-dap'].label);
  assert.deepEqual(byType.groovy.languages, byType['groovy-dap'].languages);
  assert.deepEqual(byType.groovy.configurationAttributes, byType['groovy-dap'].configurationAttributes);
  assert.ok(manifest.activationEvents.includes('onDebugResolve:groovy'));
  assert.ok(manifest.activationEvents.includes('onDebugResolve:groovy-dap'));
  assert.deepEqual(manifest.contributes.breakpoints, [{ language: 'groovy' }, { language: 'gsp' }]);
});
