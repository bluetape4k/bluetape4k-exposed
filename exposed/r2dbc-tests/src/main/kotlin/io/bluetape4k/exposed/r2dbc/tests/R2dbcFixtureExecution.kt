// 호출자 callback의 Error까지 원형대로 전파하고 cleanup 실패가 덮지 않도록 포착한다.
@file:Suppress("TooGenericExceptionCaught")

package io.bluetape4k.exposed.r2dbc.tests

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.core.Key
import org.jetbrains.exposed.v1.core.statements.StatementInterceptor
import org.jetbrains.exposed.v1.core.transactions.nullableTransactionScope
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.transactions.transactionManager
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** 현재 R2DBC 트랜잭션의 fixture입니다. commit 후에도 유지됩니다. */
var currentR2dbcTestDbFixture by nullableTransactionScope<R2dbcTestDbFixture<*>>()
    internal set

internal object R2dbcFixtureInterceptor: StatementInterceptor {
    override fun keepUserDataInTransactionStoreOnCommit(userData: Map<Key<*>, Any?>): Map<Key<*>, Any?> =
        userData.filterValues { it is R2dbcTestDbFixture<*> || it is TestDB }
}

private class R2dbcFixtureEntry(val fixture: R2dbcTestDbFixture<*>) {
    val active = atomic(true)
}

private class R2dbcFixtureEntries(val entries: List<R2dbcFixtureEntry>):
    AbstractCoroutineContextElement(Key) {
    companion object Key: CoroutineContext.Key<R2dbcFixtureEntries>
}

private val legacyFixtures = ConcurrentHashMap<TestDB, R2dbcTestDbFixture<TestDB>>()

internal fun r2dbcFixtureFor(testDB: TestDB): R2dbcTestDbFixture<TestDB> =
    legacyFixtures.computeIfAbsent(testDB) { legacy ->
        r2dbcTestDbFixture(legacy, { configure ->
            legacy.beforeConnection()
            val previous = legacy.db
            try {
                legacy.connect { configure() }
            } finally {
                legacy.db = previous
            }
        }, legacy.afterTestFinished).also { it.legacy = legacy }
    }

internal suspend fun <K> R2dbcTestDbFixture<K>.executeSuspending(
    configure: (DatabaseConfig.Builder.() -> Unit)?,
    statement: suspend R2dbcTransaction.(K) -> Unit,
) {
    val previous = currentCoroutineContext()[R2dbcFixtureEntries]?.entries.orEmpty()
    check(previous.none { it.fixture === this && it.active.value }) {
        "Nested invocation of the same R2DBC fixture is unsupported"
    }
    semaphore.withPermit {
        currentCoroutineContext().ensureActive()
        val entry = R2dbcFixtureEntry(this)
        try {
            withContext(R2dbcFixtureEntries(previous + entry)) {
                val selected = configured(configure)
                legacy?.db = selected
                var failure: Throwable? = null
                try {
                    suspendTransaction(
                        db = selected,
                        transactionIsolation = checkNotNull(selected.transactionManager.defaultIsolationLevel),
                    ) {
                        maxAttempts = 1
                        registerInterceptor(R2dbcFixtureInterceptor)
                        currentR2dbcTestDbFixture = this@executeSuspending
                        currentTestDB = legacy
                        statement(key)
                    }
                } catch (thrown: CancellationException) {
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
            legacy?.db = database
            entry.active.value = false
        }
    }
}
