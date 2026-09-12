const fs = require('fs');
const path = require('path');

function walk(directory, suffix) {
  if (!fs.existsSync(directory)) return [];
  return fs.readdirSync(directory, { withFileTypes: true }).flatMap(entry => {
    const file = path.join(directory, entry.name);
    if (entry.isSymbolicLink()) return [];
    return entry.isDirectory() ? walk(file, suffix) : entry.name.endsWith(suffix) ? [file] : [];
  }).sort();
}
function stripComments(text) {
  return text.replace(/\/\*[\s\S]*?\*\/|\/\/[^\n]*|'(?:\\.|[^'\\])*'|"(?:\\.|[^"\\])*"/g,
    match => match.startsWith('/') ? match.replace(/[^\n]/g, ' ') : match);
}
function scanProject(root) {
  root = fs.realpathSync(root);
  if (!fs.existsSync(path.join(root, 'grails-app')) || !fs.existsSync(path.join(root, 'build.gradle'))) {
    throw new Error('Open a Grails application containing grails-app and build.gradle.');
  }
  const gaps = [];
  const domains = walk(path.join(root, 'grails-app/domain'), '.groovy').flatMap(file => {
    const text = stripComments(fs.readFileSync(file, 'utf8'));
    const pkg = /^\s*package\s+([\w.]+)/m.exec(text)?.[1];
    const name = path.basename(file, '.groovy');
    const declaration = new RegExp(`\\b(abstract\\s+)?class\\s+${name}\\b`).exec(text);
    if (!declaration || declaration[1] || !/^[A-Za-z_$][\w$]*$/.test(name)) {
      gaps.push({ file: path.relative(root, file), reason: 'Not a concrete conventional Domain class.' }); return [];
    }
    const fqn = pkg ? `${pkg}.${name}` : name;
    if (!/^[A-Za-z_$][\w$]*(\.[A-Za-z_$][\w$]*)*$/.test(fqn)) throw new Error('Invalid Domain class name');
    return [{ name, fqn, file: path.relative(root, file).replace(/\\/g, '/') }];
  });
  const build = fs.readFileSync(path.join(root, 'build.gradle'), 'utf8');
  const configFiles = walk(path.join(root, 'grails-app/conf'), '.groovy').concat(walk(path.join(root, 'grails-app/conf'), '.yml'));
  const configuration = configFiles.map(file => fs.readFileSync(file, 'utf8')).join('\n');
  const security = /grails-spring-security|spring-security-core|grails\.plugin\.springsecurity/.test(build + configuration)
    ? 'grails-spring-security' : /spring-boot-starter-security|SecurityFilterChain/.test(build + configuration) ? 'spring-security' : 'none-detected';
  const multipleDataSources = configFiles.some(file => {
    const raw = fs.readFileSync(file, 'utf8');
    const text = file.endsWith('.groovy') ? stripComments(raw) : raw;
    // Match a complete configuration key, never a suffix such as
    // excluded-datasources. Accept conventional YAML and Groovy blocks/paths.
    return /(?:^|[;{}])\s*(?:dataSources|datasources|"dataSources"|"datasources"|'dataSources'|'datasources')\s*[:={.]/m.test(text);
  });
  const mappings = walk(path.join(root, 'grails-app/controllers'), '.groovy').filter(p => p.endsWith('UrlMappings.groovy'))
    .map(p => fs.readFileSync(p, 'utf8')).join('\n');
  const views = walk(path.join(root, 'grails-app/views'), '.gsp').filter(p => !path.basename(p).startsWith('_')).map(file => {
    const relative = path.relative(path.join(root, 'grails-app/views'), file).replace(/\\/g, '/');
    const parts = relative.split('/');
    const controller = parts.length === 2 ? parts[0] : null;
    const action = path.basename(file, '.gsp');
    const text = fs.readFileSync(file, 'utf8').replace(/%\{--[\s\S]*?--\}%|<!--[\s\S]*?-->/g, '');
    const fields = [...text.matchAll(/<(?:g:(?:textField|passwordField|textArea|field|select)|input|textarea|select)\b[^>]*\bname\s*=\s*["']([\w.]+)["'][^>]*>/g)].map(m => m[1]);
    const literal = controller && [...mappings.matchAll(/^\s*(?:get\s+)?["'](\/[^"'$]*)["']\s*\(\s*controller:\s*["']([^"']+)["']\s*,\s*action:\s*["']([^"']+)["']/gm)]
      .find(m => m[2] === controller && m[3] === action)?.[1];
    const domain = domains.find(d => d.name.toLowerCase() === controller?.toLowerCase());
    return { file: `grails-app/views/${relative}`, path: literal || (controller ? `/${controller}/${action}` : null),
      routeEvidence: literal ? 'literal UrlMappings entry' : 'convention candidate; confirm in config',
      domain: domain?.fqn, fields: [...new Set(fields)], hasForm: /<(?:g:form|form)\b/.test(text) };
  });
  return { version: 1, root, domains, views, security, multipleDataSources, gaps };
}
function readConfig(root) {
  const file = path.join(root, '.grails-qa/config.json');
  const config = fs.existsSync(file) ? JSON.parse(fs.readFileSync(file, 'utf8')) : {};
  if (config.auth && ('password' in config.auth || 'username' in config.auth)) throw new Error('Keep credentials in VS Code SecretStorage or GRAILS_QA_USERNAME / GRAILS_QA_PASSWORD, not config.json.');
  return config;
}
module.exports = { scanProject, readConfig, walk };
