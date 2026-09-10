package io.bluetape4k.exposed.clickhouse

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.exposed.clickhouse.support.RowBinaryConnectionProviderFixture
import org.junit.jupiter.api.Test
import java.sql.SQLException
import java.sql.Statement

/**
 * ClickHouse JDBC V2 RowBinary writer의 선택·fallback·수명주기 계약을 고정합니다.
 *
 * 이 테스트는 실제 ClickHouse 컨테이너를 사용하지 않고, 연결 profile과 JDBC 호출
 * 순서를 관찰하는 fixture만 사용합니다.
 */
class ClickHouseRowBinaryTest {

    @Test
    fun `options default to disabled and reject invalid flush bounds`() {
        ClickHouseRowBinaryOptions().enabled shouldBeEqualTo false
        ClickHouseRowBinaryOptions(maxRowsPerFlush = 1).maxRowsPerFlush shouldBeEqualTo 1

        assertFailsWith<IllegalArgumentException> { ClickHouseRowBinaryOptions(maxRowsPerFlush = 0) }
        assertFailsWith<IllegalArgumentException> { ClickHouseRowBinaryOptions(maxRowsPerFlush = -1) }
    }

    @Test
    fun `provider opens independent enabled and disabled connection profiles`() {
        val fixture = RowBinaryConnectionProviderFixture()

        val rowBinary = fixture.open(rowBinaryEnabled = true)
        val jdbc = fixture.open(rowBinaryEnabled = false)

        fixture.profileOf(rowBinary) shouldBeEqualTo true
        fixture.profileOf(jdbc) shouldBeEqualTo false
        fixture.openedProfiles shouldBeEqualTo listOf(true, false)
        fixture.distinctConnections()

        rowBinary.close()
        jdbc.close()
        fixture.assertClosedExactlyOnce()
    }

    @Test
    fun `missing provider fails closed when row binary is enabled`() {
        val executor = ClickHouseRowBinaryExecutor(
            provider = null,
            options = ClickHouseRowBinaryOptions(enabled = true),
        )

        val error = assertFailsWith<ClickHouseRowBinaryExecutor.UnsupportedConfiguration> {
            executor.executeBatch("INSERT INTO events VALUES (?)", listOf(Row { it.setInt(1, 1) }))
        }

        error.reasonCode shouldBeEqualTo "PROVIDER_ABSENT"
    }

    @Test
    fun `eligible simple insert uses row binary profile and preserves update counts`() {
        val fixture = RowBinaryConnectionProviderFixture(executionCounts = listOf(intArrayOf(1, 1)))
        val executor = ClickHouseRowBinaryExecutor(
            provider = fixture,
            options = ClickHouseRowBinaryOptions(enabled = true, maxRowsPerFlush = 8),
        )

        val result = executor.executeBatch(
            "INSERT INTO events (id, value) VALUES (?, ?)",
            listOf(
                Row { it.setInt(1, 1); it.setString(2, "one") },
                Row { it.setInt(1, 2); it.setString(2, "two") },
            ),
        )

        result.path shouldBeEqualTo ClickHouseBatchPath.ROW_BINARY
        result.updateCounts shouldBeEqualTo listOf(1, 1)
        result.acceptedCount shouldBeEqualTo 2
        result.acceptedCountMayBeIncomplete shouldBeEqualTo false
        fixture.openedProfiles shouldBeEqualTo listOf(true)
        fixture.assertClosedExactlyOnce()
    }

    @Test
    fun `unsupported sql selects disabled fallback before first byte`() {
        val fixture = RowBinaryConnectionProviderFixture(executionCounts = listOf(intArrayOf(1)))
        val executor = ClickHouseRowBinaryExecutor(
            provider = fixture,
            options = ClickHouseRowBinaryOptions(enabled = true),
        )

        val result = executor.executeBatch(
            "INSERT INTO events SELECT ?",
            listOf(Row { it.setInt(1, 1) }),
        )

        result.path shouldBeEqualTo ClickHouseBatchPath.JDBC_FALLBACK
        fixture.openedProfiles shouldBeEqualTo listOf(false)
        fixture.fallbackEvents.single().beforeFirstByte shouldBeEqualTo true
        fixture.fallbackEvents.single().reasonCode shouldBeEqualTo "INSERT_SELECT"
    }

    @Test
    fun `disabled option always uses jdbc fallback`() {
        val fixture = RowBinaryConnectionProviderFixture(executionCounts = listOf(intArrayOf(1)))
        val executor = ClickHouseRowBinaryExecutor(
            provider = fixture,
            options = ClickHouseRowBinaryOptions(enabled = false),
        )

        val result = executor.executeBatch(
            "INSERT INTO events (id) VALUES (?)",
            listOf(Row { it.setInt(1, 1) }),
        )

        result.path shouldBeEqualTo ClickHouseBatchPath.JDBC_FALLBACK
        fixture.openedProfiles shouldBeEqualTo listOf(false)
        fixture.fallbackEvents.single().reasonCode shouldBeEqualTo "ROW_BINARY_DISABLED"
    }

    @Test
    fun `setter failure keeps original exception and does not fallback or reuse executor`() {
        val original = SQLException("setter failed")
        val fixture = RowBinaryConnectionProviderFixture(setterFailure = original)
        val executor = ClickHouseRowBinaryExecutor(
            provider = fixture,
            options = ClickHouseRowBinaryOptions(enabled = true),
        )

        val thrown = assertFailsWith<SQLException> {
            executor.executeBatch("INSERT INTO events VALUES (?)", listOf(Row { it.setInt(1, 1) }))
        }

        thrown shouldBeEqualTo original
        fixture.openedProfiles shouldBeEqualTo listOf(true)
        fixture.fallbackEvents shouldBeEqualTo emptyList()
        fixture.assertClosedExactlyOnce()

        val unusable = assertFailsWith<ClickHouseRowBinaryExecutor.UnsupportedConfiguration> {
            executor.executeBatch("INSERT INTO events VALUES (?)", listOf(Row { it.setInt(1, 2) }))
        }
        unusable.reasonCode shouldBeEqualTo "EXECUTOR_UNUSABLE"
    }

    @Test
    fun `partial counts preserve driver sentinels and mark accepted count incomplete`() {
        val fixture = RowBinaryConnectionProviderFixture(
            executionCounts = listOf(intArrayOf(1, Statement.SUCCESS_NO_INFO), intArrayOf(Statement.EXECUTE_FAILED)),
        )
        val executor = ClickHouseRowBinaryExecutor(
            provider = fixture,
            options = ClickHouseRowBinaryOptions(enabled = true, maxRowsPerFlush = 2),
        )

        val result = executor.executeBatch(
            "INSERT INTO events (id) VALUES (?)",
            listOf(
                Row { it.setInt(1, 1) },
                Row { it.setInt(1, 2) },
                Row { it.setInt(1, 3) },
            ),
        )

        result.path shouldBeEqualTo ClickHouseBatchPath.ROW_BINARY
        result.updateCounts shouldBeEqualTo listOf(1, Statement.SUCCESS_NO_INFO, Statement.EXECUTE_FAILED)
        result.acceptedCount shouldBeEqualTo 1
        result.acceptedCountMayBeIncomplete shouldBeEqualTo true
    }

    @Test
    fun `empty input does not open a connection`() {
        val fixture = RowBinaryConnectionProviderFixture()
        val executor = ClickHouseRowBinaryExecutor(
            provider = fixture,
            options = ClickHouseRowBinaryOptions(enabled = true),
        )

        val result = executor.executeBatch("INSERT INTO events VALUES (?)", emptyList())

        result.updateCounts shouldBeEqualTo emptyList()
        result.acceptedCount shouldBeEqualTo 0
        fixture.openedProfiles shouldBeEqualTo emptyList()
    }

    private fun interface Row: ClickHouseRowBinaryRow
}
