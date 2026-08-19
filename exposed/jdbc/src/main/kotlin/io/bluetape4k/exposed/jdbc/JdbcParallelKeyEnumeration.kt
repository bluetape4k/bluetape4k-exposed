package io.bluetape4k.exposed.jdbc

import io.bluetape4k.concurrent.virtualthread.VirtualFuture
import io.bluetape4k.concurrent.virtualthread.VirtualThreadExecutor
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.EntityIDColumnType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.IdTable
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Semaphore
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
 * executor를 닫지 않습니다. `maxConcurrency`는 caller JDBC connection pool의
 * 유효한 최대 connection 수보다 작거나 같게 선택해야 합니다.
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
    val futures = ArrayList<VirtualFuture<List<ID>>>(ranges.size)

    return try {
        ranges.forEach { range ->
            checkCompletedFailures(futures)
            try {
                permits.acquire()
            } catch (cause: InterruptedException) {
                Thread.currentThread().interrupt()
                throw cause
            }

            try {
                val future =
                    virtualThreadJdbcTransactionAsync(
                        executor = executor,
                        db = database,
                        transactionIsolation = options.transactionIsolation,
                        readOnly = options.readOnly,
                    ) {
                        rangeReader(table, range)
                    }
                futures += future
                future.toCompletableFuture().whenComplete { _, _ -> permits.release() }
            } catch (cause: Throwable) {
                permits.release()
                throw cause
            }
        }

        futures.flatMap { it.await() }
    } catch (cause: Throwable) {
        if (cause is InterruptedException) {
            Thread.currentThread().interrupt()
        }
        cancelAndAwait(futures)
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

private fun <ID: Any> checkCompletedFailures(futures: List<VirtualFuture<List<ID>>>) {
    futures.asSequence()
        .filter { it.isDone }
        .forEach { it.await() }
}

private fun <T> cancelAndAwait(futures: List<VirtualFuture<T>>) {
    futures.forEach { it.cancel(true) }
    futures.forEach { future ->
        runCatching { future.await() }
    }
}

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
