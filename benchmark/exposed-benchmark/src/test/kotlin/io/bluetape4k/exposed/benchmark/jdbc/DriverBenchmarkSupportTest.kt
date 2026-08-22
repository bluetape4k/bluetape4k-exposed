package io.bluetape4k.exposed.benchmark.jdbc

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test

class DriverBenchmarkSupportTest {

    @Test
    fun `matrix covers two drivers two row counts and three pool sizes`() {
        val cases = jdbcDriverBenchmarkCases()

        cases.size shouldBeEqualTo 12
        cases.map { it.driver }.toSet() shouldBeEqualTo
            setOf(JdbcBenchmarkDriver.POSTGRESQL, JdbcBenchmarkDriver.MYSQL_V8)
        cases.map { it.rowCount }.toSet() shouldBeEqualTo setOf(1_000, 10_000)
        cases.map { it.poolSize }.toSet() shouldBeEqualTo setOf(1, 2, 4)
        cases.all { it.maxConcurrency == 2 }.shouldBeTrue()
    }

    @Test
    fun `range builder covers every generated id once`() {
        val ranges = buildDriverBenchmarkRanges(rowCount = 10, rangeCount = 4)
        val covered = ranges.flatMap { range ->
            val start = range.lowerInclusive ?: 1L
            val end = range.upperExclusive ?: 11L
            (start until end).toList()
        }

        covered shouldBeEqualTo (1L..10L).toList()
    }

    @Test
    fun `invalid matrix inputs fail before fixture startup`() {
        assertFailsWith<IllegalArgumentException> {
            JdbcDriverBenchmarkCase(JdbcBenchmarkDriver.POSTGRESQL, rowCount = 0, poolSize = 1)
        }
        assertFailsWith<IllegalArgumentException> {
            buildDriverBenchmarkRanges(rowCount = 3, rangeCount = 4)
        }
    }
}
