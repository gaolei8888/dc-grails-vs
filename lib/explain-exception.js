const { collectExceptionContext, promptFor } = require('./exception-context');

function registerExceptionExplainer(vscode, context) {
  const stops = new Map();
  const running = new Map();
  const documents = new Map();
  const changed = new vscode.EventEmitter();
  let serial = 0;
  const status = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, 49);
  status.text = '$(sparkle) Explain Exception';
  status.command = 'grails.explainException';
  status.tooltip = 'Explain using a model: sends the exception, application stack and nearby source lines. No variable values.';
  function refresh() {
    const session = vscode.debug.activeDebugSession;
    const available = !!(session && stops.has(session.id));
    void vscode.commands.executeCommand('setContext', 'grails:exceptionStopped', available);
    if (available) status.show(); else status.hide();
  }
  function clear(session) {
    stops.delete(session.id);
    running.get(session.id)?.cancel();
    refresh();
  }
  async function showDocument(text) {
    const uri = vscode.Uri.parse(`grails-exception:/exception-${++serial}.md`);
    documents.set(uri.toString(), text);
    const document = await vscode.workspace.openTextDocument(uri);
    await vscode.window.showTextDocument(document, { preview: false, viewColumn: vscode.ViewColumn.Beside });
    return uri;
  }
  const update = (uri, text) => { documents.set(uri.toString(), text); changed.fire(uri); };
  const snapshotText = snapshot => '# Exception context\n\n' + snapshot.note + '\n\n```json\n' +
    JSON.stringify(snapshot, null, 2).replace(/```/g, '\\u0060\\u0060\\u0060') + '\n```\n';

  async function explain(contextOnly = false) {
    const session = vscode.debug.activeDebugSession;
    const stop = session && stops.get(session.id);
    if (!stop) { await vscode.window.showInformationMessage('Pause on a Groovy exception first. Enable caught or uncaught exceptions in the Breakpoints pane.'); return; }
    if (running.has(session.id)) { await vscode.window.showInformationMessage('An exception explanation is already in progress.'); return; }
    const cancellation = new vscode.CancellationTokenSource();
    running.set(session.id, cancellation);
    const current = () => stops.get(session.id) === stop && !cancellation.token.isCancellationRequested;
    let uri;
    let rendered = '';
    try {
      const snapshot = await collectExceptionContext(vscode, session, stop, current);
      if (contextOnly) return await showDocument(snapshotText(snapshot));
      if (!vscode.lm?.selectChatModels) {
        return await showDocument('AI explanation requires VS Code 1.90 or later and an available language model.\n\n' + snapshotText(snapshot));
      }
      // Consent may be requested here, so this runs only inside a user command.
      const models = await vscode.lm.selectChatModels({});
      if (!current()) return;
      if (!models.length) {
        return await showDocument('No language model is available. Configure a language model provider in VS Code, then run Grails: Explain Exception again.\n\n' + snapshotText(snapshot));
      }
      const selected = await vscode.window.showQuickPick(models.map(model => ({
        label: model.name, description: `${model.vendor} · ${model.family}`, detail: model.id, model
      })), { title: 'Explain exception with a model',
        placeHolder: 'Sends exception, application stack and nearby source. No variable values.', ignoreFocusOut: true }, cancellation.token);
      if (!selected || !current()) return;
      await vscode.window.withProgress({ location: vscode.ProgressLocation.Notification, title: 'Explaining Groovy exception', cancellable: true }, async (_progress, token) => {
        const cancel = token.onCancellationRequested(() => cancellation.cancel());
        try {
          if (token.isCancellationRequested) cancellation.cancel();
          const model = selected.model;
          let prompt = promptFor(snapshot, vscode.env.language);
          let message = vscode.LanguageModelChatMessage.User(prompt);
          const budget = Math.floor(model.maxInputTokens * 0.8);
          // Preserve exception metadata; shed source and deep frames first.
          while (await model.countTokens(message, cancellation.token) > budget) {
            if (snapshot.snippets.length) snapshot.snippets.pop();
            else if (snapshot.frames.length) snapshot.frames.pop();
            else throw new Error('This model has too little input capacity for the exception context. Select another model.');
            prompt = promptFor(snapshot, vscode.env.language);
            message = vscode.LanguageModelChatMessage.User(prompt);
          }
          if (!current()) return;
          rendered = `# Exception explanation\n\nModel: ${model.name}\n\n`;
          uri = await showDocument(rendered + 'Waiting for the model…\n');
          if (!current()) { update(uri, rendered + '\n[Cancelled: the debugger resumed or the request was cancelled.]\n'); return; }
          const response = await model.sendRequest([message], {}, cancellation.token);
          let lastUpdate = 0;
          for await (const fragment of response.text) {
            if (!current()) break;
            rendered += fragment;
            if (rendered.length > 64000) { cancellation.cancel(); rendered = rendered.slice(0, 64000) + '\n\n[Output limit reached.]'; break; }
            if (Date.now() - lastUpdate > 100) { update(uri, rendered); lastUpdate = Date.now(); }
          }
          const ending = cancellation.token.isCancellationRequested ? '\n\n[Cancelled: the request was cancelled or the debugger resumed.]\n' : '\n';
          update(uri, rendered + ending + '\n---\n\n' + snapshotText(snapshot));
        } finally { cancel.dispose(); }
      });
      return uri;
    } catch (error) {
      const detail = cancellation.token.isCancellationRequested ? 'Explanation cancelled because the request was cancelled or the debugger resumed.' : `Could not explain the exception: ${error.message}`;
      if (uri) update(uri, rendered + '\n\n' + detail);
      else if (!cancellation.token.isCancellationRequested) await vscode.window.showErrorMessage(detail);
    } finally {
      running.delete(session.id);
      cancellation.dispose();
    }
  }
  context.subscriptions.push(status, changed,
    vscode.workspace.registerTextDocumentContentProvider('grails-exception', { onDidChange: changed.event,
      provideTextDocumentContent: uri => documents.get(uri.toString()) || '' }),
    vscode.workspace.onDidCloseTextDocument(document => { if (document.uri.scheme === 'grails-exception') documents.delete(document.uri.toString()); }),
    vscode.debug.registerDebugAdapterTrackerFactory('groovy', {
      createDebugAdapterTracker(session) { return {
        onDidSendMessage(message) {
          if (message.type !== 'event') return;
          if (message.event === 'stopped') {
            clear(session);
            if (message.body.reason === 'exception' && Number.isInteger(message.body.threadId)) stops.set(session.id, message.body);
            refresh();
          } else if (['continued', 'terminated', 'exited'].includes(message.event)) clear(session);
        },
        onWillReceiveMessage(message) {
          if (message.type === 'request' && ['continue', 'next', 'stepIn', 'stepOut', 'restart', 'disconnect', 'terminate'].includes(message.command)) clear(session);
        }, onExit() { clear(session); }, onError() { clear(session); }
      }; }
    }),
    vscode.debug.onDidChangeActiveDebugSession(refresh), vscode.debug.onDidTerminateDebugSession(clear),
    vscode.commands.registerCommand('grails.explainException', () => explain()),
    vscode.commands.registerCommand('grails.showExceptionContext', () => explain(true)),
    { dispose() { for (const cancellation of running.values()) cancellation.cancel(); stops.clear(); documents.clear(); } }
  );
  refresh();
}

module.exports = { registerExceptionExplainer };
