package org.grailsqa.generated

import spock.lang.Specification
import spock.lang.Shared
import spock.lang.AutoCleanup
import org.grails.orm.hibernate.HibernateDatastore
import org.springframework.transaction.support.DefaultTransactionDefinition

/** Dedicated H2 datastore: does not boot the application or read its DB config. */
abstract class QaBase extends Specification {
    @Shared @AutoCleanup HibernateDatastore qaStore
    def qaTransaction
    abstract List<Class> qaDomains()
    void setupSpec() {
        qaStore = new HibernateDatastore([
            'dataSource.url': 'jdbc:h2:mem:grailsQa_' + UUID.randomUUID().toString().replace('-', '') + ';DB_CLOSE_DELAY=-1',
            'dataSource.driverClassName': 'org.h2.Driver',
            'dataSource.username': 'sa', 'dataSource.password': '',
            'dataSource.dbCreate': 'create-drop',
            'hibernate.hbm2ddl.auto': 'create-drop',
            'hibernate.show_sql': false
        ], qaDomains() as Class[])
    }
    void setup() { qaTransaction = qaStore.transactionManager.getTransaction(new DefaultTransactionDefinition()) }
    void cleanup() { if (qaTransaction != null) qaStore.transactionManager.rollback(qaTransaction) }
}
