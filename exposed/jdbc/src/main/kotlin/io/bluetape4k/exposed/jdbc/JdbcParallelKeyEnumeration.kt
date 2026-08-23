package io.bluetape4k.exposed.jdbc

import io.bluetape4k.concurrent.virtualthread.VirtualFuture
import io.bluetape4k.concurrent.virtualthread.VirtualThreadExecutor
import io.bluetape4k.concurrent.virtualthread.virtualFuture
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.EntityIDColumnType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.IdTable
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.statements.StatementContext
import org.jetbrains.exposed.v1.core.statements.StatementInterceptor
import org.jetbrains.exposed.v1.core.statements.api.PreparedStatementApi
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.statements.api.JdbcPreparedStatementApi
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.transactions.transactionManager
import java.sql.Connection
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.jvm.JvmSynthetic

/**
 * JDBC PK enumeration에 사용하는 반개방 구간입니다.
 *
 * 구간은 `[lowerInclusive, upperExclusive)`로 해석합니다. 첫 구간의 lower bound와
 * 마지막 구간의 upper bound만 생략할 수 있으며, 두 경계를 모두 생략한 구간은
 * 허용하지 않습니다. 여러 구간은 선언 순서대로 정렬되고 서로 겹치지 않아야 합니다.
 *
 * @param ID PK 타입
 * @param lowerInclusive 포함할 최솟값. 첫 구간에서는 `null`일 수 있습니다.
 * @param upperExclusive 제외할 최댓값. 마지막 구간에서는 `null`일 수 있습니다.
 */
data class JdbcKeyRange<ID: Any>(
    val lowerInclusive: ID? = null,
    val upperExclusive: ID? = null,
)

/**
 * Virtual Thread 기반 JDBC key enumeration의 실행 옵션입니다.
 *
 * 병렬 경로는 호출자가 명시적으로 선택해야 하며, 결과를 range별 및 최종 [List]로
 * materialize합니다. 메모리 사용량이 우선이면 기존 sequential streaming loader를
 * 사용해야 합니다. [executor]를 넘긴 경우 생성·종료 책임은 caller에게 있고, 이 API는
 * executor를 닫지 않습니다. 실패 또는 caller interrupt가 발생하면 이미 시작한 child의
 * transaction과 connection cleanup이 끝날 때까지 기다린 뒤 원인을 반환합니다. 따라서
 * interrupt를 무시하는 child는 실제 종료 시간만큼 실패 전파를 지연할 수 있습니다.
 * `maxConcurrency`는 caller JDBC connection pool의 유효한 최대 connection 수보다
 * 작거나 같게 선택해야 합니다.
 *
 * [comparator]는 range 경계의 선언 순서와 database PK 정렬 순서가 일치할 때만
 * 사용해야 합니다. 생략하면 PK 값이 [Comparable]인지 확인한 뒤 natural order를
 * 사용합니다.
 */
data class JdbcParallelKeyEnumerationOptions<ID: Any>(
    val maxConcurrency: Int = DEFAULT_MAX_CONCURRENCY,
    val executor: ExecutorService? = VirtualThreadExecutor,
    val database: Database? = null,
    val transactionIsolation: Int? = null,
    val readOnly: Boolean = true,
    val comparator: Comparator<in ID>? = null,
) {
    init {
        require(maxConcurrency > 0) {
            "maxConcurrency는 0보다 커야 합니다. maxConcurrency=$maxConcurrency"
        }
    }

    companion object {
        private const val DEFAULT_MAX_CONCURRENCY = 4
    }
}

/**
 * 명시한 PK range를 독립 JDBC transaction으로 병렬 열거합니다.
 *
 * 각 range는 하나의 transaction과 connection을 사용하고, 작업은 입력 range 순서로
 * 결과를 병합합니다. 전체 열거는 하나의 읽기 일관성 기준을 보장하지 않으며, range 사이의
 * insert/delete는 관찰될 수도 있습니다. 기본 sequential loader 경로는 변경하지
 * 않습니다.
 *
 * @param table PK를 읽을 Exposed [IdTable]
 * @param ranges 서로 겹치지 않고 오름차순으로 선언한 PK range 목록
 * @param options 실행 executor, database, isolation, readOnly와 동시성 제한
 * @return range 선언 순서로 병합한 ID 목록
 * @throws IllegalArgumentException range가 겹치거나 역순이거나 executor가 종료된 경우
 * @throws IllegalStateException database를 확인할 수 없는 경우
 */
fun <ID: Any> parallelJdbcKeyEnumeration(
    table: IdTable<ID>,
    ranges: List<JdbcKeyRange<ID>>,
    options: JdbcParallelKeyEnumerationOptions<ID> = JdbcParallelKeyEnumerationOptions(),
): List<ID> =
    parallelJdbcKeyEnumeration(table, ranges, options) { sourceTable, range ->
        sourceTable.readParallelKeyRange(range)
    }

/**
 * Test source set에서 transaction/future lifecycle을 검증하기 위한 range reader 주입
 * overload입니다. production caller는 세 인자 public overload를 사용해야 합니다.
 */
@Suppress("ThrowsCount", "TooGenericExceptionCaught")
@JvmSynthetic
internal fun <ID: Any> parallelJdbcKeyEnumeration(
    table: IdTable<ID>,
    ranges: List<JdbcKeyRange<ID>>,
    options: JdbcParallelKeyEnumerationOptions<ID>,
    rangeReader: JdbcTransaction.(IdTable<ID>, JdbcKeyRange<ID>) -> List<ID>,
): List<ID> =
    parallelJdbcKeyEnumeration(table, ranges, options) { sourceTable, range, _ ->
        rangeReader(sourceTable, range)
    }

/**
 * test source capability probe가 child lifecycle을 확인할 수 있는 internal overload입니다.
 *
 * 이 handle은 public options/result API에 포함하지 않습니다. active JDBC connection과
 * Exposed prepared statement를 단조 증가 child generation에 묶어, 오래된 registration이
 * 새로운 child registration을 지우지 못하게 합니다.
 */
@JvmSynthetic
@Suppress("TooGenericExceptionCaught")
internal fun <ID: Any> parallelJdbcKeyEnumeration(
    table: IdTable<ID>,
    ranges: List<JdbcKeyRange<ID>>,
    options: JdbcParallelKeyEnumerationOptions<ID>,
    rangeReader: JdbcTransaction.(IdTable<ID>, JdbcKeyRange<ID>, JdbcEnumerationChildHandle) -> List<ID>,
): List<ID> {
    if (ranges.isEmpty()) {
        return emptyList()
    }

    val comparator = options.comparator ?: naturalComparator()
    validateRanges(ranges, comparator)

    val database =
        options.database
            ?: TransactionManager.currentOrNull()?.db
            ?: TransactionManager.defaultDatabase
            ?: error("JDBC parallel key enumeration requires an explicit Database or a default database")
    val executor = options.executor ?: VirtualThreadExecutor
    require(!executor.isShutdown && !executor.isTerminated) {
        "ExecutorService is already shutdown."
    }

    val permits = Semaphore(options.maxConcurrency)
    val children = ArrayList<JdbcEnumerationChild<List<ID>>>(ranges.size)

    return try {
        ranges.forEach { range ->
            checkCompletedFailures(children)
            permits.acquire()

            try {
                children +=
                    submitJdbcEnumerationChild(
                        executor = executor,
                        db = database,
                        transactionIsolation = options.transactionIsolation,
                        readOnly = options.readOnly,
                        permits = permits,
                    ) { childHandle ->
                        rangeReader(table, range, childHandle)
                    }
            } catch (cause: Throwable) {
                permits.release()
                throw cause
            }
        }

        children.flatMap { it.future.await() }
    } catch (cause: Throwable) {
        val cleanupInterrupted = cancelAndAwait(children)
        if (cause is InterruptedException || cleanupInterrupted) {
            Thread.currentThread().interrupt()
        }
        throw unwrapExecutionFailure(cause)
    }
}

private fun <ID: Any> validateRanges(
    ranges: List<JdbcKeyRange<ID>>,
    comparator: Comparator<in ID>,
) {
    ranges.forEachIndexed { index, range ->
        require(range.lowerInclusive != null || range.upperExclusive != null) {
            "JdbcKeyRange는 lowerInclusive 또는 upperExclusive 중 하나 이상을 가져야 합니다. index=$index"
        }

        val lower = range.lowerInclusive
        val upper = range.upperExclusive
        if (lower != null && upper != null) {
            require(comparator.compare(lower, upper) < 0) {
                "JdbcKeyRange의 lowerInclusive는 upperExclusive보다 작아야 합니다. index=$index"
            }
        }

        if (index > 0) {
            val previous = ranges[index - 1]
            val previousUpper = previous.upperExclusive
            require(previousUpper != null && lower != null) {
                "range 목록은 첫 구간의 lower bound와 마지막 구간의 upper bound만 생략할 수 있습니다. index=$index"
            }
            require(comparator.compare(previousUpper, lower) <= 0) {
                "JdbcKeyRange는 겹치지 않는 오름차순으로 선언해야 합니다. index=$index"
            }
        }
    }
}

private fun <ID: Any> checkCompletedFailures(children: List<JdbcEnumerationChild<List<ID>>>) {
    children.asSequence()
        .map { it.future }
        .filter { it.isDone }
        .forEach { it.await() }
}

private fun <T> submitJdbcEnumerationChild(
    executor: ExecutorService,
    db: Database,
    transactionIsolation: Int?,
    readOnly: Boolean,
    permits: Semaphore,
    statement: JdbcTransaction.(JdbcEnumerationChildHandle) -> T,
): JdbcEnumerationChild<T> {
    val completion = CountDownLatch(1)
    val lifecycle = AtomicInteger(CHILD_NEW)
    val handle = JdbcEnumerationChildHandle()
    val future =
        virtualFuture(executor = executor) {
            if (!lifecycle.compareAndSet(CHILD_NEW, CHILD_RUNNING)) {
                throw CancellationException("JDBC enumeration child was cancelled before start")
            }
            try {
                val isolationLevel = transactionIsolation ?: db.transactionManager.defaultIsolationLevel
                transaction(
                    db = db,
                    transactionIsolation = isolationLevel,
                    readOnly = readOnly,
                ) {
                    val connectionRegistration =
                        handle.registerConnection(
                            this.connection.connection as? Connection
                                ?: error("JDBC enumeration requires a java.sql.Connection"),
                        )
                    val interceptor = JdbcEnumerationStatementInterceptor(handle)
                    registerInterceptor(interceptor)
                    try {
                        statement(handle)
                    } finally {
                        try {
                            unregisterInterceptor(interceptor)
                        } finally {
                            handle.clearCurrentStatement()
                            handle.clearConnection(connectionRegistration)
                        }
                    }
                }
            } finally {
                if (lifecycle.compareAndSet(CHILD_RUNNING, CHILD_COMPLETED)) {
                    permits.release()
                    completion.countDown()
                }
            }
        }

    return JdbcEnumerationChild(
        future = future,
        completion = completion,
        lifecycle = lifecycle,
        permits = permits,
        handle = handle,
    )
}

private fun <T> cancelAndAwait(children: List<JdbcEnumerationChild<T>>): Boolean {
    children.forEach { child ->
        child.future.cancel(true)
        child.cancelBeforeStart()
    }

    var interrupted = false
    children.forEach { child ->
        while (true) {
            try {
                child.completion.await()
                break
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
    }
    return interrupted
}

private class JdbcEnumerationChild<T>(
    val future: VirtualFuture<T>,
    val completion: CountDownLatch,
    private val lifecycle: AtomicInteger,
    private val permits: Semaphore,
    val handle: JdbcEnumerationChildHandle,
) {
    fun cancelBeforeStart() {
        if (lifecycle.compareAndSet(CHILD_NEW, CHILD_CANCELLED)) {
            permits.release()
            completion.countDown()
        }
    }
}

private val nextJdbcEnumerationGeneration = AtomicLong()

/**
 * 하나의 enumeration child가 사용하는 internal identity registry입니다.
 *
 * registration 제거는 [AtomicReference.compareAndSet]로 generation과 object identity를
 * 함께 확인합니다. 따라서 늦게 도착한 clear가 다른 child에 이미 등록된 connection이나
 * statement를 제거하지 못합니다.
 */
internal class JdbcEnumerationChildHandle internal constructor(
    val generation: Long = nextJdbcEnumerationGeneration.incrementAndGet(),
) {
    private val activeConnection = AtomicReference<JdbcEnumerationHandleRegistration<Connection>?>(null)
    private val activeStatement = AtomicReference<JdbcEnumerationHandleRegistration<JdbcPreparedStatementApi>?>(null)

    internal fun registerConnection(connection: Connection): JdbcEnumerationHandleRegistration<Connection> {
        val registration = JdbcEnumerationHandleRegistration(generation, connection)
        check(activeConnection.compareAndSet(null, registration)) {
            "JDBC enumeration connection is already registered for generation=$generation"
        }
        return registration
    }

    internal fun clearConnection(registration: JdbcEnumerationHandleRegistration<Connection>): Boolean =
        activeConnection.compareAndSet(registration, null)

    internal fun currentConnection(expectedGeneration: Long): Connection? =
        activeConnection.get()
            ?.takeIf { it.generation == expectedGeneration }
            ?.value

    internal fun registerStatement(
        statement: JdbcPreparedStatementApi,
    ): JdbcEnumerationHandleRegistration<JdbcPreparedStatementApi> {
        val registration = JdbcEnumerationHandleRegistration(generation, statement)
        // Exposed는 statement 실패 시 afterExecution을 호출하지 않습니다. active registration을
        // 교체하면 range reader가 복구 후 다음 statement를 실행할 수 있고, 정확한 object identity
        // 비교를 통한 제거는 이전 callback이 새 registration을 지우지 못하게 합니다.
        activeStatement.set(registration)
        return registration
    }

    internal fun clearStatement(registration: JdbcEnumerationHandleRegistration<JdbcPreparedStatementApi>): Boolean =
        activeStatement.compareAndSet(registration, null)

    internal fun clearStatement(statement: JdbcPreparedStatementApi): Boolean {
        val registration = activeStatement.get() ?: return false
        return registration.value === statement && activeStatement.compareAndSet(registration, null)
    }

    internal fun currentStatement(expectedGeneration: Long): JdbcPreparedStatementApi? =
        activeStatement.get()
            ?.takeIf { it.generation == expectedGeneration }
            ?.value

    internal fun clearCurrentStatement() {
        activeStatement.set(null)
    }
}

internal data class JdbcEnumerationHandleRegistration<T>(
    val generation: Long,
    val value: T,
)

private class JdbcEnumerationStatementInterceptor(
    private val handle: JdbcEnumerationChildHandle,
): StatementInterceptor {

    @Suppress("UNUSED_PARAMETER")
    override fun afterStatementPrepared(
        transaction: Transaction,
        preparedStatement: PreparedStatementApi,
    ) {
        (preparedStatement as? JdbcPreparedStatementApi)?.let(handle::registerStatement)
    }

    @Suppress("UNUSED_PARAMETER")
    override fun afterExecution(
        transaction: Transaction,
        contexts: List<StatementContext>,
        executedStatement: PreparedStatementApi,
    ) {
        (executedStatement as? JdbcPreparedStatementApi)?.let(handle::clearStatement)
    }
}

private const val CHILD_NEW = 0
private const val CHILD_RUNNING = 1
private const val CHILD_CANCELLED = 2
private const val CHILD_COMPLETED = 3

private fun unwrapExecutionFailure(cause: Throwable): Throwable =
    when (cause) {
        is ExecutionException -> cause.cause ?: cause
        else -> cause
    }

@Suppress("UNCHECKED_CAST")
private fun <ID: Any> naturalComparator(): Comparator<ID> =
    Comparator { left, right ->
        val comparable = left as? Comparable<Any>
            ?: error("PK range boundary requires Comparable or an explicit comparator")
        comparable.compareTo(right)
    }

private fun <ID: Any> IdTable<ID>.readParallelKeyRange(range: JdbcKeyRange<ID>): List<ID> {
    @Suppress("UNCHECKED_CAST")
    val rawIdColumn = (id.columnType as EntityIDColumnType<ID>).idColumn as Column<Comparable<Any>>
    val lower = range.lowerInclusive
    val upper = range.upperExclusive
    val predicate: Op<Boolean> =
        when {
            lower != null && upper != null ->
                (rawIdColumn greaterEq lower.asComparableKey()) and (rawIdColumn less upper.asComparableKey())
            lower != null -> rawIdColumn greaterEq lower.asComparableKey()
            upper != null -> rawIdColumn less upper.asComparableKey()
            else -> error("JdbcKeyRange must have a lowerInclusive or upperExclusive boundary")
        }

    return select(id)
        .where { predicate }
        .orderBy(id, SortOrder.ASC)
        .map { it[id].value }
}

@Suppress("UNCHECKED_CAST")
private fun <ID: Any> ID.asComparableKey(): Comparable<Any> =
    this as? Comparable<Any>
        ?: error("PK range boundary requires Comparable")
