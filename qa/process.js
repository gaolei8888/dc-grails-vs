const { spawn, spawnSync } = require('child_process');
const fs = require('fs');
const path = require('path');
const { stripVTControlCharacters } = require('util');

function redactor(secrets = []) {
  const values = secrets.filter(Boolean).flatMap(value => [String(value), encodeURIComponent(String(value))]);
  return text => values.reduce((s, value) => s.split(value).join('[redacted]'), stripVTControlCharacters(String(text)));
}
function stopTree(child) {
  if (!child?.pid || child.exitCode !== null) return;
  if (process.platform === 'win32') spawnSync('taskkill', ['/PID', String(child.pid), '/T', '/F'], { windowsHide: true, stdio: 'ignore' });
  else { try { process.kill(-child.pid, 'SIGTERM'); } catch { /* already gone */ } }
}
function startGradle(root, args, { onOutput = () => {}, signal, logFile, secrets = [], env = {} } = {}) {
  // VS Code may disable implicit current-directory executable lookup on Windows.
  const wrapper = process.platform === 'win32' ? '.\\gradlew.bat' : './gradlew';
  if (!fs.existsSync(path.join(root, wrapper))) throw new Error('Gradle wrapper is missing.');
  // All Windows arguments originate from our engine, not user command text.
  // Quoting preserves spaces; reject shell metacharacters cmd could execute.
  for (const arg of args) if (/["\r\n&|<>^%!]/.test(arg)) throw new Error('Unsupported shell character in QA path/argument.');
  const child = process.platform === 'win32'
    ? spawn('cmd.exe', ['/d', '/s', '/c', [wrapper, ...args.map(a => `"${a}"`)].join(' ')], { cwd: root, windowsHide: true, windowsVerbatimArguments: true, env: { ...process.env, ...env } })
    : spawn(wrapper, args, { cwd: root, detached: true, env: { ...process.env, ...env } });
  const redact = redactor(secrets);
  const output = logFile ? fs.createWriteStream(logFile) : null;
  let text = '';
  let pending = '';
  function consume(data) {
    pending += data.toString();
    const lines = pending.split(/\r?\n/); pending = lines.pop();
    for (const line of lines) { const safe = redact(line) + '\n'; text += safe; output?.write(safe); onOutput(safe); }
  }
  child.stdout.on('data', consume); child.stderr.on('data', consume);
  const abort = () => stopTree(child);
  signal?.addEventListener('abort', abort, { once: true });
  if (signal?.aborted) abort();
  const done = new Promise((resolve, reject) => {
    child.on('error', reject);
    child.on('close', code => {
      if (pending) { const safe = redact(pending); text += safe; output?.write(safe); onOutput(safe); }
      output?.end(); signal?.removeEventListener('abort', abort);
      resolve({ code, text });
    });
  });
  return { child, done, stop: abort, get output() { return text; } };
}
module.exports = { startGradle, stopTree, redactor };
