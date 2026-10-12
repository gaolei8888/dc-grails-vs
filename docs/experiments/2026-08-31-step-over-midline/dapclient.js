#!/usr/bin/env node
// Drives dist/groovy-dap.jar over stdio the way VSCode would, so a stepping
// sequence can be replayed without the editor.
//
//   node dapclient.js <appDir> <breakFile> <breakLine> <nextCount> [httpUrl]
//
// Attaches to a JVM already listening on 5005 (`gradlew bootRun --debug-jvm`),
// sets one breakpoint, hits it by requesting httpUrl, then sends `next` the
// given number of times and prints the top frame of every stop.
const { spawn } = require('child_process');
const path = require('path');
const http = require('http');

const [appDir, breakFile, breakLineArg, nextCountArg, httpUrl] = process.argv.slice(2);
const breakLine = Number(breakLineArg);
const nextCount = Number(nextCountArg || 6);
// GROOVY_DAP_JAR lets an older build of the adapter be driven through the same
// sequence, which is how the mid-line defect was pinned to a commit.
const jar = process.env.GROOVY_DAP_JAR
  || path.join(__dirname, '..', '..', '..', 'dist', 'groovy-dap.jar');

const java = spawn('java', ['--add-modules', 'jdk.jdi', '-jar', jar], {
  stdio: ['pipe', 'pipe', 'pipe'],
});
java.stderr.on('data', d => process.stderr.write('[dap-server] ' + d));

let seq = 1;
const pending = new Map();
function send(command, args) {
  const body = JSON.stringify({ seq: seq, type: 'request', command, arguments: args || {} });
  const message = 'Content-Length: ' + Buffer.byteLength(body) + '\r\n\r\n' + body;
  return new Promise(resolve => {
    pending.set(seq++, resolve);
    java.stdin.write(message);
  });
}

let buffer = Buffer.alloc(0);
const handlers = { initialized: [], stopped: [] };
java.stdout.on('data', chunk => {
  buffer = Buffer.concat([buffer, chunk]);
  for (;;) {
    const header = buffer.indexOf('\r\n\r\n');
    if (header < 0) { return; }
    const length = Number(/Content-Length: (\d+)/.exec(buffer.slice(0, header).toString())[1]);
    if (buffer.length < header + 4 + length) { return; }
    const message = JSON.parse(buffer.slice(header + 4, header + 4 + length).toString());
    buffer = buffer.slice(header + 4 + length);
    if (message.type === 'response') {
      const resolve = pending.get(message.request_seq);
      pending.delete(message.request_seq);
      if (resolve) { resolve(message); }
    } else if (message.type === 'event') {
      if (message.event === 'output') {
        process.stderr.write('[target] ' + (message.body.output || ''));
      } else if (handlers[message.event]) {
        const waiters = handlers[message.event].splice(0);
        waiters.forEach(w => w(message));
      }
    }
  }
});

const waitFor = event => new Promise(resolve => handlers[event].push(resolve));

async function topFrame(threadId) {
  const r = await send('stackTrace', { threadId, startFrame: 0, levels: 3 });
  return (r.body && r.body.stackFrames) || [];
}

(async () => {
  await send('initialize', { adapterID: 'groovy', pathFormat: 'path' });
  const attach = send('attach', {
    port: 5005, sourcePaths: [appDir], trace: true,
  });
  await waitFor('initialized');
  const bp = await send('setBreakpoints', {
    source: { path: breakFile },
    breakpoints: [{ line: breakLine }],
  });
  console.log('breakpoints:', JSON.stringify(bp.body));
  await send('configurationDone');
  await attach;

  if (httpUrl) {
    // The application still has to finish booting after the attach resumes it,
    // so keep trying until it answers.
    let tries = 0;
    const poke = () => {
      http.get(httpUrl, res => {
        let body = '';
        res.on('data', d => { body += d; });
        res.on('end', () => console.log('[http] ' + res.statusCode + ' ' + body.trim()));
      }).on('error', () => {
        if (++tries < 40) { setTimeout(poke, 3000); }
        else { console.log('[http] never answered'); }
      });
    };
    setTimeout(poke, 3000);
  }

  let stop = await waitFor('stopped');
  let frames = await topFrame(stop.body.threadId);
  console.log('STOP(%s) %s', stop.body.reason, describe(frames));

  for (let i = 0; i < nextCount; i++) {
    const waiting = waitFor('stopped');
    await send('next', { threadId: stop.body.threadId });
    stop = await waiting;
    frames = await topFrame(stop.body.threadId);
    console.log('next #%d -> STOP(%s) %s', i + 1, stop.body.reason, describe(frames));
  }
  await send('disconnect', {});
  setTimeout(() => process.exit(0), 500);
})().catch(e => { console.error(e); process.exit(1); });

function describe(frames) {
  return frames.map(f => {
    const src = f.source && f.source.name ? f.source.name : '?';
    return `${f.name} (${src}:${f.line})`;
  }).join('  <-  ');
}
