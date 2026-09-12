// 호출자 callback의 Error까지 원형대로 전파하고 cleanup 실패가 덮지 않도록 포착한다.
@file:Suppress("TooGenericExceptionCaught")

package io.bluetape4k.exposed.tests

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.core.Key
import org.jetbrains.exposed.v1.core.statements.StatementInterceptor
import org.jetbrains.exposed.v1.core.transactions.nullableTransactionScope
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.transactions.transactionManager
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/** 현재 JDBC 트랜잭션의 fixture입니다. commit 후에도 유지됩니다. */
var currentJdbcTestDbFixture by nullableTransactionScope<JdbcTestDbFixture<*>>()
    internal set

internal object JdbcFixtureInterceptor: StatementInterceptor {
    override fun keepUserDataInTransactionStoreOnCommit(userData: Map<Key<*>, Any?>): Map<Key<*>, Any?> =
        userData.filterValues { it is JdbcTestDbFixture<*> || it is TestDB }
}

private class JdbcFixtureEntry(val fixture: JdbcTestDbFixture<*>) {
    val active = atomic(true)
}

private val entries = ThreadLocal.withInitial<List<JdbcFixtureEntry>> { emptyList() }
private val legacyFixtures = ConcurrentHashMap<TestDB, JdbcTestDbFixture<TestDB>>()

internal fun jdbcFixtureFor(testDB: TestDB): JdbcTestDbFixture<TestDB> =
    legacyFixtures.computeIfAbsent(testDB) { legacy ->
        jdbcTestDbFixture(legacy, { configure ->
            val previous = legacy.db
            try {
                legacy.connect(configure)
            } finally {
                legacy.db = previous
            }
        }, legacy.afterTestFinished).also { it.legacy = legacy }
    }

private fun JdbcTestDbFixture<*>.rejectNested() {
    check(entries.get().none { it.fixture === this && it.active.value }) {
        "Nested invocation of the same JDBC fixture is unsupported"
    }
}

internal fun <K> JdbcTestDbFixture<K>.executeBlocking(
    configure: (DatabaseConfig.Builder.() -> Unit)?,
    statement: JdbcTransaction.(K) -> Unit,
) {
    rejectNested()
    semaphore.acquire()
    val previousEntries = entries.get()
    val entry = JdbcFixtureEntry(this)
    entries.set(previousEntries + entry)
    try {
        val selected = configured(configure)
        legacy?.db = selected
        var failure: Throwable? = null
        try {
            transaction(db = selected, transactionIsolation = selected.transactionManager.defaultIsolationLevel) {
                maxAttempts = 1
                registerInterceptor(JdbcFixtureInterceptor)
                currentJdbcTestDbFixture = this@executeBlocking
                currentTestDB = legacy
                statement(key)
            }
        } catch (thrown: Throwable) {
            failure = thrown
            throw thrown
        } finally {
            try {
                unregisterTemporary(selected, failure)
            } finally {
                legacy?.db = database
            }
        }
    } finally {
        legacy?.db = database
        entry.active.value = false
        entries.set(previousEntries)
        semaphore.release()
    }
}

internal suspend fun <K> JdbcTestDbFixture<K>.executeSuspending(
    context: CoroutineContext?,
    configure: (DatabaseConfig.Builder.() -> Unit)?,
    statement: suspend JdbcTransaction.(K) -> Unit,
) {
    rejectNested()
    val inheritedEntries = entries.get()
    withContext(context ?: EmptyCoroutineContext) {
        val statementDispatcher = currentCoroutineContext()[ContinuationInterceptor] ?: EmptyCoroutineContext
        withContext(Dispatchers.IO) {
            var acquired = false
            val entry = JdbcFixtureEntry(this@executeSuspending)
            try {
                runInterruptible {
                    semaphore.acquire()
                    acquired = true
                }
                currentCoroutineContext().ensureActive()
                withContext(entries.asContextElement(inheritedEntries + entry)) {
                    val selected = configured(configure)
                    legacy?.db = selected
                    var failure: Throwable? = null
                    try {
                        withContext(statementDispatcher) {
                            suspendTransaction(
                                db = selected,
                                transactionIsolation = selected.transactionManager.defaultIsolationLevel,
                            ) {
                                maxAttempts = 1
                                registerInterceptor(JdbcFixtureInterceptor)
                                currentJdbcTestDbFixture = this@executeSuspending
                                currentTestDB = legacy
                                statement(key)
                            }
                        }
                    } catch (thrown: java.util.concurrent.CancellationException) {
                        failure = thrown
                        throw thrown
                    } catch (thrown: Throwable) {
                        failure = thrown
                        throw thrown
                    } finally {
                        try {
                            unregisterTemporary(selected, failure)
                        } finally {
                            legacy?.db = database
                        }
                    }
                }
            } finally {
                if (acquired) legacy?.db = database
                entry.active.value = false
                if (acquired) semaphore.release()
            }
        }
    }
}
