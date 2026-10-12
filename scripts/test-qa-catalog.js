const fs = require('fs');
const path = require('path');
const assert = require('assert/strict');
const enginePath = process.env.GRAILS_QA_ENGINE || path.resolve(__dirname, '../qa/engine');
const { openIdentitySession, runProject } = require(enginePath);
async function scenario(multi) {
  const root = path.resolve(__dirname, '../.vscode-test/catalog-' + (multi ? 'multi' : 'single'));
  process.env.GRAILS_QA_FIXTURE = root;
  process.env.QA_FIXTURE_REST = '1';
  delete require.cache[require.resolve('./prepare-qa-fixture')]; require('./prepare-qa-fixture');
  const write = (file, text) => fs.writeFileSync(path.join(root, file), text);
  const edit = (file, transform) => write(file, transform(fs.readFileSync(path.join(root, file), 'utf8')));
  fs.appendFileSync(path.join(root, 'build.gradle'), '\ndependencies { runtimeOnly "com.h2database:h2" }\n');
  fs.copyFileSync(path.resolve(__dirname, '../.vscode-test/qa-fixture/.grails-qa/plan.json'), path.join(root, '.grails-qa/plan.json'));
  if (!multi) {
    edit('grails-app/domain/qaapp/QaAccount.groovy', s => s.replace('String username', 'String username\n    Boolean enabled = true\n    Boolean accountLocked = false\n    Boolean accountExpired = false'));
    edit('grails-app/init/qaapp/BootStrap.groovy', s => s.replace("if (!QaAccount.findByUsername('qa-tester'))", "(1..105).each { new QaAccount(username: 'browse-' + it.toString().padLeft(3, '0')).save(flush: true, failOnError: true) }\n            new QaAccount(username: 'disabled', enabled: false).save(flush: true, failOnError: true)\n            new QaAccount(username: 'locked', accountLocked: true).save(flush: true, failOnError: true)\n            if (!QaAccount.findByUsername('qa-tester'))"));
  }
  if (multi) {
    fs.appendFileSync(path.join(root, 'grails-app/conf/application.groovy'), "\ngrails.gorm.multiTenancy.mode = 'DISCRIMINATOR'\ngrails.gorm.multiTenancy.tenantResolverClass = qaapp.QaTenantResolver\n");
    edit('grails-app/domain/qaapp/QaAccount.groovy', s => s.replace('class QaAccount {', 'class QaAccount implements grails.gorm.MultiTenant<QaAccount> {\n    String tenantId'));
    write('grails-app/domain/qaapp/QaOrganization.groovy', 'package qaapp\nclass QaOrganization { String name }\n');
    write('src/main/groovy/qaapp/QaTenantResolver.groovy', `package qaapp
import org.grails.datastore.mapping.multitenancy.AllTenantsResolver
import org.springframework.web.context.request.RequestContextHolder
class QaTenantResolver implements AllTenantsResolver {
    String attributeName = 'fixture.organization'
    Iterable<Serializable> resolveTenantIds() { new grails.gorm.DetachedCriteria(QaOrganization).distinct('name').list() }
    Serializable resolveTenantIdentifier() {
        def value = RequestContextHolder.currentRequestAttributes().getAttribute(attributeName, 1)
        if (!value) throw new org.grails.datastore.mapping.multitenancy.exceptions.TenantNotFoundException()
        value
    }
}
`);
    edit('grails-app/init/qaapp/BootStrap.groovy', s => s.replace(/        QaAccount.withTransaction \{[\s\S]*?\n        \}/, `        ['north', 'south', 'empty'].each { tenant ->
            QaOrganization.withTransaction { new QaOrganization(name: tenant).save(flush: true, failOnError: true) }
            if (tenant != 'empty') grails.gorm.multitenancy.Tenants.withId(tenant) {
                QaAccount.withTransaction {
                    new QaAccount(username: 'qa-tester').save(flush: true, failOnError: true)
                    new QaAccount(username: tenant + '-only').save(flush: true, failOnError: true)
                }
            }
        }`));
    edit('src/main/groovy/qaapp/QaUserDetailsService.groovy', s => s.replace('QaAccount.withTransaction {', `assert org.springframework.web.context.request.RequestContextHolder.currentRequestAttributes().getAttribute('fixture.organization', 1) == grails.gorm.multitenancy.Tenants.currentId()
        QaAccount.withTransaction {`));
    edit('grails-app/controllers/qaapp/QaRecordController.groovy', s => s.replace("response.setHeader('X-QA-Current-User', 'verified')", "assert session.getAttribute('fixture.organization') == 'north'\n        assert springSecurityService.currentUser.tenantId == 'north'\n        response.setHeader('X-QA-Current-User', 'verified')"));
  }
  let session;
  let starts = 0;
  const options = { ui: { database: 'project' }, onOutput: text => { if (text.includes('Grails application running')) starts++; process.stdout.write(text); } };
  try {
    session = await openIdentitySession(root, options);
    const initial = await session.catalog();
    assert.equal(initial.multiTenant, multi);
    if (multi) {
      assert.deepEqual(initial.tenants, ['empty', 'north', 'south']);
      assert.deepEqual(initial.users, []);
      assert.deepEqual((await session.catalog('north')).users, ['north-only', 'qa-tester']);
      assert.deepEqual((await session.catalog('south')).users, ['qa-tester', 'south-only']);
      assert.deepEqual((await session.catalog('empty')).users, []);
      await assert.rejects(session.catalog('missing'), /available test tenant/);
      assert.deepEqual((await session.catalog('north', 'south-only')).users, []);
    } else {
      assert.deepEqual(initial.tenants, []); assert.equal(initial.users.length, 100); assert.equal(initial.hasMore, true);
      const next = await session.catalog('', '', 100);
      assert.equal(next.users.length, 6); assert.equal(next.hasMore, false); assert(next.users.includes('qa-tester'));
      assert.deepEqual((await session.catalog('', 'qa-tester')).users, ['qa-tester']);
      assert.deepEqual((await session.catalog('', 'disabled')).users, []);
      assert.deepEqual((await session.catalog('', 'locked')).users, []);
      assert.deepEqual((await session.catalog('', 'missing')).users, []);
    }
    assert(!JSON.stringify(initial).includes('password'));
    await assert.rejects(runProject(root, { ui: false }), /Another QA operation/);
    const report = await session.run({ username: 'qa-tester', tenant: multi ? 'north' : '', domain: false, onOutput: options.onOutput });
    assert.equal(report.error, undefined); assert.equal(report.ui.tests, 14); assert.equal(report.ui.failed, 0);
    assert.equal(report.tenant, multi ? 'north' : undefined);
    assert.equal(starts, 1, 'Discovery and run must use the same application');
  } finally { session?.stop(); }
  assert(!fs.existsSync(path.join(root, '.grails-qa/active.lock')));
  console.log('Verified identity picker with ' + (multi ? 'tenant-scoped users, empty tenant and request/session context' : 'no tenant') + '.');
}
(async () => { if (process.env.QA_CATALOG_ONLY !== 'multi') await scenario(false); if (process.env.QA_CATALOG_ONLY !== 'single') await scenario(true); })().catch(error => { console.error(error); process.exitCode = 1; });
