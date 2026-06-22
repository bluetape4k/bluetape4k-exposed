package io.bluetape4k.exposed.benchmark.support

import java.io.File
import java.time.LocalDate
import kotlin.math.ln
import kotlin.math.max

fun main(args: Array<String>) {
    require(args.size == 3) {
        "Usage: BenchmarkDocsGenerator <benchmarkProjectDir> <rootDir> <benchmarkReportDir>"
    }
    BenchmarkDocsGenerator(
        projectDir = File(args[0]),
        rootDir = File(args[1]),
        reportDir = File(args[2]),
    ).generate()
}

class BenchmarkDocsGenerator(
    private val projectDir: File,
    private val rootDir: File,
    private val reportDir: File,
) {
    fun generate() {
        val results = BenchmarkResultReader(reportDir).read()
        val chartDir = rootDir.resolve("docs/images/readme-charts")
        chartDir.mkdirs()
        val chart = chartDir.resolve("exposed-benchmark-suite.svg")
        chart.writeText(BenchmarkChartRenderer.render(results), Charsets.UTF_8)

        projectDir.resolve("README.md").writeText(readmeEn(results), Charsets.UTF_8)
        projectDir.resolve("README.ko.md").writeText(readmeKo(results), Charsets.UTF_8)
    }

    private fun readmeEn(results: List<BenchmarkResult>): String = """
        |# Exposed Benchmark Suite
        |
        |Dedicated kotlinx-benchmark module for Exposed JDBC, R2DBC, custom ID tables, and cache strategies.
        |
        |## Scenarios
        |
        || Area | Benchmark task | What it measures |
        ||---|---|---|
        || JDBC vs R2DBC | `./gradlew :benchmark-exposed-benchmark:jdbcR2dbcBenchmark` | JDBC platform-thread select, JDBC virtual-thread dispatch, and R2DBC suspend transaction select throughput |
        || Custom ID tables | `./gradlew :benchmark-exposed-benchmark:idTablesBenchmark` | Bulk insert and select throughput for `UUIDTable`, `TimebasedUUIDTable`, `UlidTable`, Base62 UUIDv7, Snowflake, KSUID, and KSUID millis tables |
        || Local and near cache | `./gradlew :benchmark-exposed-benchmark:cacheBenchmark` | Caffeine hit, near-cache hit, and read-through miss behavior |
        || Redis cache clients | `./gradlew :benchmark-exposed-benchmark:redisCacheBenchmark -Pbenchmark.parameters.redisUri=redis://127.0.0.1:6379` | Lettuce and Redisson remote cache get throughput |
        || Smoke | `./gradlew :benchmark-exposed-benchmark:smokeBenchmark` | Short H2-only benchmark run that excludes Redis |
        |
        |## Results
        |
        |Generated on ${LocalDate.now()} from `${reportDir.relativeToOrSelf(projectDir)}`.
        |
        |${resultsTable(results)}
        |
        |![Exposed benchmark chart](../../docs/images/readme-charts/exposed-benchmark-suite.svg)
        |
        |## Notes
        |
        |- Redis benchmarks are intentionally separated from smoke because they require a reachable Redis server.
        |- H2 keeps default verification cheap; database-specific benchmark profiles can be added without changing the module boundary.
        |- Re-run `./gradlew :benchmark-exposed-benchmark:generateBenchmarkDocs` after benchmark runs to refresh tables and charts.
        |
    """.trimMargin()

    private fun readmeKo(results: List<BenchmarkResult>): String = """
        |# Exposed Benchmark Suite
        |
        |Exposed JDBC, R2DBC, custom ID table, cache 전략을 독립적으로 실행하는 kotlinx-benchmark 모듈입니다.
        |
        |## 시나리오
        |
        || 영역 | Benchmark task | 측정 대상 |
        ||---|---|---|
        || JDBC vs R2DBC | `./gradlew :benchmark-exposed-benchmark:jdbcR2dbcBenchmark` | JDBC platform thread select, JDBC virtual thread dispatch, R2DBC suspend transaction select 처리량 |
        || Custom ID tables | `./gradlew :benchmark-exposed-benchmark:idTablesBenchmark` | `UUIDTable`, `TimebasedUUIDTable`, `UlidTable`, Base62 UUIDv7, Snowflake, KSUID, KSUID millis 대량 insert/select 처리량 |
        || Local and near cache | `./gradlew :benchmark-exposed-benchmark:cacheBenchmark` | Caffeine hit, near-cache hit, read-through miss 처리량 |
        || Redis cache clients | `./gradlew :benchmark-exposed-benchmark:redisCacheBenchmark -Pbenchmark.parameters.redisUri=redis://127.0.0.1:6379` | Lettuce와 Redisson remote cache get 처리량 |
        || Smoke | `./gradlew :benchmark-exposed-benchmark:smokeBenchmark` | Redis를 제외한 짧은 H2 기반 검증 실행 |
        |
        |## 결과
        |
        |생성일: ${LocalDate.now()}, 입력 경로: `${reportDir.relativeToOrSelf(projectDir)}`.
        |
        |${resultsTable(results)}
        |
        |![Exposed benchmark chart](../../docs/images/readme-charts/exposed-benchmark-suite.svg)
        |
        |## 운영 메모
        |
        |- Redis benchmark는 접근 가능한 Redis 서버가 필요하므로 smoke에서 분리했습니다.
        |- 기본 검증은 H2로 가볍게 유지하고, DB별 profile은 같은 모듈 경계 안에서 확장합니다.
        |- benchmark 실행 후 `./gradlew :benchmark-exposed-benchmark:generateBenchmarkDocs`로 표와 차트를 갱신합니다.
        |
    """.trimMargin()

    private fun resultsTable(results: List<BenchmarkResult>): String {
        if (results.isEmpty()) {
            return """
                | Benchmark | Mode | Score | Error | Unit |
                ||---|---:|---:|---:|---|
                || No measured result yet | - | - | - | Run a benchmark task first |
            """.trimIndent()
        }
        return buildString {
            appendLine("| Benchmark | Mode | Score | Error | Unit |")
            appendLine("||---|---:|---:|---:|---|")
            results.sortedByDescending { it.score }.forEach { result ->
                appendLine("|| `${result.benchmark}` | ${result.mode} | ${"%.2f".format(result.score)} | ${result.error?.let { "%.2f".format(it) } ?: "-"} | ${result.unit} |")
            }
        }.trimEnd()
    }
}

data class BenchmarkResult(
    val benchmark: String,
    val mode: String,
    val score: Double,
    val error: Double?,
    val unit: String,
)

class BenchmarkResultReader(private val reportDir: File) {
    fun read(): List<BenchmarkResult> {
        if (!reportDir.isDirectory) {
            return emptyList()
        }
        return reportDir.walkTopDown()
            .filter { it.isFile && it.extension == "json" }
            .flatMap { parse(it.readText()).asSequence() }
            .groupBy { it.benchmark }
            .map { (_, values) -> values.maxBy { it.score } }
            .toList()
    }

    private fun parse(json: String): List<BenchmarkResult> =
        json.trim()
            .removePrefix("[")
            .removeSuffix("]")
            .split(Regex("""\n\s*\},\s*\n\s*\{"""))
            .asSequence()
            .mapNotNull { rawBlock ->
                val block = rawBlock
                if (!block.contains(""""benchmark"""")) {
                    return@mapNotNull null
                }
            BenchmarkResult(
                benchmark = benchmarkRegex.find(block)?.groupValues?.get(1) ?: return@mapNotNull null,
                mode = modeRegex.find(block)?.groupValues?.get(1) ?: "-",
                score = scoreRegex.find(block)?.groupValues?.get(1)?.toDoubleOrNull() ?: return@mapNotNull null,
                error = errorRegex.find(block)?.groupValues?.get(1)?.toDoubleOrNull(),
                unit = unitRegex.find(block)?.groupValues?.get(1) ?: "-",
            )
            }.toList()

    private companion object {
        val benchmarkRegex = Regex(""""benchmark"\s*:\s*"([^"]+)"""")
        val modeRegex = Regex(""""mode"\s*:\s*"([^"]+)"""")
        val scoreRegex = Regex(""""score"\s*:\s*([0-9.Ee+-]+)""")
        val errorRegex = Regex(""""scoreError"\s*:\s*([0-9.Ee+-]+)""")
        val unitRegex = Regex(""""scoreUnit"\s*:\s*"([^"]+)"""")
    }
}

object BenchmarkChartRenderer {
    fun render(results: List<BenchmarkResult>): String {
        val rows = if (results.isEmpty()) {
            listOf(BenchmarkResult("Run benchmark tasks", "thrpt", 1.0, null, "ops/s"))
        } else {
            results.sortedByDescending { it.score }.take(10)
        }
        val maxScore = max(1.0, rows.maxOf { it.score })
        val width = 1160
        val rowHeight = 54
        val height = 140 + rows.size * rowHeight
        val bars = rows.mapIndexed { index, result ->
            val y = 96 + index * rowHeight
            val barWidth = (760 * ln(result.score + 1.0) / ln(maxScore + 1.0)).coerceAtLeast(8.0)
            val label = result.benchmark.substringAfterLast('.')
            val scoreText = "%.2f ${result.unit}".format(result.score)
            val scoreElement = if (barWidth > 620.0) {
                """<text x="${318 + barWidth}" y="${y + 20}" class="scoreInside" text-anchor="end">${scoreText.escapeXml()}</text>"""
            } else {
                """<text x="${350 + barWidth}" y="${y + 20}" class="score">${scoreText.escapeXml()}</text>"""
            }
            """
            |<text x="32" y="${y + 20}" class="label">${label.escapeXml()}</text>
            |<rect x="330" y="$y" width="$barWidth" height="26" rx="4" class="bar"/>
            |$scoreElement
            """.trimMargin()
        }.joinToString("\n")
        return """
            |<svg xmlns="http://www.w3.org/2000/svg" width="$width" height="$height" viewBox="0 0 $width $height" role="img" aria-labelledby="title desc">
            |  <title id="title">Exposed benchmark suite results</title>
            |  <desc id="desc">Top benchmark scores rendered from kotlinx-benchmark JSON output.</desc>
            |  <style>
            |    .bg { fill: #f7f8fb; }
            |    .title { fill: #18212f; font: 700 28px Arial, sans-serif; }
            |    .subtitle { fill: #536173; font: 15px Arial, sans-serif; }
            |    .label { fill: #1f2937; font: 14px Arial, sans-serif; }
            |    .score { fill: #334155; font: 13px Arial, sans-serif; }
            |    .scoreInside { fill: #ffffff; font: 700 13px Arial, sans-serif; }
            |    .bar { fill: #2f7d6d; }
            |    .axis { stroke: #cbd5e1; stroke-width: 1; }
            |  </style>
            |  <rect class="bg" width="$width" height="$height" rx="0"/>
            |  <text x="32" y="44" class="title">Exposed Benchmark Suite</text>
            |  <text x="32" y="72" class="subtitle">JDBC, R2DBC, custom ID table, and cache benchmark summary</text>
            |  <line x1="330" y1="86" x2="1090" y2="86" class="axis"/>
            |  $bars
            |</svg>
        """.trimMargin()
    }
}

private fun String.escapeXml(): String =
    replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
