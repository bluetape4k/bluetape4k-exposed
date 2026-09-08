package io.bluetape4k.exposed.clickhouse

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * 호출 시 조회 결과를 트랜잭션 안에서 모두 수집하여 반환합니다.
 *
 * ```kotlin
 * val names = queryList(db) { Events.selectAll().limit(100).map { it[Events.eventName] } }
 * ```
 *
 * 결과 크기에 비례하는 메모리를 사용하므로 Query 또는 서버에서 결과 상한을 설정해야 합니다.
 * [suspendTransaction]의 트랜잭션 참여·재시도 정책을 따릅니다. 블록이 재실행될 수 있으므로
 * 조회 외 부수 효과를 넣지 마세요. ClickHouse의 DML 원자성이나 롤백을 보장하지 않습니다.
 *
 * @param db 호출자가 소유하는 데이터베이스입니다.
 * @param dispatcher 블로킹 JDBC 조회를 실행할 디스패처입니다.
 * @param block 트랜잭션 안에서 전체 수집할 조회 결과입니다.
 */
suspend fun <T> queryList(
    db: Database,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    block: JdbcTransaction.() -> Iterable<T>,
): List<T> = suspendTransaction(db, dispatcher) { block().toList() }

/**
 * 수집할 때마다 독립된 트랜잭션에서 조회하고 행을 하나씩 변환하여 전달합니다.
 *
 * ```kotlin
 * queryFlow(db, query = { Events.selectAll().limit(100) }, mapper = {
 *     it[Events.eventName]
 * }).take(10).collect { name -> process(name) }
 * ```
 *
 * 전체 결과를 List로 수집하지 않으며 생산자는 전달 대기 중인 항목 하나만 보관합니다.
 * 소비자 항목, 드라이버 내부 버퍼와 호출자가 추가한 Flow buffer는 이 상한에 포함되지 않습니다.
 * 쿼리는 재시도하지 않으며 이미 전달한 행을 다시 보내지 않습니다.
 * 외부 트랜잭션과 연결 지역 상태를 상속하지 않으므로 보안 조건을 [query]에 명시해야 합니다.
 *
 * [query]는 매번 새 Query를 반환해야 하며 [mapper]는 짧게 실행하고 트랜잭션 밖에서도
 * 유효한 값을 반환해야 합니다. 추가 SQL이나 지연 로딩 자원을 mapper에서 사용하지 마세요.
 * 취소는 블로킹 JDBC 호출을 즉시 중단하지 않습니다. 호출자가 유한한 연결 획득·소켓·조회
 * timeout을 설정해야 합니다. 정상 완료와 취소 모두 내부 자원 정리가 끝난 후 반환합니다.
 * 데이터베이스·풀·디스패처는 호출자 소유이며 이 함수가 닫지 않습니다.
 * ClickHouse의 DML 원자성이나 롤백은 보장하지 않습니다.
 *
 * @param db 호출자가 소유하는 데이터베이스입니다.
 * @param dispatcher 블로킹 조회와 매핑을 실행할 디스패처입니다.
 * @param query 독립 트랜잭션에서 수집마다 새로 만드는 조회입니다.
 * @param mapper 현재 행을 트랜잭션과 무관한 값으로 변환합니다.
 */
fun <T> queryFlow(
    db: Database,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    query: JdbcTransaction.() -> Query,
    mapper: (ResultRow) -> T,
): Flow<T> = clickHouseQueryFlow(db, dispatcher, query, mapper)

/**
 * ClickHouse에서 suspend 트랜잭션을 실행합니다.
 *
 * ```kotlin
 * val db = ClickHouseDatabase.connect("jdbc:clickhouse://host:8123/default")
 *
 * // suspend 트랜잭션
 * val result = suspendTransaction(db) {
 *     exec("SELECT count() FROM events") { rs -> rs.next(); rs.getLong(1) }
 * }
 *
 * // Virtual Thread 사용
 * val vtDispatcher = Executors.newVirtualThreadPerTaskExecutor().asCoroutineDispatcher()
 * val result = suspendTransaction(db, vtDispatcher) {
 *     exec("SELECT 1") { rs -> rs.next(); rs.getInt(1) }
 * }
 * ```
 *
 * ClickHouse 트랜잭션 원자성 없음:
 * - ClickHouse는 autocommit 모드로 동작하며 원자성 보장이 없습니다.
 * - 블록 중간 실패 시 앞선 DML은 롤백되지 않습니다.
 * - rollback() 호출은 no-op입니다.
 * - nested transaction 호출 허용되나 원자성 없음
 * - multi-statement 쓰기 시 부분 반영 위험
 *
 * @param db ClickHouse 데이터베이스 연결
 * @param dispatcher 블로킹 JDBC 호출을 실행할 디스패처 (기본값: [Dispatchers.IO])
 * @param block 트랜잭션 블록
 */
suspend fun <T> suspendTransaction(
    db: Database,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    block: JdbcTransaction.() -> T,
    // ClickHouse JDBC 호출은 블로킹 I/O이므로, 코루틴 기본 디스패처(Main/Default)를 점유하지 않도록
    // Dispatchers.IO(또는 Virtual Thread 전용 디스패처)로 컨텍스트를 전환합니다.
): T = withContext(dispatcher) {
    try {
        transaction(db) { block() }
    } catch (e: CancellationException) {
        // 코루틴 취소는 반드시 재전파해야 합니다 — 삼키면 구조적 동시성이 깨집니다.
        throw e
    }
}

/**
 * ClickHouse 쿼리 결과를 [Flow]로 반환합니다.
 *
 * 구현상 JDBC `ResultSet` 수명과 Exposed 트랜잭션 경계를 안전하게 유지하기 위해
 * 트랜잭션 내부에서 결과를 `List`로 materialize 한 뒤 순차적으로 emit 합니다.
 * 따라서 소비 API는 [Flow]이지만, 엄밀한 의미의 row-by-row 스트리밍은 아닙니다.
 * 중간 규모 결과를 코루틴 파이프라인으로 연결할 때 적합하며,
 * 매우 큰 결과셋은 페이지네이션 또는 전용 배치 전략을 별도로 고려해야 합니다.
 *
 * ```kotlin
 * val db = ClickHouseDatabase.connect("jdbc:clickhouse://host:8123/default")
 *
 * queryFlow(db) {
 *     Events.selectAll().where { Events.region eq "kr" }
 * }.collect { row ->
 *     println(row[Events.eventId])
 * }
 * ```
 *
 * ClickHouse 트랜잭션 원자성 없음:
 * - ClickHouse는 autocommit 모드로 동작하며 원자성 보장이 없습니다.
 * - 블록 중간 실패 시 앞선 DML은 롤백되지 않습니다.
 * - rollback() 호출은 no-op입니다.
 * - nested transaction 호출 허용되나 원자성 없음
 * - multi-statement 쓰기 시 부분 반영 위험
 *
 * @param db ClickHouse 데이터베이스 연결
 * @param dispatcher 블로킹 JDBC 호출을 실행할 디스패처 (기본값: [Dispatchers.IO])
 * @param block 조회 결과를 반환하는 트랜잭션 블록. 반환된 [Iterable]은 트랜잭션 안에서 즉시 materialize 됩니다.
 */
fun <T> queryFlow(
    db: Database,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    block: JdbcTransaction.() -> Iterable<T>,
): Flow<T> = flow {
    // ClickHouse JDBC 호출은 블로킹 I/O이므로 Dispatchers.IO로 전환하고,
    // ResultSet 수명(트랜잭션 경계 내)과 Flow emit 경계가 겹치지 않도록
    // 트랜잭션 내에서 List로 완전히 materialize한 뒤 방출합니다.
    val items = try {
        withContext(dispatcher) { transaction(db) { block().toList() } }
    } catch (e: CancellationException) {
        // 코루틴 취소는 반드시 재전파해야 합니다 — 삼키면 구조적 동시성이 깨집니다.
        throw e
    }
    items.forEach { emit(it) }
}
