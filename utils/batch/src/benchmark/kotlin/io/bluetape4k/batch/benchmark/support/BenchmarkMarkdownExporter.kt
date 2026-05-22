package io.bluetape4k.batch.benchmark.support

import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.createDirectories
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * benchmark 결과 Markdown 문서를 생성하는 도우미입니다.
 *
 * 현재는 DB별 상세 문서와 허브 문서를 생성하는 최소 구현을 제공합니다.
 * JSON 결과가 존재하지 않아도 문서 골격과 실행 방법을 일관되게 갱신할 수 있습니다.
 */
internal object BenchmarkMarkdownExporter {

    fun writeAll(projectDir: Path, reportDir: Path? = null) {
        val docsDir = projectDir.resolve("docs/benchmark").createDirectories()
        val reportRoot = reportDir ?: projectDir.resolve("build/reports/benchmarks")
        writeHubReadme(docsDir, reportDir)
        writeHubReadmeKo(docsDir, reportDir)
        writeDatabaseDoc(
            docsDir.resolve("h2.md"),
            title = "# H2 Benchmark Details",
            databaseName = "H2",
            profileRows = listOf(
                "| JDBC | `./gradlew :exposed-batch:h2JdbcBenchmark` | `H2JdbcBatchBenchmark` |",
                "| R2DBC | `./gradlew :exposed-batch:h2R2dbcBenchmark` | `H2R2dbcBatchBenchmark` |",
            ),
            notes = listOf(
                "H2는 인메모리 DB이므로 네트워크 왕복 비용 없이 JDBC/R2DBC 차이를 비교할 수 있습니다.",
                "H2 profile은 로컬 실행과 빠른 검증에 적합한 기준선 역할을 합니다.",
            ),
            reportSet = loadDatabaseReportSet(reportRoot, jdbcProfile = "h2Jdbc", r2dbcProfile = "h2R2dbc"),
        )
        writeDatabaseDoc(
            docsDir.resolve("postgresql.md"),
            title = "# PostgreSQL Benchmark Details",
            databaseName = "PostgreSQL",
            profileRows = listOf(
                "| JDBC | `./gradlew :exposed-batch:postgresJdbcBenchmark` | `PostgreSqlJdbcBatchBenchmark` |",
                "| R2DBC | `./gradlew :exposed-batch:postgresR2dbcBenchmark` | `PostgreSqlR2dbcBatchBenchmark` |",
            ),
            notes = listOf(
                "PostgreSQL benchmark는 Testcontainers를 자동 기동하도록 설계되어 있습니다.",
                "JDBC vs R2DBC 격차를 가장 명확하게 보여주는 대표 DB입니다.",
            ),
            reportSet = loadDatabaseReportSet(reportRoot, jdbcProfile = "postgresJdbc", r2dbcProfile = "postgresR2dbc"),
        )
        writeDatabaseDoc(
            docsDir.resolve("mysql.md"),
            title = "# MySQL Benchmark Details",
            databaseName = "MySQL",
            profileRows = listOf(
                "| JDBC | `./gradlew :exposed-batch:mysqlJdbcBenchmark` | `MySqlJdbcBatchBenchmark` |",
                "| R2DBC | `./gradlew :exposed-batch:mysqlR2dbcBenchmark` | `MySqlR2dbcBatchBenchmark` |",
            ),
            notes = listOf(
                "MySQL benchmark도 Testcontainers 자동 기동을 전제로 합니다.",
                "대규모 batch에서 JDBC + Virtual Threads의 이점이 드러나는 비교 대상입니다.",
            ),
            reportSet = loadDatabaseReportSet(reportRoot, jdbcProfile = "mysqlJdbc", r2dbcProfile = "mysqlR2dbc"),
        )
    }

    private fun writeHubReadme(docsDir: Path, reportDir: Path?) {
        docsDir.resolve("README.md").writeText(
            """
            # exposed-batch Benchmark Hub

            [한국어](./README.ko.md) | English

            This directory contains DB-specific benchmark notes for the new `kotlinx-benchmark` setup.

            ## Scope

            - Databases: H2, PostgreSQL, MySQL
            - Drivers: JDBC with Virtual Threads, R2DBC
            - Scenarios: `seedBenchmark`, `endToEndBatchJobBenchmark`
            - Parameters: `dataSize = 1000/10000/100000`, `poolSize = 10/30/60`, `parallelism = 1/4/8`

            ## Benchmark Profiles

            | DB | JDBC | R2DBC | Details |
            |----|------|-------|---------|
            | H2 | `h2JdbcBenchmark` | `h2R2dbcBenchmark` | [H2](./h2.md) |
            | PostgreSQL | `postgresJdbcBenchmark` | `postgresR2dbcBenchmark` | [PostgreSQL](./postgresql.md) |
            | MySQL | `mysqlJdbcBenchmark` | `mysqlR2dbcBenchmark` | [MySQL](./mysql.md) |

            ## Comparison Focus

            The primary comparison is **JDBC vs R2DBC** for each database, split into:

            1. `seedBenchmark` — source row insert cost
            2. `endToEndBatchJobBenchmark` — full batch job execution cost

            ## Comparison Map

            ![Batch benchmark comparison map](../../../docs/images/readme-diagrams/utils-batch-benchmark-map-01.png)

            ## Notes

            - Detailed numeric rows are generated per DB document.
            - `generateBenchmarkDocs` currently writes the benchmark hub and DB detail skeletons.
            - Report directory: `${reportDir?.toString() ?: "not provided"}`.
            - Full PostgreSQL/MySQL runs can be generated later without changing the README link structure.
            """.trimIndent() + "\n"
        )
    }

    private fun writeHubReadmeKo(docsDir: Path, reportDir: Path?) {
        docsDir.resolve("README.ko.md").writeText(
            """
            # exposed-batch 벤치마크 허브

            한국어 | [English](./README.md)

            이 디렉토리는 `kotlinx-benchmark` 기반으로 재구성한 batch benchmark의 DB별 상세 문서를 모읍니다.

            ## 범위

            - 데이터베이스: H2, PostgreSQL, MySQL
            - 드라이버: JDBC with Virtual Threads, R2DBC
            - 시나리오: `seedBenchmark`, `endToEndBatchJobBenchmark`
            - 파라미터: `dataSize = 1000/10000/100000`, `poolSize = 10/30/60`, `parallelism = 1/4/8`

            ## Benchmark 프로파일

            | DB | JDBC | R2DBC | 상세 문서 |
            |----|------|-------|-----------|
            | H2 | `h2JdbcBenchmark` | `h2R2dbcBenchmark` | [H2](./h2.md) |
            | PostgreSQL | `postgresJdbcBenchmark` | `postgresR2dbcBenchmark` | [PostgreSQL](./postgresql.md) |
            | MySQL | `mysqlJdbcBenchmark` | `mysqlR2dbcBenchmark` | [MySQL](./mysql.md) |

            ## 비교 초점

            가장 중요한 비교 축은 **JDBC vs R2DBC**이며, 각 DB에서 다음 두 측정값을 분리해 봅니다.

            1. `seedBenchmark` — source row 적재 비용
            2. `endToEndBatchJobBenchmark` — 전체 batch job 실행 비용

            ## Comparison Map

            ![Batch benchmark comparison map](../../../docs/images/readme-diagrams/utils-batch-benchmark-map-01.png)

            ## 참고

            - 상세 수치 표는 DB별 문서에 둡니다.
            - `generateBenchmarkDocs` 는 현재 benchmark 허브와 DB별 상세 문서 골격을 생성합니다.
            - Report directory: `${reportDir?.toString() ?: "not provided"}`
            - PostgreSQL/MySQL full run 결과는 나중에 추가해도 링크 구조는 그대로 유지됩니다.
            """.trimIndent() + "\n"
        )
    }

    private fun writeDatabaseDoc(
        path: Path,
        title: String,
        databaseName: String,
        profileRows: List<String>,
        notes: List<String>,
        reportSet: DatabaseReportSet,
    ) {
        val content = buildString {
            appendLine(title)
            appendLine()
            appendLine("[Benchmark Hub](./README.md) · [벤치마크 허브](./README.ko.md)")
            appendLine()
            appendLine("## Profiles")
            appendLine()
            appendLine("| Driver | Gradle Task | Benchmark Class |")
            appendLine("|--------|-------------|-----------------|")
            profileRows.forEach { appendLine(it) }
            appendLine()
            appendLine("## Comparison Dimensions")
            appendLine()
            appendLine("| Scenario | JDBC vs R2DBC 비교 축 | 고정/가변 파라미터 |")
            appendLine("|----------|-----------------------|-------------------|")
            appendLine("| Seed | source row insert throughput / time | dataSize = 1000, 10000, 100000 · poolSize = 10, 30, 60 |")
            appendLine("| End-to-End | full batch job throughput / time | dataSize = 1000, 10000, 100000 · poolSize = 10, 30, 60 · parallelism = 1, 4, 8 |")
            appendLine()
            appendLine("## Result Tables")
            appendLine()
            appendLine("### Seed Benchmark — JDBC vs R2DBC by dataSize / poolSize")
            appendLine()
            appendLine("| Driver | dataSize | poolSize | ops/sec | avg ms |")
            appendLine("|--------|----------|----------|--------:|-------:|")
            appendSeedRows(reportSet.seedRows, "JDBC")
            appendSeedRows(reportSet.seedRows, "R2DBC")
            appendLine()
            appendLine("### End-to-End Benchmark — JDBC vs R2DBC by dataSize / poolSize / parallelism")
            appendLine()
            appendLine("| Driver | dataSize | poolSize | parallelism | ops/sec | avg ms |")
            appendLine("|--------|----------|----------|-------------|--------:|-------:|")
            appendEndToEndRows(reportSet.endToEndRows, "JDBC")
            appendEndToEndRows(reportSet.endToEndRows, "R2DBC")
            appendLine()
            val chartSlug = databaseName.lowercase()
            appendLine("## Comparison Charts")
            appendLine()
            appendLine(buildChartHint(reportSet.hasMeasuredData))
            appendLine()
            appendLine("### Seed — dataSize comparison (poolSize=30)")
            appendLine()
            appendLine("![${databaseName.lowercase()} seed dataSize chart](../../../docs/images/readme-charts/utils-batch-$chartSlug-seed-datasize-chart-01.png)")
            appendLine()
            appendLine("### Seed — poolSize comparison (dataSize=10000)")
            appendLine()
            appendLine("![${databaseName.lowercase()} seed poolSize chart](../../../docs/images/readme-charts/utils-batch-$chartSlug-seed-poolsize-chart-01.png)")
            appendLine()
            appendLine("### End-to-End — parallelism comparison (dataSize=10000, poolSize=30)")
            appendLine()
            appendLine("![${databaseName.lowercase()} end-to-end parallelism chart](../../../docs/images/readme-charts/utils-batch-$chartSlug-e2e-parallelism-chart-01.png)")
            appendLine()
            appendLine("## Notes")
            appendLine()
            notes.forEach { appendLine("- $it") }
            appendLine()
            appendLine("## Generated Result Rows")
            appendLine()
            appendLine(buildGeneratedRowsNote(reportSet))
        }
        path.writeText(content)
    }

    private fun String.driverLabel(): String =
        if (contains("R2dbc", ignoreCase = true)) "R2DBC" else "JDBC"

    private fun String.scenarioName(): String =
        if (substringAfterLast('.').contains("seed", ignoreCase = true)) "seed" else "endToEnd"

    private fun loadDatabaseReportSet(reportRoot: Path, jdbcProfile: String, r2dbcProfile: String): DatabaseReportSet {
        val reports = listOfNotNull(
            latestReportEntries(reportRoot, jdbcProfile),
            latestReportEntries(reportRoot, r2dbcProfile),
        ).flatten()

        val rows = reports.map { report ->
            BenchmarkRow(
                driver = report.benchmark.driverLabel(),
                scenario = report.benchmark.scenarioName(),
                dataSize = report.params["dataSize"]?.toIntOrNull() ?: 0,
                poolSize = report.params["poolSize"]?.toIntOrNull() ?: 0,
                parallelism = report.params["parallelism"]?.toIntOrNull() ?: 1,
                opsPerSecond = report.score,
            )
        }

        return DatabaseReportSet(
            seedRows = rows.filter { it.scenario == "seed" }.sortedWith(compareBy({ it.driver }, { it.dataSize }, { it.poolSize })),
            endToEndRows = rows.filter { it.scenario == "endToEnd" }.sortedWith(compareBy({ it.driver }, { it.dataSize }, { it.poolSize }, { it.parallelism })),
        )
    }

    private fun latestReportEntries(reportRoot: Path, profile: String): List<JsonBenchmarkEntry>? {
        val profileDir = reportRoot.resolve(profile)
        if (!profileDir.isDirectory()) return null
        val latestDir = profileDir.listDirectoryEntries()
            .filter { it.isDirectory() }
            .maxByOrNull { it.name }
            ?: return null
        val reportFile = latestDir.resolve("benchmark.json")
        if (!reportFile.isRegularFile()) return null
        return parseBenchmarkEntries(reportFile)
    }

    private fun parseBenchmarkEntries(reportFile: Path): List<JsonBenchmarkEntry> {
        val text = reportFile.readText()
        val objects = mutableListOf<String>()
        var depth = 0
        var start = -1
        for ((index, char) in text.withIndex()) {
            when (char) {
                '{' -> {
                    if (depth == 0) start = index
                    depth++
                }
                '}' -> {
                    depth--
                    if (depth == 0 && start >= 0) {
                        objects += text.substring(start, index + 1)
                        start = -1
                    }
                }
            }
        }
        return objects.mapNotNull { chunk ->
            val benchmark = Regex("\"benchmark\"\\s*:\\s*\"([^\"]+)\"").find(chunk)?.groupValues?.get(1) ?: return@mapNotNull null
            val score = Regex("\"score\"\\s*:\\s*([0-9.Ee+-]+)").find(chunk)?.groupValues?.get(1)?.toDoubleOrNull() ?: return@mapNotNull null
            val scoreUnit = Regex("\"scoreUnit\"\\s*:\\s*\"([^\"]+)\"").find(chunk)?.groupValues?.get(1) ?: "ops/s"
            val paramsBlock = Regex("\"params\"\\s*:\\s*\\{([\\s\\S]*?)\\}").find(chunk)?.groupValues?.get(1).orEmpty()
            val params = Regex("\"([^\"]+)\"\\s*:\\s*\"([^\"]+)\"").findAll(paramsBlock).associate { it.groupValues[1] to it.groupValues[2] }
            JsonBenchmarkEntry(benchmark = benchmark, params = params, score = score, scoreUnit = scoreUnit)
        }
    }

    private fun StringBuilder.appendSeedRows(rows: List<BenchmarkRow>, driver: String) {
        val targetRows = rows.filter { it.driver == driver }
        if (targetRows.isEmpty()) {
            appendLine("| $driver | 1000 | 10 | pending | pending |")
            appendLine("| $driver | 10000 | 30 | pending | pending |")
            appendLine("| $driver | 100000 | 60 | pending | pending |")
            return
        }
        targetRows.forEach { row ->
            appendLine("| ${row.driver} | ${row.dataSize} | ${row.poolSize} | ${row.opsPerSecond.formatScore()} | ${row.avgMs().formatMs()} |")
        }
    }

    private fun StringBuilder.appendEndToEndRows(rows: List<BenchmarkRow>, driver: String) {
        val targetRows = rows.filter { it.driver == driver }
        if (targetRows.isEmpty()) {
            appendLine("| $driver | 1000 | 10 | 1 | pending | pending |")
            appendLine("| $driver | 10000 | 30 | 4 | pending | pending |")
            appendLine("| $driver | 100000 | 60 | 8 | pending | pending |")
            return
        }
        targetRows.forEach { row ->
            appendLine("| ${row.driver} | ${row.dataSize} | ${row.poolSize} | ${row.parallelism} | ${row.opsPerSecond.formatScore()} | ${row.avgMs().formatMs()} |")
        }
    }

    private fun buildChartHint(hasMeasuredData: Boolean): String =
        if (hasMeasuredData) {
            "> These chart images use the latest JSON benchmark report values (ops/sec). Use the tables above for avg ms."
        } else {
            "> Chart image paths are reserved for generated benchmark reports. Re-run the benchmark tasks and `generateBenchmarkDocs` after JSON reports exist."
        }

    private fun buildGeneratedRowsNote(reportSet: DatabaseReportSet): String =
        if (reportSet.hasMeasuredData) {
            "> Latest JSON benchmark reports were found and rendered into the tables/graphs above. Re-run the corresponding benchmark tasks and `generateBenchmarkDocs` to refresh the numbers."
        } else {
            "> JSON benchmark reports are not available in the current worktree yet, so this document records the benchmark contract, task mapping, and graph layout first. Numeric rows can be appended by rerunning the corresponding benchmark tasks and `generateBenchmarkDocs`."
        }

    private fun Double.formatScore(): String = String.format(Locale.ROOT, "%.3f", this)

    private fun Double.formatMs(): String = String.format(Locale.ROOT, "%.3f", this)

    private data class JsonBenchmarkEntry(
        val benchmark: String,
        val params: Map<String, String>,
        val score: Double,
        val scoreUnit: String,
    )

    private data class BenchmarkRow(
        val driver: String,
        val scenario: String,
        val dataSize: Int,
        val poolSize: Int,
        val parallelism: Int,
        val opsPerSecond: Double,
    ) {
        fun avgMs(): Double = if (opsPerSecond <= 0.0) 0.0 else 1000.0 / opsPerSecond
    }

    private data class DatabaseReportSet(
        val seedRows: List<BenchmarkRow>,
        val endToEndRows: List<BenchmarkRow>,
    ) {
        val hasMeasuredData: Boolean
            get() = seedRows.isNotEmpty() || endToEndRows.isNotEmpty()
    }
}

