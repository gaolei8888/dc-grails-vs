/* global acquireVsCodeApi, document, window, Option */
const vscode = acquireVsCodeApi();
const el = id => document.getElementById(id);
let security = 'none-detected';
let initialized = false;
let userOffset = 0;
let identityLoaded = false;
function modes() {
  const local = el('target').value !== 'existing';
  el('url-row').classList.toggle('hidden', local);
  el('bypass').disabled = !local || security !== 'grails-spring-security';
  if (el('auth').value === 'bypass' && el('bypass').disabled) el('auth').value = 'credentials';
  el('credentials').classList.toggle('hidden', el('auth').value !== 'credentials');
  el('identity-picker').classList.toggle('hidden', el('auth').value !== 'bypass');
  for (const id of ['password-row', 'remember-row', 'forget', 'credential-note']) el(id).classList.toggle('hidden', el('auth').value !== 'credentials');
  el('auth-note').textContent = el('auth').value === 'bypass'
    ? 'Load the test database identities below, then select a user and tenant when applicable. No password is needed. Authentication and authorization are not tested.'
    : `Security detection: ${security}. Credential mode exercises the real login page.`;
}
function clearIdentities() {
  identityLoaded = false;
  el('cancel').disabled = true;
  el('tenant').replaceChildren(new Option('Select a tenant', ''));
  el('tenant-row').classList.add('hidden');
  el('test-user').replaceChildren(new Option('Load and select a test user', ''));
  el('user-search').value = '';
  el('identity-status').textContent = 'Load the list, then select an existing user. The username does not need to contain “test”.';
  el('more-identities').disabled = true; el('more-identities').dataset.hasMore = 'false'; userOffset = 0;
}
for (const id of ['target', 'auth']) el(id).addEventListener('change', () => {
  vscode.postMessage({ type: 'resetIdentity' }); clearIdentities(); modes();
});
function loadIdentities(refresh = false, offset = 0) {
  if (!offset) el('test-user').replaceChildren(new Option('Select a test user', ''));
  el('identity-status').textContent = 'Loading users…';
  vscode.postMessage({ type: 'identities', target: el('target').value, auth: el('auth').value,
    tenant: refresh ? '' : el('tenant').value, query: el('user-search').value, offset, refresh });
}
el('load-identities').addEventListener('click', () => { clearIdentities(); loadIdentities(true); });
el('tenant').addEventListener('change', () => { el('user-search').value = ''; loadIdentities(); });
el('search-identities').addEventListener('click', () => loadIdentities());
el('clear-user-search').addEventListener('click', () => { el('user-search').value = ''; loadIdentities(); });
el('user-search').addEventListener('keydown', event => { if (event.key === 'Enter') { event.preventDefault(); loadIdentities(); } });
el('more-identities').addEventListener('click', () => loadIdentities(false, userOffset));
for (const id of ['ai-plan', 'apply-ai', 'plan', 'run', 'cancel', 'config', 'report', 'forget']) el(id).addEventListener('click', () => {
  vscode.postMessage({ type: id, target: el('target').value, baseURL: el('baseURL').value, auth: el('auth').value,
    username: el('username').value, selectedUser: el('test-user').value, tenant: el('tenant').value,
    password: el('password').value, remember: el('remember').checked, showBrowser: el('show-browser').checked });
  if (id === 'run') el('password').value = '';
});
function renderReport(report) {
  if (!report) return;
  el('results').classList.remove('hidden'); el('result-title').textContent = `QA ${report.status}`;
  el('counts').replaceChildren();
  for (const [name, result] of [['Domain / H2', report.domain], ['Browser UI', report.ui]]) {
    const row = document.createElement('tr');
    for (const value of [name, result?.tests || 0, (result?.failures || 0) + (result?.errors || 0) + (result?.failed || 0), result?.skipped || 0]) {
      const cell = document.createElement('td'); cell.textContent = String(value); row.append(cell);
    } el('counts').append(row);
  }
  el('gaps').replaceChildren();
  el('cases').replaceChildren();
  for (const result of [...(report.domain?.cases || []), ...(report.ui?.cases || [])]) {
    const row = document.createElement('tr');
    for (const value of [result.name, result.useCase || 'Description unavailable in this older report. Run QA again.', result.status]) {
      const cell = document.createElement('td'); cell.textContent = value; row.append(cell);
    }
    el('cases').append(row);
  }
  const issues = [...(report.gaps || []).map(g => [g.domain, g.property, g.page, g.file, g.reason].filter(Boolean).join(' — ')),
    ...(report.error ? [report.error] : []), ...[...(report.domain?.cases || []), ...(report.ui?.cases || [])].filter(c => c.status !== 'passed').map(c => c.name + ': ' + c.detail)];
  for (const issue of issues.length ? issues : ['No failures or recorded coverage gaps in the planned tests.']) {
    const item = document.createElement('li'); item.textContent = issue; el('gaps').append(item);
  }
}
window.addEventListener('message', event => {
  const message = event.data;
  if (message.type === 'identityReset') clearIdentities();
  else if (message.type === 'identities') {
    identityLoaded = true;
    el('tenant-row').classList.toggle('hidden', !message.catalog.multiTenant);
    el('tenant').replaceChildren(new Option('Select a tenant', ''), ...message.catalog.tenants.map(id => new Option(id, id)));
    el('tenant').value = message.tenant;
    if (!message.offset) el('test-user').replaceChildren(new Option('Select a test user', ''));
    for (const name of message.catalog.users) el('test-user').append(new Option(name, name));
    userOffset = message.offset + message.catalog.users.length;
    el('identity-status').textContent = message.catalog.multiTenant && !message.tenant
      ? (message.catalog.tenants.length ? 'Select a tenant above to load its users.' : 'No tenants found in this test database.')
      : message.catalog.users.length ? `${userOffset} user(s) shown${message.query ? ' matching “' + message.query + '”' : ''}. Choose a user above.`
        : message.query ? `No usernames match “${message.query}”. Clear the filter to show all available users.`
          : 'No available users in the selected tenant / test database.';
    (message.catalog.multiTenant && !message.tenant ? el('tenant-row') : el('test-user')).scrollIntoView({ block: 'center' });
    el('more-identities').dataset.hasMore = String(message.catalog.hasMore);
    el('more-identities').disabled = !message.catalog.hasMore;
  } else if (message.type === 'state') {
    security = message.project.security;
    el('project').textContent = `${message.name} · ${message.project.domains.length} Domain classes · ${message.project.views.length} GSP pages`;
    el('plan-state').textContent = message.hasPlan ? 'A saved plan exists. Run QA reuses its expectations; Generate replaces them.' : 'No plan yet. The first run will generate one.';
    if (!initialized) {
      initialized = true;
      el('show-browser').checked = message.config.ui?.headless !== true;
      el('baseURL').value = message.config.ui?.baseURL || '';
      if (message.config.ui?.launch === false || message.config.ui?.baseURL) el('target').value = 'existing';
      else el('target').value = message.config.ui?.database === 'h2' ? 'isolated' : 'project';
      el('auth').value = message.config.auth?.mode || (security !== 'none-detected' ? 'credentials' : 'none');
    }
    modes(); renderReport(message.report);
  } else if (message.type === 'notice') { el('notice').textContent = message.text; el('notice').classList.toggle('error', !!message.error); }
  else if (message.type === 'busy') {
    for (const id of ['ai-plan', 'apply-ai', 'show-browser', 'plan', 'run', 'target', 'auth', 'config', 'forget', 'load-identities', 'search-identities', 'clear-user-search', 'tenant', 'test-user', 'user-search']) el(id).disabled = message.value;
    el('more-identities').disabled = message.value || el('more-identities').dataset.hasMore !== 'true';
    el('cancel').disabled = !message.value && !identityLoaded;
    if (message.value) el('notice').textContent = 'QA is running. See Grails QA output for progress.';
  }
});
vscode.postMessage({ type: 'ready' });
