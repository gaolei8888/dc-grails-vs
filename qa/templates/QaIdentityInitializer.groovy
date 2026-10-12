package org.grailsqa.runtime

import groovy.json.JsonOutput
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

// Present only on the tool-owned bootRun classpath, never in application artifacts.
class QaIdentityInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    void initialize(ConfigurableApplicationContext context) {
        String key = System.getenv('GRAILS_QA_IDENTITY_TOKEN')
        String username = System.getenv('GRAILS_QA_IDENTITY_USER')
        if (!key || (!username && System.getenv('GRAILS_QA_DISCOVER') != 'true') || System.getProperty('grails.env') != 'test' ||
            System.getProperty('server.address') != '127.0.0.1') {
            throw new IllegalStateException('QA identity requires a tool-owned loopback test process and a test username')
        }
        // Environment-specific config can replace the external chainMap override.
        // Disable only the servlet registration; retain all security service beans.
        // The capability filter below still gates every request to this QA process.
        context.beanFactory.addBeanPostProcessor(new BeanPostProcessor() {
            Object postProcessAfterInitialization(Object bean, String name) {
                if (name == 'springSecurityFilterChainRegistrationBean' && bean instanceof FilterRegistrationBean) bean.enabled = false
                bean
            }
        })
        def filter = new OncePerRequestFilter() {
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) {
                if (request.remoteAddr != '127.0.0.1' || request.getHeader('X-Grails-QA-Identity') != key) {
                    response.sendError(403, 'This QA instance accepts only its QA browser'); return
                }
                def previous = SecurityContextHolder.context
                def previousRequest = RequestContextHolder.getRequestAttributes()
                def attributes = new ServletRequestAttributes(request, response)
                RequestContextHolder.setRequestAttributes(attributes)
                def catalog = new QaIdentityCatalog(context: context)
                try {
                    if (request.requestURI == '/__grails_qa/identities') {
                        response.characterEncoding = 'UTF-8'
                        if (request.method != 'GET') { response.sendError(405); return }
                        try {
                            int offset = Math.max(0, Integer.parseInt(request.getParameter('offset') ?: '0'))
                            def result = catalog.catalog(request.getParameter('tenant'), request.getParameter('query') ?: '', offset, request, request.getParameter('exact'))
                            response.contentType = 'application/json'
                            response.setHeader('Cache-Control', 'no-store')
                            response.writer.write(JsonOutput.toJson(result))
                        } catch (IllegalStateException | IllegalArgumentException error) {
                            response.status = 400
                            response.contentType = 'application/json'
                            response.writer.write(JsonOutput.toJson([error: error.message]))
                        } catch (Exception error) {
                            response.status = 400
                            response.contentType = 'application/json'
                            response.writer.write(JsonOutput.toJson([error: 'Could not read test identities (' + error.class.simpleName + '). Check user domain and tenant resolver support.']))
                        }
                        return
                    }
                    String chosenUser = request.getHeader('X-Grails-QA-User') ? URLDecoder.decode(request.getHeader('X-Grails-QA-User'), 'UTF-8') : username
                    String tenant = request.getHeader('X-Grails-QA-Tenant') ? URLDecoder.decode(request.getHeader('X-Grails-QA-Tenant'), 'UTF-8') : System.getenv('GRAILS_QA_TENANT')
                    boolean established = false
                    try {
                        catalog.within(tenant, request) {
                            if (System.getenv('GRAILS_QA_DISCOVER') == 'true') catalog.validateUser(chosenUser)
                            def principal = context.getBean('userDetailsService').loadUserByUsername(chosenUser)
                            if (!principal.enabled || !principal.accountNonLocked || !principal.accountNonExpired) {
                                response.sendError(403, 'QA test user is disabled, locked or expired'); return
                            }
                            def identity = SecurityContextHolder.createEmptyContext()
                            identity.authentication = new UsernamePasswordAuthenticationToken(principal, null, principal.authorities)
                            SecurityContextHolder.context = identity
                            established = true
                            chain.doFilter(request, response)
                        }
                    } catch (Exception error) {
                        if (response.committed || established) throw error
                        response.sendError(403, 'QA identity or tenant could not be established (' + error.class.simpleName + ')')
                    }
                } finally {
                    SecurityContextHolder.context = previous
                    RequestContextHolder.setRequestAttributes(previousRequest)
                    attributes.requestCompleted()
                }
            }
        }
        def registration = new FilterRegistrationBean(filter)
        registration.name = 'grailsQaIdentity'
        registration.order = -110
        registration.addUrlPatterns('/*')
        context.beanFactory.registerSingleton('grailsQaIdentityRegistration', registration)
    }
}
