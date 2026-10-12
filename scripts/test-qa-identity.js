const assert = require('assert/strict');
const fs = require('fs');
const path = require('path');
const http = require('http');
const enginePath = process.env.GRAILS_QA_ENGINE || path.resolve(__dirname, '../qa/engine.js');
const { launchQaApp } = require(enginePath);
const { scanProject } = require(path.join(path.dirname(enginePath), 'project'));
const { runUi } = require(path.join(path.dirname(enginePath), 'ui'));
async function main() {
  const project = scanProject(process.env.GRAILS_QA_FIXTURE || path.resolve(__dirname, '../.vscode-test/qa-fixture'));
  const directory = fs.mkdtempSync(path.resolve(__dirname, '../.vscode-test/identity-guards-'));
  await assert.rejects(launchQaApp(project, { auth: { mode: 'bypass' } }, directory), /test username/);
  const app = await launchQaApp(project, { username: 'qa-tester', auth: { mode: 'bypass' } }, directory);
  try {
    const url = app.baseURL + '/qaRecord/create';
    assert.equal((await fetch(url)).status, 403);
    assert.equal((await fetch(url, { headers: { 'X-Grails-QA-Identity': 'wrong' } })).status, 403);
    const response = await fetch(url, { headers: { 'X-Grails-QA-Identity': app.identityToken } });
    assert.equal(response.status, 200);
    assert.equal(response.headers.get('X-QA-Current-User'), 'verified');
    assert.equal((await fetch(url)).status, 403, 'Identity must not persist into another request');
    assert(!fs.readFileSync(path.join(directory, 'application.log'), 'utf8').includes(app.identityToken));
  } finally { app.stop(); }
  // Verify the capability cannot follow redirects to a different origin.
  const received = [];
  const foreign = http.createServer((req, res) => { received.push(req.headers); res.end('<html>external</html>'); });
  await new Promise(resolve => foreign.listen(0, '127.0.0.1', resolve));
  const target = http.createServer((req, res) => {
    assert.equal(req.headers['x-grails-qa-identity'], 'test-capability');
    res.writeHead(302, { Location: `http://127.0.0.1:${foreign.address().port}/external` }); res.end();
  });
  await new Promise(resolve => target.listen(0, '127.0.0.1', resolve));
  try {
    const report = await runUi({ domains: [], pages: [{ path: '/' }] }, {
      baseURL: `http://127.0.0.1:${target.address().port}`, identityToken: 'test-capability', username: 'qa-tester', tenant: 'north', auth: { mode: 'bypass' }, directory
    });
    assert.equal(report.failed, 1, 'Off-origin navigation must fail');
    assert(received.length > 0);
    assert(received.every(headers => !headers['x-grails-qa-identity']));
    assert(received.every(headers => !headers['x-grails-qa-user'] && !headers['x-grails-qa-tenant']));
    assert(report.cases.every(c => c.useCase));
  } finally { await Promise.all([new Promise(resolve => target.close(resolve)), new Promise(resolve => foreign.close(resolve))]); }
  console.log('Verified currentUser + roles without a password, request isolation, capability guards and cross-origin redirect protection.');
}
main().catch(error => { console.error(error); process.exitCode = 1; });
