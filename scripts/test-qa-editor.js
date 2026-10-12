// Exercises the shipped QA panel in an isolated VS Code against our blank fixture.
const fs = require('fs');
const path = require('path');
const net = require('net');
const { runTests } = require('@vscode/test-electron');
const root = path.resolve(__dirname, '..');
async function main() {
  if (!process.env.GRAILS_QA_EDITOR_FIXTURE) require('./prepare-qa-fixture');
  const server = net.createServer();
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const port = server.address().port;
  await new Promise(resolve => server.close(resolve));
  const artifacts = path.join(root, '.vscode-test/qa-editor-artifacts');
  fs.mkdirSync(artifacts, { recursive: true });
  const installed = process.platform === 'win32' ? path.join(process.env.LOCALAPPDATA, 'Programs/Microsoft VS Code/Code.exe') : undefined;
  await runTests({
    vscodeExecutablePath: process.env.VSCODE_EXECUTABLE || (installed && fs.existsSync(installed) ? installed : undefined),
    extensionDevelopmentPath: root, extensionTestsPath: path.join(root, 'test/qa-editor-suite.js'),
    extensionTestsEnv: { GRAILS_TEST_CDP_PORT: String(port), GRAILS_TEST_ARTIFACTS: artifacts, QA_EDITOR_GREP: process.env.QA_EDITOR_GREP || '', QA_EDITOR_MULTI: process.env.QA_EDITOR_MULTI || '' },
    launchArgs: [process.env.GRAILS_QA_EDITOR_FIXTURE || path.join(root, '.vscode-test/qa-fixture'), '--user-data-dir=' + path.join(root, '.vscode-test/qa-profile'),
      '--extensions-dir=' + path.join(root, '.vscode-test/extensions'), '--disable-extensions', '--disable-workspace-trust',
      '--skip-welcome', '--skip-release-notes', '--remote-debugging-port=' + port]
  });
}
main().catch(error => { console.error(error); process.exitCode = 1; });
