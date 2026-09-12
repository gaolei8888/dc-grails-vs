const assert = require('assert/strict');
const fs = require('fs');
const os = require('os');
const path = require('path');
const http = require('http');
const { runUi } = require(process.env.GRAILS_QA_ENGINE ? path.join(path.dirname(process.env.GRAILS_QA_ENGINE), 'ui') : '../qa/ui');
async function main() {
  let externalRequests = 0;
  const external = http.createServer((_, response) => { externalRequests++; response.end('external'); });
  await new Promise(resolve => external.listen(0, '127.0.0.1', resolve));
  const externalURL = `http://127.0.0.1:${external.address().port}`;
  const server = http.createServer((_, response) => {
    response.setHeader('Content-Type', 'text/html');
    response.end(`<input id="query"><select id="category"><option value="all">All</option></select><input id="enabled" type="checkbox"><button id="search" onclick="document.querySelector('#result').textContent=document.querySelector('#query').value">Search</button><p id="result"></p><a id="external" href="${externalURL}">External</a>`);
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'qa-ai-browser-'));
  try {
    const result = await runUi({ domains: [], pages: [], browserCases: [
      { name: 'Search flow', useCase: 'Search returns entered query.', path: '/', steps: [
        { action: 'fill', selector: '#query', value: 'sample' }, { action: 'select', selector: '#category', value: 'all' },
        { action: 'check', selector: '#enabled', value: true }, { action: 'click', selector: '#search' },
        { action: 'text', selector: '#result', value: 'sample' }, { action: 'url', value: '/' }] },
      { name: 'Wrong result', useCase: 'Incorrect expectations must fail.', path: '/', steps: [{ action: 'text', selector: '#search', value: 'missing' }] },
      { name: 'External navigation', useCase: 'External navigation is blocked.', path: '/', steps: [{ action: 'click', selector: '#external' }, { action: 'visible', selector: '#result' }] }
    ] }, { baseURL: `http://127.0.0.1:${server.address().port}`, directory, headless: false, timeout: 1000, onOutput: text => process.stdout.write(text) });
    assert.equal(result.tests, 3); assert.equal(result.failed, 2);
    assert.equal(result.cases[0].status, 'passed');
    assert.equal(result.cases[0].steps.length, 7);
    assert(fs.existsSync(result.cases[0].screenshot));
    assert.equal(externalRequests, 0);
    console.log('Visible local browser: actions, assertions, failure evidence and external navigation guard passed.');
  } finally { await new Promise(resolve => server.close(resolve)); await new Promise(resolve => external.close(resolve)); }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
