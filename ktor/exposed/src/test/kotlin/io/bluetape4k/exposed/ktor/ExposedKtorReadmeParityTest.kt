package io.bluetape4k.exposed.ktor

import io.bluetape4k.assertions.should

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class ExposedKtorReadmeParityTest {

    @Test
    fun `Ktor README examples match the compiled canonical fixture in both locales`() {
        val fixture = read("ktor/exposed/src/test/kotlin/io/bluetape4k/exposed/ktor/ExposedKtorReadmeFixture.kt")
        val readmes = listOf("ktor/exposed/README.md", "ktor/exposed/README.ko.md")

        EXAMPLES.forEach { name ->
            val expected = extractFixture(fixture, name)
            readmes.forEach { path ->
                (extractReadme(read(path), name)).should("$path example '$name' drifted") { it == expected }
            }
        }
    }

    @Test
    fun `Ktor README locales contain the complete readiness and operations contract`() {
        listOf("ktor/exposed/README.md", "ktor/exposed/README.ko.md").forEach { path ->
            val text = read(path)
            KTOR_TERMS.forEach { term -> (term in text).should("$path is missing '$term'") { it } }
        }
        REQUIRED_ENGLISH_FRAGMENTS.forEach { fragment ->
            (fragment in read("ktor/exposed/README.md")).should("English Ktor README drifted: $fragment") { it }
        }
        REQUIRED_KOREAN_FRAGMENTS.forEach { fragment ->
            (fragment in read("ktor/exposed/README.ko.md")).should("Korean Ktor README drifted: $fragment") { it }
        }
    }

    @Test
    fun `cache migration and Spring Actuator mappings remain source equivalent`() {
        listOf("exposed/cache/README.md", "exposed/cache/README.ko.md").forEach { path ->
            val text = read(path)
            CACHE_TERMS.forEach { term -> (term in text).should("$path is missing '$term'") { it } }
        }
        CACHE_ENGLISH_FRAGMENTS.forEach { fragment ->
            (fragment in read("exposed/cache/README.md")).should("English cache migration drifted: $fragment") { it }
        }
        CACHE_KOREAN_FRAGMENTS.forEach { fragment ->
            (fragment in read("exposed/cache/README.ko.md")).should("Korean cache migration drifted: $fragment") { it }
        }
        listOf(
            "spring-boot/jdbc/README.md",
            "spring-boot/r2dbc/README.md",
        ).forEach { path ->
            val text = read(path)
            ACTUATOR_TERMS.forEach { term -> (term in text).should("$path is missing '$term'") { it } }
            ACTUATOR_ENGLISH_FRAGMENTS.forEach { fragment ->
                (fragment in text).should("$path Actuator mapping drifted: $fragment") { it }
            }
        }
        listOf(
            "spring-boot/jdbc/README.ko.md",
            "spring-boot/r2dbc/README.ko.md",
        ).forEach { path ->
            val text = read(path)
            ACTUATOR_TERMS.forEach { term -> (term in text).should("$path is missing '$term'") { it } }
            ACTUATOR_KOREAN_FRAGMENTS.forEach { fragment ->
                (fragment in text).should("$path Actuator mapping drifted: $fragment") { it }
            }
        }
    }

    private fun extractFixture(text: String, name: String): String =
        extract(text, "// example:$name:start", "// example:$name:end")

    private fun extractReadme(text: String, name: String): String {
        val marked = extract(text, "<!-- example:$name:start -->", "<!-- example:$name:end -->")
        return marked.lineSequence()
            .filterNot { it.trim().startsWith("```") }
            .joinToString("\n")
            .trim()
    }

    private fun extract(text: String, start: String, end: String): String {
        val startIndex = text.indexOf(start)
        val endIndex = text.indexOf(end, startIndex + start.length)
        (startIndex >= 0 && endIndex > startIndex).should("Missing markers: $start .. $end") { it }
        return text.substring(startIndex + start.length, endIndex).trimIndent().trim()
    }

    private fun read(relative: String): String {
        val candidates = listOf(Path.of(relative), Path.of("..", "..", relative).normalize())
        val path = candidates.firstOrNull(Files::exists)
            ?: error("Cannot locate repository file: $relative from ${Path.of("").toAbsolutePath()}")
        return Files.readString(path)
    }

    companion object {
        private val EXAMPLES = listOf(
            "jdbc-report",
            "r2dbc-report",
            "snapshot",
            "custom-status",
            "cache-only-installer",
            "ingress-root-route",
            "authenticated-direct-route",
        )
        private val KTOR_TERMS = listOf(
            "[a-z][a-z0-9_-]{0,62}", "1..16", "side-effect-free O(1)",
            "bluetape4k.exposed.ktor.cache.readiness", "bluetape4k.exposed.ktor.cache.queue.depth",
            "bluetape4k.exposed.ktor.cache.snapshot.pending", "bluetape4k.exposed.ktor.cache.snapshot.dropped",
            "bluetape4k.exposed.ktor.cache.snapshot.observer.failures", "component", "kind", "operation", "outcome",
            "entries", "events", "NaN", "128", "T_endpoint", "J_effective", "timeoutSeconds", "periodSeconds",
            "failureThreshold", "/healthz/exposed", "/readyz/exposed", "NOT_APPLICABLE", "IDLE", "RUNNING",
            "DRAINING", "FAILED", "STOPPED", "OUT_OF_SERVICE", "identity_collision", "rate limiting",
        )
        private val CACHE_TERMS = listOf(
            "isFlushJobRunning", "workerState", "NOT_APPLICABLE", "IDLE", "RUNNING", "DRAINING", "FAILED", "STOPPED",
        )
        private val ACTUATOR_TERMS = listOf(
            "workerState", "NOT_APPLICABLE", "IDLE", "RUNNING", "DRAINING", "FAILED", "STOPPED",
            "UP", "OUT_OF_SERVICE", "DOWN", "ExposedKtorCacheContributor",
        )
        private val CACHE_ENGLISH_FRAGMENTS = listOf(
            "`CacheHealthReport.isFlushJobRunning` was removed before the stable release",
            "report.lastFlushError == null && report.workerState in setOf(",
            "CacheWorkerState.NOT_APPLICABLE",
            "CacheWorkerState.IDLE",
            "CacheWorkerState.RUNNING",
            "The old Boolean could not distinguish those states",
        )
        private val CACHE_KOREAN_FRAGMENTS = listOf(
            "`CacheHealthReport.isFlushJobRunning`은 stable release 전에 제거됐습니다",
            "report.lastFlushError == null && report.workerState in setOf(",
            "CacheWorkerState.NOT_APPLICABLE",
            "CacheWorkerState.IDLE",
            "CacheWorkerState.RUNNING",
            "기존 Boolean으로는 이 상태들을 구분할 수 없습니다",
        )
        private val ACTUATOR_ENGLISH_FRAGMENTS = listOf(
            "`workerState=NOT_APPLICABLE|IDLE|RUNNING` | `UP`",
            "`workerState=DRAINING|STOPPED` | `OUT_OF_SERVICE`",
            "Flush error or `workerState=FAILED` | `DOWN`",
            "explicit",
            "`ExposedKtorCacheContributor`",
        )
        private val ACTUATOR_KOREAN_FRAGMENTS = listOf(
            "`workerState=NOT_APPLICABLE|IDLE|RUNNING` | `UP`",
            "`workerState=DRAINING|STOPPED` | `OUT_OF_SERVICE`",
            "Flush error 또는 `workerState=FAILED` | `DOWN`",
            "명시적으로",
            "`ExposedKtorCacheContributor`",
        )
        private val REQUIRED_ENGLISH_FRAGMENTS = listOf(
            "## Cache Readiness Contributors",
            "## Installation and Security",
            "## Readiness Semantics and Budget",
            "## Metrics",
            "## Runbook",
            "`/healthz/exposed` | Probe-free liveness",
            "`/readyz/exposed` | Traffic readiness",
            "`NOT_APPLICABLE`, `IDLE`, or `RUNNING` with no flush error | `cache.<component>=UP`",
            "`DRAINING`, `FAILED`, or `STOPPED`, or any flush error | `cache.<component>=DOWN`",
            "Ktor has no `OUT_OF_SERVICE` response state",
            "T_endpoint = I_jdbc * (R + J_effective) + I_r2dbc * R + I_cache * R + overhead",
            "`(2+1)+2+2 = 7s`",
            "timeoutSeconds: 10",
            "periodSeconds: 15",
            "failureThreshold: 3",
            "Exported time-series counts and suffixes depend on the",
            "missing, omitted, or `NaN` gauge is unavailable, not zero",
            "Database `DOWN` | Check caller-owned pool connectivity and credentials",
            "Database `timeout` | Check pool exhaustion, network latency",
            "Cache `timeout` | Check the shared `R` budget and supplier cooperation",
            "A supplier that throws `CancellationException` while the request is still active",
            "request context is rethrown and stops readiness processing",
            "Snapshot cumulative counters rise | Inspect caller-owned drain/observer handling",
            "Meter collision | Keep the older route serving until traffic is withdrawn",
            "Withdraw traffic, start repository drain/close, observe readiness enter `DRAINING` and then `STOPPED`",
        )
        private val REQUIRED_KOREAN_FRAGMENTS = listOf(
            "## Cache Readiness Contributor",
            "## 설치와 보안",
            "## Readiness 의미와 시간 예산",
            "## Metrics",
            "## Runbook",
            "`/healthz/exposed` | Probe를 실행하지 않는 liveness",
            "`/readyz/exposed` | Traffic readiness",
            "`NOT_APPLICABLE`, `IDLE`, `RUNNING` | `cache.<component>=UP`",
            "`DRAINING`, `FAILED`, `STOPPED` 또는 flush error | `cache.<component>=DOWN`",
            "Ktor 응답에는 `OUT_OF_SERVICE` 상태가 없습니다",
            "T_endpoint = I_jdbc * (R + J_effective) + I_r2dbc * R + I_cache * R + overhead",
            "`(2+1)+2+2 = 7s`",
            "timeoutSeconds: 10",
            "periodSeconds: 15",
            "failureThreshold: 3",
            "Export된 time-series 수와 suffix는 registry와 distribution",
            "생략됐거나 `NaN`인 gauge는 0이 아니라 unavailable",
            "Database `DOWN` | 호출자 소유 pool의 연결과 credential",
            "Database `timeout` | Pool 고갈, network latency",
            "Cache `timeout` | 공유 `R` 예산과 supplier의 cancellation 협력",
            "Request가 여전히 active인 동안 supplier가 `CancellationException`을 던지면 `DOWN`",
            "Request context 자체가 취소되면 예외를 다시",
            "Snapshot 누적 counter 증가 | 호출자 소유 drain/observer 처리",
            "Meter collision | 이전 route의 traffic을 먼저 회수",
            "Traffic을 회수하고 repository drain/close를 시작한 뒤 readiness가 `DRAINING`, 이어서 `STOPPED`",
        )
    }
}
