package io.bluetape4k.exposed.clickhouse

import io.bluetape4k.assertions.shouldBeEqualTo
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * CI가 제품 테스트와 분리해 ClickHouse 컨테이너 readiness를 확인하는 최소 probe입니다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClickHouseReadinessTest: AbstractClickHouseTest() {

    @Test
    fun `ClickHouse readiness query succeeds`() {
        transaction(db) {
            exec("SELECT 1") { resultSet ->
                resultSet.next()
                resultSet.getInt(1)
            }
        } shouldBeEqualTo 1
    }
}
