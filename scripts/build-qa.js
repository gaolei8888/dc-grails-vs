// Stage Playwright's driver (no browser binaries) for the packaged QA engine.
const fs = require('fs');
const path = require('path');
const source = path.dirname(require.resolve('playwright-core/package.json'));
const target = path.resolve(__dirname, '../dist/qa-browser');
fs.mkdirSync(target, { recursive: true });
fs.cpSync(source, target, { recursive: true });
console.log('Staged QA browser driver at ' + target);
