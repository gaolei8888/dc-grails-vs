const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { scanProject, readConfig } = require('../qa/project');
const { planProject, runProject, openIdentitySession, applyAiDraft } = require('../qa/engine');
const { generateAiDraft } = require('./qa-ai');

function credentialKey(root, target) {
  return 'grails.qa.credentials.' + crypto.createHash('sha256').update(root + '\n' + target).digest('hex');
}
function registerQaPanel(vscode, context) {
  let panel, controller;
  context.subscriptions.push(vscode.commands.registerCommand('grails.qa', async () => {
    const folders = vscode.workspace.workspaceFolders;
    if (!folders?.length) { await vscode.window.showErrorMessage('Open a Grails application first.'); return; }
    if (!vscode.workspace.isTrusted) { await vscode.window.showErrorMessage('Trust this workspace before running its QA tests.'); return; }
    if (panel) { panel.reveal(); return; }
    let folder = folders[0];
    if (folders.length > 1) {
      const selected = await vscode.window.showQuickPick(folders.map(f => ({ label: f.name, folder: f })), { placeHolder: 'Choose the Grails application to test' });
      if (!selected) return; folder = selected.folder;
    }
    let project;
    try { project = scanProject(folder.uri.fsPath); }
    catch (error) { await vscode.window.showErrorMessage(error.message); return; }
    const root = project.root;
    const output = vscode.window.createOutputChannel('Grails QA');
    const current = vscode.window.createWebviewPanel('grailsQa', 'Grails QA', vscode.ViewColumn.One,
      { enableScripts: true, localResourceRoots: [vscode.Uri.joinPath(context.extensionUri, 'media')] });
    panel = current;
    const nonce = crypto.randomBytes(24).toString('base64');
    current.webview.html = fs.readFileSync(path.join(context.extensionPath, 'media/qa.html'), 'utf8')
      .replaceAll('{{nonce}}', nonce).replaceAll('{{csp}}', current.webview.cspSource)
      .replaceAll('{{script}}', current.webview.asWebviewUri(vscode.Uri.joinPath(context.extensionUri, 'media/qa.js')).toString());
    const send = message => current.webview.postMessage(message);
    async function state() {
      project = scanProject(root);
      const config = readConfig(root);
      let report;
      const reportPath = path.join(root, '.grails-qa/report.json');
      if (fs.existsSync(reportPath)) report = JSON.parse(fs.readFileSync(reportPath, 'utf8'));
      await send({ type: 'state', name: folder.name, project, config, report,
        hasPlan: fs.existsSync(path.join(root, '.grails-qa/plan.json')) });
    }
    let localController, identitySession, identityTarget;
    const resetIdentity = () => { identitySession?.stop(); identitySession = undefined; identityTarget = undefined; };
    current.onDidDispose(() => { localController?.abort(); resetIdentity(); if (panel === current) panel = undefined; output.dispose(); });
    current.webview.onDidReceiveMessage(async message => {
      try {
        if (!message || typeof message.type !== 'string') return;
        if (message.type === 'ready') return await state();
        if (message.type === 'cancel') { controller?.abort(); resetIdentity(); await send({ type: 'identityReset' }); return; }
        if (message.type === 'resetIdentity') { controller?.abort(); resetIdentity(); return; }
        if (message.type === 'ai-plan' || message.type === 'apply-ai') {
          if (controller) throw new Error('A QA operation is already running.');
          resetIdentity(); await send({ type: 'identityReset' });
          controller = new AbortController(); localController = controller;
          await send({ type: 'busy', value: true }); output.show(true);
          try {
            const draftPath = path.join(root, '.grails-qa/ai-draft.json');
            if (message.type === 'ai-plan') {
              const draft = await generateAiDraft(vscode, root, controller.signal, text => output.append(text));
              if (!draft) { await send({ type: 'notice', text: 'Model selection cancelled. Existing plan is unchanged.' }); return; }
              fs.mkdirSync(path.dirname(draftPath), { recursive: true });
              fs.writeFileSync(draftPath, JSON.stringify(draft, null, 2));
              await vscode.window.showTextDocument(await vscode.workspace.openTextDocument(vscode.Uri.file(draftPath)), { viewColumn: vscode.ViewColumn.Beside });
              await send({ type: 'notice', text: `AI drafted ${draft.cases.length} browser cases. Review or edit the draft, save it, then click Use AI draft. Run QA executes the saved plan.` });
            } else {
              if (!fs.existsSync(draftPath)) throw new Error('Generate an AI draft first.');
              if (vscode.workspace.textDocuments.some(d => d.uri.fsPath === draftPath && d.isDirty)) throw new Error('Save your draft edits before applying them.');
              const draft = JSON.parse(fs.readFileSync(draftPath, 'utf8'));
              if (!fs.existsSync(path.join(root, '.grails-qa/plan.json'))) await planProject(root, { signal: controller.signal, onOutput: text => output.append(text) });
              if (controller.signal.aborted) throw new Error('QA operation cancelled.');
              await applyAiDraft(root, draft);
              await state();
              await send({ type: 'notice', text: 'AI browser cases saved to the test plan. Select the test identity if needed, then Run QA.' });
            }
          } finally { controller = undefined; localController = undefined; await send({ type: 'busy', value: false }); }
          return;
        }
        if (message.type === 'identities') {
          if (controller) throw new Error('A QA operation is already running.');
          if (!['project', 'isolated'].includes(message.target) || message.auth !== 'bypass') throw new Error('Test identity discovery requires local Skip authentication mode.');
          controller = new AbortController(); localController = controller;
          await send({ type: 'busy', value: true });
          output.show(true);
          try {
            if (message.refresh || identityTarget !== message.target) resetIdentity();
            if (!identitySession) {
              identitySession = await openIdentitySession(root, { signal: controller.signal, onOutput: text => output.append(text),
                ui: { database: message.target === 'isolated' ? 'h2' : 'project' } });
              identityTarget = message.target;
            }
            const catalog = await identitySession.catalog(String(message.tenant || ''), String(message.query || ''), Number(message.offset || 0));
            await send({ type: 'identities', catalog, tenant: message.tenant || '', query: message.query || '', offset: Number(message.offset || 0) });
            await send({ type: 'notice', text: catalog.multiTenant && !message.tenant
              ? (catalog.tenants.length ? 'Select a test tenant to load its users.' : 'No test tenants found. Prepare test data, then reload.')
              : catalog.users.length ? 'Select a user from the test database, then Run QA. No password is needed.'
                : message.query ? 'No usernames match the filter. Click Clear filter to show all available users.' : 'No available users found in this tenant / test database.' });
          } catch (error) { output.appendLine('Test identity discovery failed: ' + error.message); resetIdentity(); await send({ type: 'identityReset' }); throw error; }
          finally { controller = undefined; await send({ type: 'busy', value: false }); }
          return;
        }
        if (message.type === 'report' || message.type === 'config') {
          const file = path.join(root, '.grails-qa', message.type === 'report' ? 'report.json' : 'config.json');
          if (!fs.existsSync(file)) {
            if (message.type === 'report') throw new Error('Run QA to create a report first.');
            fs.mkdirSync(path.dirname(file), { recursive: true });
            fs.writeFileSync(file, JSON.stringify({ ui: { launch: true, pages: project.views.filter(v => v.path && v.routeEvidence.startsWith('literal')).map(v => ({ path: v.path, source: v.file, domain: v.domain, fields: v.fields, ...(v.hasForm ? { form: 'form' } : {}) })) }, auth: { mode: 'none', loginPath: '/login/auth' }, fixtures: {} }, null, 2));
          }
          await vscode.window.showTextDocument(await vscode.workspace.openTextDocument(vscode.Uri.file(file))); return;
        }
        if (!['plan', 'run', 'forget'].includes(message.type)) return;
        if (!['project', 'isolated', 'existing'].includes(message.target)) throw new Error('Choose a QA application target.');
        const launch = message.target !== 'existing';
        const database = message.target === 'isolated' ? 'h2' : 'project';
        const target = launch ? (database === 'h2' ? 'isolated-local-qa' : 'project-test-local-qa') : new URL(String(message.baseURL)).origin;
        if (!launch && !/^https?:\/\//.test(target)) throw new Error('Provide an HTTP(S) QA server URL.');
        const key = credentialKey(root, target);
        if (message.type === 'forget') { await context.secrets.delete(key); await send({ type: 'notice', text: 'Saved credentials removed for this target.' }); return; }
        if (controller) throw new Error('A QA operation is already running.');
        const mode = String(message.auth || 'none');
        if (!['none', 'credentials', 'bypass'].includes(mode)) throw new Error('Choose an authentication mode.');
        if (mode === 'bypass' && !launch) throw new Error('Skipping authentication is only available for a locally started QA instance.');
        let credentials = {};
        if (message.type === 'run' && mode === 'bypass') {
          if (!identitySession || identityTarget !== message.target || typeof message.selectedUser !== 'string' || !message.selectedUser) throw new Error('Load test users and select an identity before running QA.');
          credentials.username = message.selectedUser;
        }
        if (message.type === 'run' && mode === 'credentials') {
          if (message.password) {
            if (typeof message.username !== 'string' || typeof message.password !== 'string') throw new Error('Invalid credentials.');
            credentials = { username: message.username, password: message.password };
            if (message.remember === true) await context.secrets.store(key, JSON.stringify(credentials));
          } else {
            const saved = await context.secrets.get(key);
            if (saved) credentials = JSON.parse(saved);
          }
          if (!credentials.username || !credentials.password) throw new Error('Enter a test username/password, or use credentials saved for this target.');
        }
        if (mode !== 'bypass' || message.type === 'plan') resetIdentity();
        if (controller) throw new Error('A QA operation is already running.');
        controller = new AbortController();
        localController = controller;
        await send({ type: 'busy', value: true });
        output.clear(); output.show(true);
        const options = { ...credentials, signal: controller.signal, onOutput: text => output.append(text),
          ui: { launch, database, headless: message.showBrowser === false, baseURL: launch ? undefined : String(message.baseURL) }, auth: { mode } };
        try {
          if (message.type === 'plan') await planProject(root, options);
          else if (mode === 'bypass') {
            try { await identitySession.run({ ...options, tenant: String(message.tenant || '') }); }
            finally { resetIdentity(); await send({ type: 'identityReset' }); }
          } else await runProject(root, options);
          await state();
          await send({ type: 'notice', text: message.type === 'plan' ? 'Frozen test plan saved. Review it before using it as a regression baseline.' : 'QA finished. Results and coverage gaps are below.' });
        } finally { controller = undefined; localController = undefined; credentials = {}; await send({ type: 'busy', value: false }); }
      } catch (error) { await send({ type: 'notice', error: true, text: error.message }); }
    });
  }), { dispose() { controller?.abort(); panel?.dispose(); } });
}
module.exports = { registerQaPanel, credentialKey };
