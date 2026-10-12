package org.grailsqa.runtime

import grails.gorm.multitenancy.Tenants
import grails.plugin.springsecurity.SpringSecurityUtils
import org.grails.orm.hibernate.HibernateDatastore
import org.grails.datastore.mapping.multitenancy.AllTenantsResolver
import org.springframework.transaction.support.TransactionTemplate

class QaIdentityCatalog {
    def context
    def store() { context.getBean(HibernateDatastore) }
    boolean multitenant() { store().multiTenancyMode.toString() != 'NONE' }
    Map tenantMap() {
        if (!multitenant()) return [:]
        def datastore = store()
        def ids
        if (datastore.tenantResolver instanceof AllTenantsResolver) {
            ids = Tenants.withoutId(datastore) { ->
                def transaction = new TransactionTemplate(datastore.transactionManager)
                transaction.readOnly = true
                transaction.execute { status -> datastore.tenantResolver.resolveTenantIds().toList() }
            }
        } else if (datastore.multiTenancyMode.toString() == 'DATABASE') {
            ids = datastore.connectionSources.collect { it.name }.findAll { it != 'DEFAULT' }
        } else {
            throw new IllegalStateException('The tenant resolver cannot list tenants. Implement AllTenantsResolver for QA discovery.')
        }
        def result = [:]
        ids.each { id ->
            if (id == null || !(id instanceof CharSequence || id instanceof Number || id instanceof UUID)) throw new IllegalStateException('Unsupported tenant identifier type')
            if (result.containsKey(id.toString())) throw new IllegalStateException('Ambiguous tenant identifiers')
            result[id.toString()] = id
        }
        result
    }
    def within(String tenant, def request, Closure action) {
        if (!multitenant()) {
            if (tenant) throw new IllegalArgumentException('This application has no tenant')
            return action.call()
        }
        def tenants = tenantMap()
        if (!tenant || !tenants.containsKey(tenant)) throw new IllegalArgumentException('Select an available test tenant')
        def resolver = store().tenantResolver
        // Standard SessionTenantResolver and custom resolvers exposing attributeName.
        String attribute = resolver.metaClass.hasProperty(resolver, 'attributeName') ? resolver.attributeName : null
        def session = attribute ? request.getSession() : null
        def previous = attribute ? session.getAttribute(attribute) : null
        if (attribute) session.setAttribute(attribute, tenants[tenant])
        try {
            return Tenants.withId(HibernateDatastore, tenants[tenant]) { action.call() }
        } finally {
            if (attribute) {
                if (previous == null) session.removeAttribute(attribute)
                else session.setAttribute(attribute, previous)
            }
        }
    }
    def userType() {
        String name = SpringSecurityUtils.securityConfig.userLookup.userDomainClassName
        def entity = name ? store().mappingContext.getPersistentEntity(name) : null
        if (!entity) throw new IllegalStateException('No mapped Spring Security user domain was found. Configure userLookup.userDomainClassName or provide a project QA adapter.')
        entity.javaClass
    }
    List users(String query, int offset, String exact = null) {
        def type = userType()
        def conf = SpringSecurityUtils.securityConfig.userLookup
        String field = conf.usernamePropertyName ?: 'username'
        def entity = store().mappingContext.getPersistentEntity(type.name)
        if (!entity.getPropertyByName(field)) throw new IllegalStateException('Configured username property is not mapped')
        def flags = [(conf.enabledPropertyName ?: 'enabled'): true,
            (conf.accountLockedPropertyName ?: 'accountLocked'): false,
            (conf.accountExpiredPropertyName ?: 'accountExpired'): false]
        type.withTransaction([readOnly: true]) {
            type.createCriteria().list(max: exact == null ? 101 : 2, offset: offset) {
                if (exact != null) eq(field, exact)
                else if (query) ilike(field, '%' + query.replace('\\', '\\\\').replace('%', '\\%').replace('_', '\\_') + '%')
                flags.each { key, value -> if (entity.getPropertyByName(key)) eq(key, value) }
                order(field, 'asc')
                projections { property(field) }
            }.collect { it.toString() }
        }
    }
    Map catalog(String tenant, String query, int offset, def request, String exact = null) {
        def multi = multitenant()
        def tenants = tenantMap().keySet().sort()
        if (multi && !tenant) return [multiTenant: true, tenants: tenants, users: [], hasMore: false]
        def rows = within(tenant, request) { users(query, offset, exact) }
        [multiTenant: multi, tenants: tenants, users: rows.take(100), hasMore: rows.size() > 100]
    }
    void validateUser(String username) {
        if (!username || users('', 0, username).size() != 1) throw new IllegalArgumentException('Select an available, unambiguous test user in this tenant')
    }
}
