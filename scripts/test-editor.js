// Isolated VS Code + standalone Grails testbed; never uses a business application.
const fs = require('fs');
const path = require('path');
const net = require('net');
const { spawn, spawnSync } = require('child_process');
const { runTests } = require('@vscode/test-electron');
const root = path.resolve(__dirname, '..');
const target = path.resolve(process.env.GRAILS_TESTBED || path.join(root, '..', 'grails-dap-testbed', 'dapspike'));
const artifacts = path.join(root, '.vscode-test', 'artifacts');
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
async function freePort() {
  const server = net.createServer();
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const port = server.address().port;
  await new Promise(resolve => server.close(resolve));
  return port;
}
async function main() {
  if (!fs.existsSync(path.join(target, 'grails-app', 'services', 'dapspike', 'SpikeService.groovy'))) {
    throw new Error('Set GRAILS_TESTBED to the standalone dapspike Grails application (see docs/testing.md).');
  }
  fs.mkdirSync(artifacts, { recursive: true });
  const debugPort = await freePort(), httpPort = await freePort(), cdpPort = await freePort();
  const env = { ...process.env };
  if (process.platform === 'win32' && (!env.JAVA_HOME || !fs.existsSync(path.join(env.JAVA_HOME, 'bin', 'java.exe')))) {
    const java = spawnSync('where.exe', ['java'], { encoding: 'utf8' }).stdout.trim().split(/\r?\n/)[0];
    if (java) env.JAVA_HOME = path.dirname(path.dirname(java));
  }
  const args = ['bootRun', `-PdbgPort=${debugPort}`, `-PappPort=${httpPort}`, '--no-daemon'];
  const child = process.platform === 'win32'
    ? spawn('cmd.exe', ['/d', '/s', '/c', 'gradlew.bat', ...args], { cwd: target, env, windowsHide: true })
    : spawn('./gradlew', args, { cwd: target, env, detached: true });
  let log = '', launchError;
  child.on('error', error => { launchError = error; });
  const output = fs.createWriteStream(path.join(artifacts, 'grails.log'));
  for (const stream of [child.stdout, child.stderr]) stream.on('data', data => { log += data; output.write(data); });
  try {
    const deadline = Date.now() + 300000;
    while (!log.includes('Listening for transport dt_socket')) {
      if (launchError) throw launchError;
      if (child.exitCode !== null || Date.now() > deadline) throw new Error('Grails did not open JDWP:\n' + log.slice(-4000));
      await delay(500);
    }
    console.log(`Test target listening: JDWP ${debugPort}, HTTP ${httpPort}`);
    const installed = process.platform === 'win32' ? path.join(process.env.LOCALAPPDATA, 'Programs', 'Microsoft VS Code', 'Code.exe') : undefined;
    await runTests({
      vscodeExecutablePath: process.env.VSCODE_EXECUTABLE || (installed && fs.existsSync(installed) ? installed : undefined),
      extensionDevelopmentPath: root, extensionTestsPath: path.join(root, 'test', 'suite.js'),
      extensionTestsEnv: { GRAILS_TESTBED: target, GRAILS_TEST_DEBUG_PORT: String(debugPort),
        GRAILS_TEST_HTTP_PORT: String(httpPort), GRAILS_TEST_CDP_PORT: String(cdpPort), GRAILS_TEST_ARTIFACTS: artifacts },
      launchArgs: [target, '--user-data-dir=' + path.join(root, '.vscode-test', 'profile'),
        '--extensions-dir=' + path.join(root, '.vscode-test', 'extensions'), '--disable-extensions',
        '--disable-workspace-trust', '--skip-welcome', '--skip-release-notes', '--remote-debugging-port=' + cdpPort]
    });
  } finally {
    if (child.pid && child.exitCode === null) {
      if (process.platform === 'win32') spawnSync('taskkill', ['/PID', String(child.pid), '/T', '/F'], { windowsHide: true });
      else { try { process.kill(-child.pid, 'SIGTERM'); } catch { /* already exited */ } }
    }
    output.end();
  }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
