const fs = require('fs');
const path = require('path');
const literal = value => "'" + String(value).replace(/\\/g, '\\\\').replace(/'/g, "\\'").replace(/\r/g, '\\r').replace(/\n/g, '\\n') + "'";
const jsonExpr = value => `new groovy.json.JsonSlurper().parseText(new String(${literal(Buffer.from(JSON.stringify(value)).toString('base64'))}.decodeBase64(), 'UTF-8'))`;
function base(directory) {
  fs.mkdirSync(directory, { recursive: true });
  fs.copyFileSync(path.join(__dirname, 'templates/QaBase.groovy'), path.join(directory, 'QaBase.groovy'));
}
function domainList(domains) {
  const names = domains.map(d => d.fqn || d.name);
  for (const name of names) if (!/^[A-Za-z_$][\w$]*(\.[A-Za-z_$][\w$]*)*$/.test(name)) throw new Error('Invalid Domain class name');
  return '[' + names.join(', ') + ']';
}
function writeProbe(project, directory) {
  base(directory);
  fs.writeFileSync(path.join(directory, 'InventoryQaSpec.groovy'), `package org.grailsqa.generated
import groovy.json.JsonOutput
class InventoryQaSpec extends QaBase {
    List<Class> qaDomains() { ${domainList(project.domains)} }
    static Object plain(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean) return value
        if (value instanceof Number) return value.toString()
        if (value instanceof Range) return [from: plain(value.from), to: plain(value.to)]
        if (value instanceof Collection) return value.collect { plain(it) }
        return [unsupported: value.class.name]
    }
    void "export evaluated Domain constraints"() {
        when:
        def domains = qaDomains().collect { type ->
            def entity = qaStore.mappingContext.getPersistentEntity(type.name)
            def validator = qaStore.mappingContext.getEntityValidator(entity)
            [name: type.name, properties: validator.constrainedProperties.collect { name, cp ->
                [name: name, type: cp.propertyType.name, nullable: cp.nullable,
                 constraints: cp.appliedConstraints.collectEntries { c -> [(c.name): plain(c.parameter)] }]
            }.sort { it.name }]
        }
        def file = new File(System.getProperty('grails.qa.output'), 'inventory.json')
        file.text = JsonOutput.prettyPrint(JsonOutput.toJson([domains: domains, gaps: []]))
        then:
        file.exists()
    }
}
`);
}
function writeSpecs(plan, directory) {
  base(directory);
  const all = domainList(plan.domains);
  plan.domains.forEach((domain, index) => {
    fs.writeFileSync(path.join(directory, `Domain${index}QaSpec.groovy`), `package org.grailsqa.generated
import spock.lang.Unroll
import org.opentest4j.TestAbortedException
class Domain${index}QaSpec extends QaBase {
    List<Class> qaDomains() { ${all} }
    static final Class TARGET = ${domain.name}
    static Object coerce(def bean, String name, Object value) {
        value == null ? null : value.asType(bean.metaClass.getMetaProperty(name).type)
    }
    static def seed() {
        def bean = TARGET.newInstance()
        def values = ${jsonExpr(domain.seed)}
        values.each { key, value -> bean[key] = coerce(bean, key, value) }
        if (!bean.validate()) throw new TestAbortedException('Generated fixture is not valid; configure fixtures for ${domain.name}: ' + bean.errors.allErrors*.code)
        bean
    }
    ${domain.rows.length ? `@Unroll('${domain.name}: #row.property #row.rule #row.label (reject=#row.reject)')
    void "frozen constraint boundaries"() {
        given:
        def bean = TARGET.newInstance()
        when:
        bean[row.property] = coerce(bean, row.property, row.value)
        bean.validate([row.property])
        def codes = bean.errors.getFieldErrors(row.property)*.code
        def prefix = row.rule == 'inList' ? 'not.inList' : row.rule
        def rejectedByRule = codes.any { it == prefix || it.startsWith(prefix + '.') }
        then:
        rejectedByRule == row.reject
        where:
        row << ${jsonExpr(domain.rows)}
    }` : ''}
    ${domain.persistence ? `void "${domain.name}: persist flush clear and reload in H2"() {
        given:
        def bean = seed()
        def expected = ${jsonExpr(domain.seed)}
        when:
        bean.save(flush: true, failOnError: true)
        def id = bean.ident()
        qaStore.sessionFactory.currentSession.clear()
        def reloaded = TARGET.get(id)
        then:
        reloaded != null
        expected.every { name, value -> reloaded[name] == coerce(reloaded, name, value) }
    }` : ''}
    ${domain.persistence && domain.unique.length ? `@Unroll('${domain.name}: reject duplicate #field in H2')
    void "unique values"() {
        given:
        def first = seed()
        first.save(flush: true, failOnError: true)
        qaStore.sessionFactory.currentSession.clear()
        when:
        def duplicate = TARGET.newInstance()
        duplicate[field] = coerce(duplicate, field, (${jsonExpr(domain.seed)})[field])
        duplicate.validate([field])
        then:
        duplicate.errors.getFieldErrors(field).any { it.code == 'unique' }
        where:
        field << ${jsonExpr(domain.unique)}
    }` : ''}
}
`);
  });
}
module.exports = { writeProbe, writeSpecs, literal };
