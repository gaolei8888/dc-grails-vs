// Build an isolated test app from the existing blank Grails testbed's toolchain.
// No business repositories or credentials are used.
const fs = require('fs');
const path = require('path');
const source = path.resolve(process.env.GRAILS_TESTBED || path.join(__dirname, '../../grails-dap-testbed/dapspike'));
const target = process.env.GRAILS_QA_FIXTURE || path.resolve(__dirname, '../.vscode-test/qa-fixture');
function write(file, text) { const dest = path.join(target, file); fs.mkdirSync(path.dirname(dest), { recursive: true }); fs.writeFileSync(dest, text); }
fs.mkdirSync(target, { recursive: true });
for (const name of ['gradlew', 'gradlew.bat', 'gradle', 'gradle.properties']) fs.cpSync(path.join(source, name), path.join(target, name), { recursive: true });
write('settings.gradle', "rootProject.name = 'grails-qa-fixture'\n");
let build = fs.readFileSync(path.join(source, 'build.gradle'), 'utf8').split('// --- T0 spike additions')[0]
  .replace(/^.*grails-data-mongodb-gson-templates.*\r?\n/gm, '')
  .replace(/^.*["']com\.h2database:h2[^\r\n]*\r?\n/gm, '')
  .replace('    profile ', '    implementation "org.apache.grails:grails-spring-security"\n    profile ');
if (process.env.QA_FIXTURE_REST === '1') build += '\ndependencies { implementation "org.apache.grails:grails-spring-security-rest:7.0.1" }\n';
write('build.gradle', build);
write('grails-app/init/qaapp/Application.groovy', `package qaapp
import grails.boot.GrailsApp
import grails.boot.config.GrailsAutoConfiguration
class Application extends GrailsAutoConfiguration {
    static void main(String[] args) { GrailsApp.run(Application, args) }
}
`);
write('grails-app/init/qaapp/BootStrap.groovy', `package qaapp
class BootStrap {
    def dataSource
    def grailsApplication
    def init = { servletContext ->
        assert grailsApplication.mainContext.getBean('springSecurityFilterChainRegistrationBean').enabled == !System.getenv('GRAILS_QA_IDENTITY_TOKEN')
        QaAccount.withTransaction {
            if (!QaAccount.findByUsername('qa-tester')) new QaAccount(username: 'qa-tester').save(flush: true, failOnError: true)
        }
        if (grailsApplication.mainContext.containsBean('tokenStorageConsumer')) {
            assert grailsApplication.mainContext.getBean('tokenStorageConsumer').tokenStorageService != null
            println 'QA fixture verified REST tokenStorageService dependency'
        }
        // Verify the actual JDBC connection, not just a system-property value.
        def connection = dataSource.connection
        try {
            if (System.getProperty('grails.qa.database') == 'project') {
                assert connection.metaData.URL == 'jdbc:h2:mem:qaFixtureTest' : 'QA replaced the project test database'
                assert System.getProperty('dataSource.url') == null : 'QA supplied an unwanted datasource override'
                println 'QA fixture verified project test database'
            } else {
                assert connection.metaData.URL.startsWith('jdbc:h2:mem:grailsQa_') : 'QA did not isolate the primary datasource'
                println 'QA fixture verified temporary H2 connection'
            }
        } finally { connection.close() }
    }
}
`);
write('grails-app/domain/qaapp/QaRecord.groovy', `package qaapp
class QaRecord {
    String name
    Integer quantity
    String contact
    String state
    static constraints = {
        name blank: false, minSize: 3, maxSize: 12, unique: true
        quantity min: 1, max: 5
        contact email: true
        state inList: ['open', 'closed']
    }
}
`);
write('grails-app/controllers/qaapp/QaRecordController.groovy', `package qaapp
import grails.gorm.transactions.Transactional
class QaRecordController {
    def springSecurityService
    private boolean qaIdentity() {
        if (!springSecurityService.currentUser) { redirect(uri: '/login/auth'); return false }
        assert springSecurityService.currentUser instanceof QaAccount
        assert springSecurityService.currentUser.username == 'qa-tester'
        assert springSecurityService.authentication.authorities*.authority.contains('ROLE_QA')
        response.setHeader('X-QA-Current-User', 'verified')
        true
    }
    def create() { if (!qaIdentity()) return; render(view: 'create', model: [record: new QaRecord()]) }
    @Transactional
    def save() {
        if (!qaIdentity()) return
        def record = new QaRecord(params)
        if (!record.save(flush: true)) {
            render(view: 'create', model: [record: record]); return
        }
        render(view: 'show', model: [record: record])
    }
}
`);
write('grails-app/controllers/qaapp/LoginController.groovy', `package qaapp
class LoginController {
    def auth() { render(view: 'auth', model: [csrf: request.getAttribute('_csrf')]) }
    def denied() { response.status = 403; render 'Forbidden' }
}
`);
write('grails-app/controllers/qaapp/UrlMappings.groovy', `package qaapp
class UrlMappings {
    static mappings = {
        get "/qaRecord/create"(controller: 'qaRecord', action: 'create')
        post "/qaRecord/save"(controller: 'qaRecord', action: 'save')
        get "/login/auth"(controller: 'login', action: 'auth')
        get "/login/denied"(controller: 'login', action: 'denied')
        "/"(controller: 'qaRecord', action: 'create')
        "500"(view: '/error')
    }
}
`);
write('grails-app/views/qaRecord/create.gsp', `<html><head><title>QA record</title></head><body>
<h1>Create QA record</h1>
<g:hasErrors bean="\${record}"><ul class="errors" role="alert">
<g:eachError bean="\${record}" var="problem"><li data-qa-field="\${problem.field}">\${problem.code}</li></g:eachError>
</ul></g:hasErrors>
<g:form controller="qaRecord" action="save" name="qa-form">
<label>Name <g:textField name="name" value="\${record.name}" /></label>
<label>Quantity <g:field type="number" name="quantity" value="\${record.quantity}" /></label>
<label>Contact <g:textField name="contact" value="\${record.contact}" /></label>
<label>State <g:textField name="state" value="\${record.state}" /></label>
<button type="submit">Save</button>
</g:form></body></html>
`);
write('grails-app/views/qaRecord/show.gsp', '<html><body><h1 id="qa-saved">Saved</h1><p>\${record.name}</p></body></html>');
write('grails-app/views/error.gsp', '<html><body>Application error</body></html>');
write('grails-app/views/login/auth.gsp', `<html><head><title>QA login</title></head><body><h1>Log in</h1>
<form method="POST" action="\${request.contextPath}/login/authenticate">
<label>Username <input name="username" autocomplete="off"></label>
<label>Password <input name="password" type="password"></label>
<g:if test="\${csrf}"><input type="hidden" name="\${csrf.parameterName}" value="\${csrf.token}"></g:if>
<button type="submit">Log in</button></form></body></html>
`);
write('grails-app/conf/application.yml', `grails:
  profile: rest-api
  codegen:
    defaultPackage: qaapp
dataSource:
  driverClassName: org.h2.Driver
  username: sa
  password: ''
  dbCreate: create-drop
  url: jdbc:h2:mem:qaFixtureDefault
environments:
  test:
    dataSource:
      url: jdbc:h2:mem:qaFixtureTest
`);
write('grails-app/conf/application.groovy', `grails.plugin.springsecurity.securityConfigType = 'InterceptUrlMap'
grails.plugin.springsecurity.userLookup.userDomainClassName = 'qaapp.QaAccount'
grails.plugin.springsecurity.interceptUrlMap = [
    [pattern: '/login/**', access: ['permitAll']],
    [pattern: '/error', access: ['permitAll']],
    [pattern: '/**', access: ['ROLE_QA']]
]
grails.plugin.springsecurity.successHandler.defaultTargetUrl = '/qaRecord/create'
grails.plugin.springsecurity.successHandler.alwaysUseDefault = true
${process.env.QA_FIXTURE_REST === '1' ? "grails.plugin.springsecurity.rest.token.storage.jwt.secret = 'public-qa-fixture-only-jwt-key-never-for-real-applications-0123456789'" : ''}
`);
write('grails-app/conf/spring/resources.groovy', `import org.springframework.security.provisioning.InMemoryUserDetailsManager
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
beans = {
    ${process.env.QA_FIXTURE_REST === '1' ? "tokenStorageConsumer(qaapp.TokenStorageConsumer) { tokenStorageService = ref('tokenStorageService') }" : ''}
    // Public fixture-only credentials, never used outside this disposable app.
    userDetailsService(qaapp.QaUserDetailsService)
}
`);
write('grails-app/domain/qaapp/QaAccount.groovy', `package qaapp
class QaAccount {
    String username
}
`);
write('src/main/groovy/qaapp/QaUserDetailsService.groovy', `package qaapp
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import grails.plugin.springsecurity.userdetails.GrailsUser
class QaUserDetailsService implements UserDetailsService {
    UserDetails loadUserByUsername(String username) {
        QaAccount.withTransaction {
            def user = QaAccount.findByUsername(username)
            if (!user) throw new UsernameNotFoundException('Unknown fixture user')
            new GrailsUser(user.username, '{bcrypt}' + new BCryptPasswordEncoder().encode('qa-password'),
                true, true, true, true, [new SimpleGrantedAuthority('ROLE_QA')], user.id)
        }
    }
}
`);
write('src/main/groovy/qaapp/TokenStorageConsumer.groovy', 'package qaapp\nclass TokenStorageConsumer { Object tokenStorageService }\n');
write('.grails-qa/config.json', JSON.stringify({ ui: { launch: true, pages: [{ path: '/qaRecord/create', source: 'grails-app/views/qaRecord/create.gsp', domain: 'qaapp.QaRecord', form: 'form', fields: ['name', 'quantity', 'contact', 'state'],
  success: { selector: '#qa-saved', text: 'Saved' }, error: { selector: '.errors', fieldSelector: '[data-qa-field="{field}"]' } }] }, auth: { mode: 'credentials', loginPath: '/login/auth' } }, null, 2));
console.log(target);
