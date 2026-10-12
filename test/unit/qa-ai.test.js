const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { parseDraft, generateAiDraft, sourceContext } = require('../../lib/qa-ai');
const { applyAiDraft } = require('../../qa/engine');
const sample = () => ({ cases: [{ name: 'Search', useCase: 'Search for an item and see its result.', path: '/search',
  steps: [{ action: 'fill', selector: '#query', value: 'sample' }, { action: 'click', selector: '#search' }, { action: 'text', selector: '#result', value: 'sample' }] }], gaps: [] });

test('AI output requires bounded declarative steps and explicit assertions', () => {
  assert.equal(parseDraft(JSON.stringify(sample())).cases.length, 1);
  for (const mutate of [
    d => d.cases[0].steps.push({ action: 'evaluate', value: 'process.exit()' }),
    d => d.cases[0].steps.pop(),
    d => d.cases[0].path = '//external.test',
    d => d.cases[0].path = '/\\external.test',
    d => d.cases[0].steps[2].value = '',
    d => d.cases[0].steps[2] = { action: 'url', value: 'https://external.test' }
  ]) { const draft = sample(); mutate(draft); assert.throws(() => parseDraft(JSON.stringify(draft))); }
});

test('Applying AI cases preserves Domain baseline and rejects invalid drafts without overwriting', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'qa-ai-plan-'));
  try {
    fs.mkdirSync(path.join(root, '.grails-qa'));
    const file = path.join(root, '.grails-qa/plan.json');
    const original = { version: 1, createdAt: 'frozen', domains: [{ name: 'Item', seed: { count: 3 } }], pages: [], gaps: [{ reason: 'existing gap' }] };
    fs.writeFileSync(file, JSON.stringify(original));
    await applyAiDraft(root, { version: 1, ...sample() });
    const applied = fs.readFileSync(file, 'utf8');
    assert.deepEqual(JSON.parse(applied).domains, original.domains);
    assert.equal(JSON.parse(applied).createdAt, 'frozen');
    assert.deepEqual(JSON.parse(fs.readFileSync(file + '.before-ai.json')), original);
    await assert.rejects(applyAiDraft(root, { version: 1, cases: [], gaps: [] }));
    assert.equal(fs.readFileSync(file, 'utf8'), applied);
    assert.equal(fs.existsSync(path.join(root, '.grails-qa/active.lock')), false);
  } finally { fs.rmSync(root, { recursive: true, force: true }); }
});

test('AI generation streams a validated draft, limits context, excludes config, and handles cancellation', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'qa-ai-context-'));
  let requestCount = 0;
  try {
    fs.mkdirSync(path.join(root, 'grails-app/controllers'), { recursive: true });
    fs.mkdirSync(path.join(root, 'grails-app/conf'), { recursive: true });
    fs.writeFileSync(path.join(root, 'grails-app/controllers/ItemController.groovy'), 'class ItemController {}');
    fs.writeFileSync(path.join(root, 'grails-app/conf/application.yml'), 'password: MUST_NOT_SEND');
    assert.equal(sourceContext(root).files.length, 1);
    const model = { id: 'fixture', name: 'Fixture model', maxInputTokens: 10000, countTokens: async () => 100,
      async sendRequest(messages) {
        requestCount++; assert(!messages[0].includes('MUST_NOT_SEND'));
        return { text: (async function* () { const output = JSON.stringify(sample()); yield output.slice(0, 25); yield output.slice(25); })() };
      } };
    const vscode = { lm: { selectChatModels: async () => [model] },
      window: { showQuickPick: async rows => rows[0] }, LanguageModelChatMessage: { User: text => text },
      CancellationTokenSource: class { token = {}; cancel() {} dispose() {} } };
    const draft = await generateAiDraft(vscode, root, new AbortController().signal, () => {});
    assert.equal(draft.cases[0].name, 'Search'); assert.equal(draft.model, 'fixture');
    vscode.window.showQuickPick = async () => undefined;
    assert.equal(await generateAiDraft(vscode, root, new AbortController().signal, () => {}), null);
    assert.equal(requestCount, 1);
    const cancelled = new AbortController(); cancelled.abort();
    await assert.rejects(generateAiDraft(vscode, root, cancelled.signal, () => {}), /cancelled/);
    vscode.lm.selectChatModels = async () => [];
    await assert.rejects(generateAiDraft(vscode, root, new AbortController().signal, () => {}), /No VS Code language model/);
  } finally { fs.rmSync(root, { recursive: true, force: true }); }
});
