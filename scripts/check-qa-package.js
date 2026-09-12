// Pass the extracted VSIX's extension directory outside this repository.
const assert = require('assert');
const path = require('path');
const fs = require('fs');
const http = require('http');
async function main() {
  const extension = path.resolve(process.argv[2]);
  assert.throws(() => require.resolve('playwright-core', { paths: [extension] }), 'smoke directory must not resolve development dependencies');
  const { runUi } = require(path.join(extension, 'qa/ui.js'));
  const server = http.createServer((_, response) => { response.setHeader('Content-Type', 'text/html'); response.end('<html><body><h1 id="ready">Packaged QA works</h1></body></html>'); });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  try {
    const directory = path.join(extension, '../smoke'); fs.mkdirSync(directory, { recursive: true });
    const result = await runUi({ domains: [], pages: [{ path: '/', selector: '#ready' }] }, {
      baseURL: 'http://127.0.0.1:' + server.address().port, directory
    });
    assert.equal(result.tests, 1); assert.equal(result.failed, 0);
    assert(fs.existsSync(result.cases[0].screenshot));
    console.log('Packaged QA driver launched Edge and passed a real page assertion without node_modules.');
  } finally { await new Promise(resolve => server.close(resolve)); }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
