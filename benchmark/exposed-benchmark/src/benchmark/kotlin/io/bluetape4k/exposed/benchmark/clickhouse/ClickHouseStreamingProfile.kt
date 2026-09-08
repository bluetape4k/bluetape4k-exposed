package io.bluetape4k.exposed.benchmark.clickhouse

import io.bluetape4k.exposed.clickhouse.queryFlow
import io.bluetape4k.exposed.clickhouse.queryList
import io.bluetape4k.testcontainers.database.ClickHouseServer
import jdk.jfr.Recording
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.io.File
import java.lang.management.BufferPoolMXBean
import java.lang.management.ManagementFactory
import java.lang.ref.Reference
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** fresh JVM에서 warmup과 실측을 분리하고 원시 메모리 표본과 JFR을 보존합니다. */
object ClickHouseStreamingProfile {
    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        val api = args[0]
        val rowCount = args[1].toInt()
        val run = args[2].toInt()
        check(api in setOf("list", "flow") && rowCount in setOf(100000, 1000000) && run in 1..3)
        val output = File(args[3]).apply { mkdirs() }
        val stem = "$api-$rowCount-$run"
        val database = clickHouseDatabase()
        val warmupRows = minOf(rowCount, WARMUP_ROWS)
        suspend fun consume(rowsToRead: Int, checkpoint: (Long) -> Unit): Pair<Long, Long> {
            var count = 0L
            var checksum = 0L
            fun accept(value: Long) {
                count++
                checksum += value
                checkpoint(count)
            }
            if (api == "list") {
                var rows: List<Long>? = queryList(database) {
                    Numbers.selectAll().limit(rowsToRead).map { it[Numbers.number] }
                }
                checkpoint(0)
                rows?.forEach(::accept)
                rows?.let { Reference.reachabilityFence(it) }
                rows = null
            } else {
                checkpoint(0)
                queryFlow(database, query = { Numbers.selectAll().limit(rowsToRead) }, mapper = {
                    it[Numbers.number]
                }).collect(::accept)
            }
            check(count == rowsToRead.toLong())
            check(checksum == rowsToRead.toLong() * (rowsToRead - 1L) / 2)
            return count to checksum
        }
        repeat(2) { consume(warmupRows) {} }
        val memory = ManagementFactory.getMemoryMXBean()
        val buffers = ManagementFactory.getPlatformMXBeans(BufferPoolMXBean::class.java)
        fun direct() = buffers.filter { it.name == "direct" }.sumOf { it.memoryUsed }
        fun mapped() = buffers.filter { it.name == "mapped" }.sumOf { it.memoryUsed }
        repeat(GC_SETTLE_ROUNDS) {
            System.gc()
            Thread.sleep(GC_SETTLE_DELAY_MILLIS)
        }
        val baseline = memory.heapMemoryUsage.used
        val baselineDirect = direct()
        val pid = ProcessHandle.current().pid()
        val samples = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val checkpoints = mutableListOf<String>()
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        var firstItemNanos = 0L
        val recording = Recording().apply {
            enable("jdk.ObjectAllocationSample").withStackTrace()
            enable("jdk.OldObjectSample").withStackTrace()
            enable("jdk.GCHeapSummary")
            maxAge = Duration.ofMinutes(10)
            start()
        }
        val start = System.nanoTime()
        val sampler = scheduler.scheduleAtFixedRate({
            val process = ProcessBuilder("ps", "-o", "rss=", "-p", pid.toString()).start()
            val exited = process.waitFor(1, TimeUnit.SECONDS)
            val rss = if (exited) process.inputStream.bufferedReader().use { it.readText().trim() } else "-1"
            if (!exited) process.destroyForcibly()
            samples.add("${System.nanoTime() - start},${memory.heapMemoryUsage.used},${direct()},${mapped()},$rss")
        }, 0, 10, TimeUnit.MILLISECONDS)
        val result: Pair<Long, Long>
        val elapsed: Long
        try {
            result = consume(rowCount) { count ->
                if (count == 1L) firstItemNanos = System.nanoTime() - start
                if (count % (rowCount / 4) == 0L) {
                    System.gc()
                    checkpoints.add("$count,${memory.heapMemoryUsage.used},${direct()},${mapped()}")
                }
            }
            elapsed = System.nanoTime() - start
        } finally {
            sampler.cancel(false)
            scheduler.shutdown()
            var schedulerTerminated = false
            try {
                schedulerTerminated = scheduler.awaitTermination(3, TimeUnit.SECONDS)
            } finally {
                try {
                    recording.stop()
                    recording.dump(File(output, "$stem.jfr").toPath())
                } finally {
                    recording.close()
                }
            }
            check(schedulerTerminated)
        }
        File(output, "$stem-raw.csv").writeText("elapsedNanos,heap,direct,mapped,rssKiB\n" + samples.joinToString("\n"))
        File(output, "$stem-live.csv").writeText("count,heap,direct,mapped\n" + checkpoints.joinToString("\n"))
        val peak = checkpoints.maxOf { it.split(',')[1].toLong() }
        val image = ClickHouseServer.Launcher.clickhouse.dockerImageName
        val json = """
            {"api":"$api","rows":$rowCount,"run":$run,"pid":$pid,
             "java":"${System.getProperty("java.version")}","driver":"0.9.9","serverImage":"$image",
             "rowWidth":"one UInt64 mapped to Long","socketTimeoutMillis":10000,"connectionTimeoutMillis":10000,
             "warmups":2,"warmupRows":$warmupRows,"baselineHeap":$baseline,"baselineDirect":$baselineDirect,
             "peakLiveHeap":$peak,"deltaLiveHeap":${peak - baseline},
             "count":${result.first},"checksum":${result.second},
             "firstItemNanos":$firstItemNanos,"elapsedNanos":$elapsed,"forcedGcProfile":true}
        """.trimIndent()
        File(output, "$stem.json").writeText(json)
        println(json)
    }

    private const val GC_SETTLE_ROUNDS = 5
    private const val GC_SETTLE_DELAY_MILLIS = 100L
    private const val WARMUP_ROWS = 1_000
}
