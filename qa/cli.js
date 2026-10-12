#!/usr/bin/env node
const path = require('path');
const { scanProject } = require('./project');
const { planProject, runProject } = require('./engine');

async function main(args = process.argv.slice(2)) {
  const [command, directory, ...flags] = args;
  if (!['scan', 'plan', 'run'].includes(command) || !directory) throw new Error('Usage: node qa/cli.js scan|plan|run <Grails project> [--domain-only|--ui-only] [--launch] [--auth=credentials|bypass|none] [--base-url=http://...]');
  for (const flag of flags) if (!/^(--domain-only|--ui-only|--launch|--database=(project|h2)|--auth=(credentials|bypass|none)|--base-url=https?:\/\/\S+)$/.test(flag)) throw new Error('Unknown option: ' + flag);
  if (flags.includes('--domain-only') && flags.some(f => f === '--ui-only' || f === '--launch' || f.startsWith('--base-url='))) throw new Error('--domain-only cannot be combined with UI options.');
  if (flags.includes('--launch') && flags.some(f => f.startsWith('--base-url='))) throw new Error('Choose --launch or --base-url, not both.');
  const controller = new AbortController();
  const stop = () => controller.abort(); process.on('SIGINT', stop); process.on('SIGTERM', stop);
  try {
    const root = path.resolve(directory);
    const options = { signal: controller.signal, onOutput: text => process.stdout.write(text),
      username: process.env.GRAILS_QA_USERNAME, password: process.env.GRAILS_QA_PASSWORD };
    if (flags.includes('--domain-only')) options.ui = false;
    if (flags.includes('--ui-only')) options.domain = false;
    if (flags.includes('--launch')) options.ui = { launch: true };
    const database = flags.find(f => f.startsWith('--database='));
    if (database) {
      if (options.ui === false) throw new Error('--database configures UI application launch and cannot be combined with --domain-only.');
      options.ui = { ...options.ui, database: database.slice(11) };
    }
    const base = flags.find(f => f.startsWith('--base-url='));
    if (base) options.ui = { ...options.ui, baseURL: base.slice(11), launch: false };
    const auth = flags.find(f => f.startsWith('--auth='));
    if (auth) options.auth = { mode: auth.slice(7) };
    if (command === 'scan') console.log(JSON.stringify(scanProject(root), null, 2));
    else if (command === 'plan') await planProject(root, options);
    else { const result = await runProject(root, options); process.exitCode = result.status === 'failed' ? 1 : result.status === 'partial' ? 2 : 0; }
  } finally { process.removeListener('SIGINT', stop); process.removeListener('SIGTERM', stop); }
}
if (require.main === module) main().catch(error => { console.error(error.message); process.exitCode = 1; });
module.exports = { main };
