// Deterministic expectations from a frozen constraint inventory, not AI guesses.
const numericTypes = /^(?:java\.lang\.)?(?:Integer|Long|Short|Byte|Double|Float|int|long|short|byte|double|float)$|^java\.math\.BigDecimal$/;
function decimal(value, delta = 0, scale = 0) {
  const match = /^(-?)(\d+)(?:\.(\d+))?$/.exec(String(value));
  if (!match) throw new Error('Unsupported numeric boundary: ' + value);
  scale = Math.max(scale, (match[3] || '').length);
  if (scale > 12 || match[2].length > 18) throw new Error('Numeric boundary exceeds first-version limits');
  let n = BigInt(match[2] + (match[3] || '').padEnd(scale, '0')) * (match[1] ? -1n : 1n) + BigInt(delta);
  const sign = n < 0 ? '-' : ''; if (n < 0) n = -n;
  const digits = n.toString().padStart(scale + 1, '0');
  return sign + (scale ? digits.slice(0, -scale) + '.' + digits.slice(-scale) : digits);
}
function propertyCases(property, gaps, domain) {
  const c = property.constraints, rows = [];
  const add = (rule, label, value, reject) => rows.push({ property: property.name, type: property.type, rule, label, value, reject });
  const scalar = property.type === 'java.lang.String' || numericTypes.test(property.type) || /^(java.lang.Boolean|boolean)$/.test(property.type);
  if (!scalar) { gaps.push({ domain, property: property.name, reason: 'Association or unsupported value type: ' + property.type }); return rows; }
  if (!/^(int|long|short|byte|double|float|boolean)$/.test(property.type)) add('nullable', 'null', null, !property.nullable);
  const handled = new Set(['nullable', 'unique']);
  if (property.type === 'java.lang.String') {
    if ('blank' in c) { add('blank', 'empty string', '', !c.blank); add('blank', 'whitespace', '   ', !c.blank); handled.add('blank'); }
    for (const key of ['minSize', 'maxSize', 'size']) {
      if (!(key in c)) continue;
      handled.add(key);
      const low = key === 'maxSize' ? null : Number(key === 'size' ? c[key].from : c[key]);
      const high = key === 'minSize' ? null : Number(key === 'size' ? c[key].to : c[key]);
      if ([low, high].filter(x => x !== null).some(n => !Number.isInteger(n) || n < 0 || n > 4095)) {
        gaps.push({ domain, property: property.name, reason: `${key} outside supported string length 0..4095` }); continue;
      }
      if (low !== null) {
        if (low > 1) add(key, 'below minimum length', 'x'.repeat(low - 1), true);
        add(key, 'at minimum length', 'x'.repeat(low), false);
      }
      if (high !== null) { add(key, 'at maximum length', 'x'.repeat(high), false); add(key, 'above maximum length', 'x'.repeat(high + 1), true); }
    }
    for (const key of ['email', 'url']) if (c[key] === true) {
      handled.add(key);
      add(key, 'valid format', key === 'email' ? 'qa@example.org' : 'https://example.org/qa', false);
      add(key, 'invalid format', 'not-a-' + key, true);
    }
  }
  if (numericTypes.test(property.type)) {
    const scale = /BigDecimal|Double|Float|double|float/.test(property.type) ? Math.max(2, Number(c.scale || 0)) : 0;
    for (const key of ['min', 'max', 'range']) {
      if (!(key in c)) continue;
      handled.add(key);
      try {
        const low = key === 'max' ? null : key === 'range' ? c[key].from : c[key];
        const high = key === 'min' ? null : key === 'range' ? c[key].to : c[key];
        if (low !== null) { add(key, 'below minimum', decimal(low, -1, scale), true); add(key, 'at minimum', decimal(low), false); }
        if (high !== null) { add(key, 'at maximum', decimal(high), false); add(key, 'above maximum', decimal(high, 1, scale), true); }
      } catch (error) { gaps.push({ domain, property: property.name, reason: error.message }); }
    }
  }
  if (Array.isArray(c.inList) && c.inList.length) {
    handled.add('inList');
    add('inList', 'allowed value', c.inList[0], false);
    let outside = property.type === 'java.lang.String' ? '__grails_qa_outside__' : numericTypes.test(property.type) ? decimal(c.inList[0], 1) : !c.inList[0];
    for (let i = 0; c.inList.map(String).includes(String(outside)) && i < 100; i++) {
      outside = property.type === 'java.lang.String' ? outside + '_' : numericTypes.test(property.type) ? decimal(outside, 1) : outside;
    }
    if (!c.inList.map(String).includes(String(outside))) add('inList', 'disallowed value', outside, true);
  }
  for (const key of Object.keys(c)) if (!handled.has(key)) gaps.push({ domain, property: property.name, reason: `Constraint ${key} needs a custom test; no expectation was guessed.` });
  return rows;
}
function seedFor(property) {
  const c = property.constraints;
  if (Array.isArray(c.inList) && c.inList.length) return c.inList[0];
  if (property.type === 'java.lang.String') {
    const min = Number(c.minSize || c.size?.from || 1), max = Number(c.maxSize || c.size?.to || 4095);
    if (min > 4095 || max < min) return undefined;
    let value = c.email ? 'qa@example.org' : c.url ? 'https://example.org' : 'q'.repeat(Math.max(1, min));
    if (value.length > max || value.length < min || c.matches || c.validator) return undefined;
    return value;
  }
  if (numericTypes.test(property.type)) return c.min ?? c.range?.from ?? (c.max !== undefined && Number(c.max) < 0 ? c.max : '0');
  if (/^(java.lang.Boolean|boolean)$/.test(property.type)) return false;
  return property.nullable ? null : undefined;
}
function createPlan(inventory, project, config = {}) {
  const gaps = [...project.gaps, ...(inventory.gaps || [])];
  const domains = inventory.domains.map(domain => {
    const rows = domain.properties.flatMap(property => propertyCases(property, gaps, domain.name));
    const seed = {}, missing = [];
    const custom = config.fixtures?.[domain.name] || {};
    for (const property of domain.properties) {
      const value = Object.hasOwn(custom, property.name) ? custom[property.name] : seedFor(property);
      if (value === undefined) missing.push(property.name); else seed[property.name] = value;
    }
    const unique = domain.properties.filter(p => p.constraints.unique === true && seed[p.name] != null).map(p => p.name);
    for (const p of domain.properties) if (p.constraints.unique && p.constraints.unique !== true) gaps.push({ domain: domain.name, property: p.name, reason: 'Composite uniqueness needs a custom fixture/test.' });
    if (missing.length) gaps.push({ domain: domain.name, reason: 'Persistence fixture incomplete: ' + missing.join(', ') });
    return { name: domain.name, properties: domain.properties, rows, seed, persistence: missing.length === 0, unique };
  });
  const pages = config.ui?.pages || project.views.filter(v => v.path && v.routeEvidence === 'literal UrlMappings entry').map(v => ({
    path: v.path, source: v.file, domain: v.domain, fields: v.fields, form: v.hasForm ? 'form' : undefined
  }));
  for (const page of pages) if (page.form && (!page.success || !page.error)) gaps.push({ page: page.path, reason: 'Form submission needs explicit success/error selectors; only page and field checks are planned.' });
  for (const view of project.views) if (!pages.some(p => p.source === view.file || p.path === view.path)) gaps.push({ file: view.file, reason: 'View route not confirmed. Add ui.pages configuration.' });
  return { version: 1, createdAt: new Date().toISOString(), oracle: 'Frozen Domain constraints from isolated Hibernate/H2; application-wide defaults and business requirements are not inferred.',
    domains, pages, gaps };
}
module.exports = { createPlan, propertyCases, seedFor, decimal };
