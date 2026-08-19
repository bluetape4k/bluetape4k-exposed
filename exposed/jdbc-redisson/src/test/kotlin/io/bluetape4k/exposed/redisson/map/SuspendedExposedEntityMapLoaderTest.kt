package io.bluetape4k.exposed.redisson.map

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.exposed.tests.AbstractExposedTest
import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.exposed.tests.withTablesSuspending
import io.bluetape4k.junit5.coroutines.runSuspendIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SqlLogger
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.StatementContext
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletionException

/** [SuspendedExposedEntityMapLoader]의 bounded AsyncIterator 계약을 검증한다. */
class SuspendedExposedEntityMapLoaderTest: AbstractExposedTest() {

    private data class LoaderEntity(val id: Long, val name: String)

    private data class ComparableCustomId(val value: String): Comparable<ComparableCustomId> {
        override fun compareTo(other: ComparableCustomId): Int = value.compareTo(other.value)
    }

    private object LoaderTable: LongIdTable("suspended_redisson_loader_test") {
        val name = varchar("name", 64)
    }

    private object MissingLoaderTable: LongIdTable("suspended_redisson_missing_loader_test")

    private fun ResultRow.toLoaderEntity(): LoaderEntity =
        LoaderEntity(
            id = this[LoaderTable.id].value,
            name = this[LoaderTable.name],
        )

    @Test
    fun `keyset capability는 표준 scalar만 허용하고 custom Comparable ID는 fallback으로 분류한다`() {
        42L.isKeysetScalar().shouldBeTrue()
        ComparableCustomId("custom").isKeysetScalar().shouldBeFalse()
    }

    @Test
    fun `loadAllKeys - AsyncIterator가 keyset page를 순서대로 소비한다`() = runSuspendIO {
        withTablesSuspending(TestDB.H2, LoaderTable) {
            repeat(5) { index ->
                LoaderTable.insert { it[name] = "user-$index" }
            }
            commit()

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val loader = SuspendedExposedEntityMapLoader(
                    entityTable = LoaderTable,
                    scope = scope,
                    batchSize = 2,
                    toEntity = { toLoaderEntity() },
                )

                val iterator = loader.loadAllKeys()
                val ids = buildList {
                    while (iterator.hasNext().await() == true) {
                        add(iterator.next().await())
                    }
                }

                ids shouldHaveSize 5
                ids shouldBeEqualTo ids.sorted()
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun `loadAllKeys - sparse ID를 순서대로 중복 없이 반환한다`() = runSuspendIO {
        withTablesSuspending(TestDB.H2, LoaderTable) {
            val initialIds =
                List(5) { index ->
                    LoaderTable.insert { it[name] = "user-$index" } get LoaderTable.id
                }.map { it.value }
            LoaderTable.deleteWhere { LoaderTable.id eq initialIds[1] }
            commit()

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val loader = SuspendedExposedEntityMapLoader(
                    entityTable = LoaderTable,
                    scope = scope,
                    batchSize = 2,
                    toEntity = { toLoaderEntity() },
                )
                val iterator = loader.loadAllKeys()
                val ids = buildList {
                    while (iterator.hasNext().await() == true) {
                        add(iterator.next().await())
                    }
                }

                ids shouldBeEqualTo initialIds.filterNot { it == initialIds[1] }
                ids.distinct() shouldBeEqualTo ids
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun `loadAllKeys - large fixture는 page cardinality와 query count를 bounded하게 유지한다`() = runSuspendIO {
        val sqlStatements = mutableListOf<String>()
        withTablesSuspending(
            TestDB.H2,
            LoaderTable,
            configure = {
                sqlLogger = object : SqlLogger {
                    override fun log(context: StatementContext, transaction: Transaction) {
                        sqlStatements += context.sql(transaction)
                    }
                }
            },
        ) {
            repeat(101) { index ->
                LoaderTable.insert { it[name] = "large-user-$index" }
            }
            commit()
            sqlStatements.clear()

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val loader =
                    SuspendedExposedEntityMapLoader(
                        entityTable = LoaderTable,
                        scope = scope,
                        batchSize = 16,
                        toEntity = { toLoaderEntity() },
                    )
                val iterator = loader.loadAllKeys()
                val ids = buildList {
                    while (iterator.hasNext().await() == true) {
                        add(iterator.next().await())
                    }
                }
                val selects = sqlStatements.filter { it.trimStart().startsWith("SELECT", ignoreCase = true) }

                ids shouldHaveSize 101
                selects.size shouldBeEqualTo 7
                selects.all { it.contains("limit", ignoreCase = true) }.shouldBeTrue()
                selects.drop(1).all { it.contains(">") }.shouldBeTrue()
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun `loadAllKeys - DB 오류는 AsyncIterator에 전달된다`() = runSuspendIO {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val loader = SuspendedExposedEntityMapLoader(
                entityTable = MissingLoaderTable,
                scope = scope,
                batchSize = 2,
                toEntity = { toLoaderEntity() },
            )
            val failure = runCatching { loader.loadAllKeys().hasNext().await() }.exceptionOrNull()
            (failure != null).shouldBeTrue()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `loadAllKeys - fatal Error는 channel cause와 coroutine exception handler에 유지된다`() = runSuspendIO {
        withTablesSuspending(TestDB.H2, LoaderTable) {
            val fatal = AssertionError("fatal producer failure")
            val observedFatal = CompletableDeferred<Throwable>()
            val exceptionHandler = CoroutineExceptionHandler { _, cause -> observedFatal.complete(cause) }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)
            try {
                val loader =
                    SuspendedEntityMapLoader<Long, LoaderEntity>(
                        loadByIdFromDB = { null },
                        loadAllIdsFromDB = { throw fatal },
                        scope = scope,
                    )
                val failure = runCatching { loader.loadAllKeys().hasNext().await() }.exceptionOrNull()
                val cause = (failure as? CompletionException)?.cause ?: failure
                cause.shouldBeInstanceOf<AssertionError>()
                cause.message shouldBeEqualTo fatal.message
                withTimeout(1_000) {
                    val observed = observedFatal.await()
                    observed.shouldBeInstanceOf<AssertionError>()
                    observed.message shouldBeEqualTo fatal.message
                }
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun `loadAllKeys - producer 오류가 기본 및 일반 scope를 오염시키지 않는다`() = runSuspendIO {
        suspend fun assertEnumerationRecovery(scope: CoroutineScope) {
            val failingLoader =
                SuspendedEntityMapLoader<Long, LoaderEntity>(
                    loadByIdFromDB = { null },
                    loadAllIdsFromDB = { error("producer failure") },
                    scope = scope,
                )
            val failure = runCatching { failingLoader.loadAllKeys().hasNext().await() }.exceptionOrNull()
            val cause = (failure as? CompletionException)?.cause ?: failure
            cause.shouldBeInstanceOf<IllegalStateException>()

            val succeedingLoader =
                SuspendedEntityMapLoader<Long, LoaderEntity>(
                    loadByIdFromDB = { null },
                    loadAllIdsFromDB = { channel -> channel.send(7L) },
                    scope = scope,
                )
            val iterator = succeedingLoader.loadAllKeys()
            iterator.hasNext().await().shouldBeTrue()
            iterator.next().await() shouldBeEqualTo 7L
            iterator.hasNext().await().shouldBeFalse()
            yield()
            scope.coroutineContext[Job]?.children?.toList().shouldHaveSize(0)
        }

        val defaultFailure =
            SuspendedEntityMapLoader<Long, LoaderEntity>(
                loadByIdFromDB = { null },
                loadAllIdsFromDB = { error("producer failure") },
            )
        val defaultException = runCatching { defaultFailure.loadAllKeys().hasNext().await() }.exceptionOrNull()
        val defaultCause = (defaultException as? CompletionException)?.cause ?: defaultException
        defaultCause.shouldBeInstanceOf<IllegalStateException>()

        val defaultSuccess =
            SuspendedEntityMapLoader<Long, LoaderEntity>(
                loadByIdFromDB = { null },
                loadAllIdsFromDB = { channel -> channel.send(7L) },
            )
        val defaultIterator = defaultSuccess.loadAllKeys()
        defaultIterator.hasNext().await().shouldBeTrue()
        defaultIterator.next().await() shouldBeEqualTo 7L

        val ordinaryScope = CoroutineScope(Job() + Dispatchers.IO)
        try {
            assertEnumerationRecovery(ordinaryScope)
        } finally {
            ordinaryScope.cancel()
        }
    }

    @Test
    fun `loadAllKeys - producer timeout 원인은 AsyncIterator에 전달된다`() = runSuspendIO {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            var observedMaxAttempts = 0
            val loader = SuspendedEntityMapLoader<Long, LoaderEntity>(
                loadByIdFromDB = { null },
                loadAllIdsFromDB = {
                    observedMaxAttempts = TransactionManager.currentOrNull()?.maxAttempts ?: -1
                    withTimeout(1) {
                        awaitCancellation()
                    }
                },
                scope = scope,
            )

            val failure = runCatching { loader.loadAllKeys().hasNext().await() }.exceptionOrNull()
            val cause = (failure as? CompletionException)?.cause ?: failure
            cause.shouldBeInstanceOf<TimeoutCancellationException>()
            observedMaxAttempts shouldBeEqualTo 1
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `loadAllKeys - caller scope 취소는 producer를 취소한다`() = runSuspendIO {
        withTablesSuspending(TestDB.H2, LoaderTable) {
            repeat(5) { index ->
                LoaderTable.insert { it[name] = "user-$index" }
            }
            commit()

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val loader = SuspendedExposedEntityMapLoader(
                entityTable = LoaderTable,
                scope = scope,
                batchSize = 2,
                toEntity = { toLoaderEntity() },
            )
            val iterator = loader.loadAllKeys()
            iterator.hasNext().await().shouldBeTrue()
            iterator.next().await()

            scope.cancel()
            val failure = runCatching { iterator.hasNext().await() }.exceptionOrNull()
            val cause = (failure as? CompletionException)?.cause ?: failure
            cause.shouldBeInstanceOf<CancellationException>()
            scope.coroutineContext[Job]?.children?.toList()?.forEach { it.join() }
            scope.coroutineContext[Job]?.children?.toList().shouldHaveSize(0)
        }
    }
}
