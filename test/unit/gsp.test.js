const { test, before } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { Registry, parseRawGrammar, INITIAL } = require('vscode-textmate');
const oniguruma = require('vscode-oniguruma');

let grammar;
before(async () => {
  let builtins = process.env.VSCODE_BUILTIN_EXTENSIONS;
  if (!builtins && process.platform === 'win32') {
    const install = path.join(process.env.LOCALAPPDATA, 'Programs', 'Microsoft VS Code');
    builtins = [path.join(install, 'resources', 'app', 'extensions'),
      ...fs.readdirSync(install, { withFileTypes: true }).filter(d => d.isDirectory()).map(d => path.join(install, d.name, 'resources', 'app', 'extensions'))]
      .find(p => fs.existsSync(path.join(p, 'html', 'package.json')));
  }
  assert(builtins, 'Set VSCODE_BUILTIN_EXTENSIONS to VS Code resources/app/extensions');
  const scopes = new Map();
  for (const folder of ['html', 'groovy', 'javascript', 'css']) {
    const manifest = JSON.parse(fs.readFileSync(path.join(builtins, folder, 'package.json'), 'utf8'));
    for (const grammar of manifest.contributes.grammars) scopes.set(grammar.scopeName, path.join(builtins, folder, grammar.path));
  }
  scopes.set('text.html.gsp', path.resolve(__dirname, '../../syntaxes/gsp.tmLanguage.json'));
  await oniguruma.loadWASM(fs.readFileSync(require.resolve('vscode-oniguruma/release/onig.wasm')));
  const registry = new Registry({
    onigLib: Promise.resolve({ createOnigScanner: patterns => new oniguruma.OnigScanner(patterns), createOnigString: s => new oniguruma.OnigString(s) }),
    loadGrammar: async scope => {
      const file = scopes.get(scope);
      return file ? parseRawGrammar(fs.readFileSync(file, 'utf8'), file) : null;
    }
  });
  grammar = await registry.loadGrammar('text.html.gsp');
});
function tokens(text) {
  let state = INITIAL;
  return text.split('\n').map(line => {
    const result = grammar.tokenizeLine(line, state);
    state = result.ruleStack;
    return result.tokens.map(token => ({ text: line.slice(token.startIndex, token.endIndex), scopes: token.scopes }));
  });
}
function at(line, word) {
  const found = line.find(t => t.text.includes(word));
  assert(found, `Missing token ${word}: ${JSON.stringify(line)}`);
  return found.scopes;
}
test('HTML and custom Grails namespaces retain tag and attribute scopes', () => {
  const [line] = tokens('<div class="a"><g:each in="${items}" var="item"><sec:ifLoggedIn /></g:each></div>');
  assert(at(line, 'div').some(s => s.startsWith('entity.name.tag')));
  assert(at(line, 'each').includes('entity.name.tag.gsp'));
  assert(at(line, 'ifLoggedIn').includes('entity.name.tag.gsp'));
  assert(at(line, 'items').includes('source.groovy'));
});
test('Expressions inside HTML attributes and nested closures close at the right brace', () => {
  const [line] = tokens('<a href="${items.collect { it + 1 }.join(\',\')}">after</a>');
  assert(at(line, 'collect').includes('source.groovy'));
  assert(at(line, 'join').includes('source.groovy'));
  assert(!at(line, 'after').includes('source.groovy'));
});
test('GSP and HTML comments suppress expressions and survive multiple lines', () => {
  const lines = tokens('%{-- ${hidden}\n<g:each /> --}%\n<!-- ${alsoHidden} -->\n<p>${visible}</p>');
  for (const line of lines.slice(0, 3)) {
    assert(line.some(t => t.scopes.some(s => s.startsWith('comment.block'))));
    assert(!line.some(t => t.scopes.includes('source.groovy')));
  }
  assert(at(lines[3], 'visible').includes('source.groovy'));
});
test('Directives and multiline scriptlets restore HTML after the delimiter', () => {
  const lines = tokens('<%@ page contentType="text/html" %>\n<% def number = 1\nnumber += 2 %><p>after</p>');
  assert(at(lines[0], 'page').includes('keyword.control.directive.gsp'));
  assert(at(lines[1], 'def').includes('source.groovy'));
  assert(!at(lines[2], 'after').includes('source.groovy'));
});
test('Script and style keep their embedded language with GSP interpolation', () => {
  const lines = tokens('<script>const total = ${items.size()};</script>\n<style>p { color: red; }</style>');
  assert(at(lines[0], 'const').some(s => s.includes('source.js')));
  assert(at(lines[0], 'items').includes('source.groovy'));
  assert(at(lines[1], 'color').some(s => s.includes('source.css')));
});
