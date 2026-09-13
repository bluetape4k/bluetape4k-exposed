package io.bluetape4k.exposed.clickhouse

import io.bluetape4k.exposed.clickhouse.support.RowBinaryConnectionProviderFixture
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * RowBinary와 JDBC fallback의 bounded fixture benchmark를 raw JSON으로 저장합니다.
 *
 * 실제 ClickHouse wire/driver buffer를 대신한다고 주장하지 않도록 기본 selector에서
 * 비활성화하고, `-PclickhouseV2Benchmark=true`와 run 번호를 명시한 별도 process에서
 * 실행합니다. 논리 행 수와 실제 fixture 측정 행 수를 JSON에 함께 남깁니다.
 */
@EnabledIfSystemProperty(named = "clickhouseV2Benchmark", matches = "true")
class ClickHouseRowBinaryBenchmarkTest {

    @Test
    fun `writes 36 scenario benchmark records for this process`() {
        val run = System.getProperty("clickhouseV2BenchmarkRun", "1").toInt()
        require(run in 1..3) { "clickhouseV2BenchmarkRun must be 1, 2, or 3" }

        val outputDirectory = Path.of(
            System.getProperty(
                "clickhouseV2BenchmarkOutputDir",
                "docs/benchmarks/clickhouse-v2-rowbinary",
            ),
        )
        Files.createDirectories(outputDirectory)
        PATHS.forEach { path ->
            val records = SCENARIOS.map { scenario -> measureScenario(path, scenario) }
            val output = outputDirectory.resolve("$path-run-$run.json")
            Files.writeString(output, renderJson(path, run, records))
        }
    }

    private fun measureScenario(path: String, scenario: Scenario): Measurement {
        val measuredRows = min(scenario.rowCount, MEASURED_ROWS)
        val fixture = RowBinaryConnectionProviderFixture()
        val executor = ClickHouseRowBinaryExecutor(
            provider = fixture,
            options = ClickHouseRowBinaryOptions(
                enabled = path == ROW_BINARY,
                maxRowsPerFlush = scenario.maxRowsPerFlush,
            ),
        )
        val sql = sqlFor(scenario.rowShape)
        repeat(WARMUP_ITERATIONS) {
            executor.executeBatch(sql, rows(measuredRows, scenario.rowShape))
        }

        val rawElapsedNs = ArrayList<Long>(MEASUREMENT_ITERATIONS)
        var peakHeapBytes = 0L
        var acceptedCount = 0
        var acceptedCountMayBeIncomplete = false
        repeat(MEASUREMENT_ITERATIONS) {
            val before = usedHeapBytes()
            val started = System.nanoTime()
            val result = executor.executeBatch(sql, rows(measuredRows, scenario.rowShape))
            val elapsed = System.nanoTime() - started
            val after = usedHeapBytes()
            rawElapsedNs += elapsed
            peakHeapBytes = max(peakHeapBytes, max(0L, after - before))
            acceptedCount += result.acceptedCount
            acceptedCountMayBeIncomplete = acceptedCountMayBeIncomplete || result.acceptedCountMayBeIncomplete
        }

        val medianElapsedNs = rawElapsedNs.sorted()[rawElapsedNs.size / 2]
        return Measurement(
            rowCount = scenario.rowCount,
            maxRowsPerFlush = scenario.maxRowsPerFlush,
            rowShape = scenario.rowShape,
            path = path,
            logicalRows = scenario.rowCount,
            measuredRows = measuredRows,
            rawElapsedNs = rawElapsedNs,
            medianElapsedNs = medianElapsedNs,
            medianRowsPerSecond = measuredRows * 1_000_000_000.0 / medianElapsedNs,
            firstByteNs = medianElapsedNs,
            acceptedCount = acceptedCount,
            acceptedCountMayBeIncomplete = acceptedCountMayBeIncomplete,
            peakHeapBytes = peakHeapBytes,
        )
    }

    private fun rows(count: Int, shape: String): Iterable<ClickHouseRowBinaryRow> = Iterable {
        object: Iterator<ClickHouseRowBinaryRow> {
            private var index = 0

            override fun hasNext(): Boolean = index < count

            override fun next(): ClickHouseRowBinaryRow {
                if (!hasNext()) throw NoSuchElementException()
                val id = index++
                return ClickHouseRowBinaryRow { statement ->
                    statement.setInt(1, id)
                    if (shape == WIDE) {
                        repeat(WIDE_COLUMNS - 1) { column -> statement.setInt(column + 2, id + column) }
                    }
                }
            }
        }
    }

    private fun usedHeapBytes(): Long {
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }

    private fun sqlFor(shape: String): String =
        if (shape == WIDE) {
            "INSERT INTO benchmark_events (id, c2, c3, c4, c5, c6, c7, c8) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
        } else {
            "INSERT INTO benchmark_events (id) VALUES (?)"
        }

    private fun renderJson(path: String, run: Int, records: List<Measurement>): String = buildString {
        appendLine("{")
        appendLine("  \"issue\": 867,")
        appendLine("  \"path\": \"${escape(path)}\",")
        appendLine("  \"run\": $run,")
        appendLine("  \"warmupIterations\": $WARMUP_ITERATIONS,")
        appendLine("  \"measurementIterations\": $MEASUREMENT_ITERATIONS,")
        val measurementScope =
            "bounded in-memory provider fixture; logical row counts are scenario labels"
        appendLine("  \"measurementScope\": \"" + escape(measurementScope) + "\",")
        appendLine("  \"provenance\": {")
        appendLine("    \"implementationSha\": \"${escape(System.getenv("GIT_COMMIT") ?: "local")}\",")
        appendLine("    \"gitDirty\": true,")
        appendLine("    \"driverArtifact\": \"com.clickhouse:clickhouse-jdbc:0.9.9\",")
        appendLine("    \"jdk\": \"${escape(System.getProperty("java.version"))}\",")
        appendLine("    \"os\": \"${escape(System.getProperty("os.name"))}\",")
        appendLine("    \"architecture\": \"${escape(System.getProperty("os.arch"))}\",")
        appendLine("    \"container\": \"not-run\"")
        appendLine("  },")
        appendLine("  \"records\": [")
        records.forEachIndexed { index, record ->
            append("    ")
            append(record.toJson())
            if (index < records.lastIndex) append(',')
            appendLine()
        }
        appendLine("  ]")
        appendLine("}")
    }

    private fun escape(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")

    private data class Scenario(val rowCount: Int, val maxRowsPerFlush: Int, val rowShape: String)

    private data class Measurement(
        val rowCount: Int,
        val maxRowsPerFlush: Int,
        val rowShape: String,
        val path: String,
        val logicalRows: Int,
        val measuredRows: Int,
        val rawElapsedNs: List<Long>,
        val medianElapsedNs: Long,
        val medianRowsPerSecond: Double,
        val firstByteNs: Long,
        val acceptedCount: Int,
        val acceptedCountMayBeIncomplete: Boolean,
        val peakHeapBytes: Long,
    ) {
        fun toJson(): String = buildString {
            append("{\"rowCount\":$rowCount,\"maxRowsPerFlush\":$maxRowsPerFlush,")
            append("\"rowShape\":\"$rowShape\",\"path\":\"$path\",")
            append("\"logicalRows\":$logicalRows,\"measuredRows\":$measuredRows,")
            append("\"rawElapsedNs\":[${rawElapsedNs.joinToString(",")}],")
            append("\"medianElapsedNs\":$medianElapsedNs,")
            append("\"medianRowsPerSecond\":${"%.6f".format(Locale.ROOT, medianRowsPerSecond)},")
            append("\"firstByteNs\":$firstByteNs,\"acceptedCount\":$acceptedCount,")
            append("\"acceptedCountMayBeIncomplete\":$acceptedCountMayBeIncomplete,")
            append("\"peakHeapBytes\":$peakHeapBytes}")
        }
    }

    private companion object {
        const val ROW_BINARY = "rowbinary"
        const val JDBC_FALLBACK = "jdbc-fallback"
        const val NARROW = "narrow"
        const val WIDE = "wide"
        const val MEASURED_ROWS = 2_048
        const val WIDE_COLUMNS = 8
        const val WARMUP_ITERATIONS = 2
        const val MEASUREMENT_ITERATIONS = 5
        val PATHS = listOf(ROW_BINARY, JDBC_FALLBACK)
        val SCENARIOS = listOf(10_000, 100_000, 1_000_000).flatMap { rowCount ->
            listOf(256, 1_024, 4_096).flatMap { maxRowsPerFlush ->
                listOf(NARROW, WIDE).map { rowShape ->
                    Scenario(rowCount, maxRowsPerFlush, rowShape)
                }
            }
        }
    }
}
