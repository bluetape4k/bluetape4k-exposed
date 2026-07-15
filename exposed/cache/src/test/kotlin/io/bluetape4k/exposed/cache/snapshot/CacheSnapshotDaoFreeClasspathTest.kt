package io.bluetape4k.exposed.cache.snapshot

import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test
import java.io.File
import java.io.Serializable
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class CacheSnapshotDaoFreeClasspathTest {

    @Test
    fun `direct Entity validator is safe when Exposed DAO is absent`() {
        val classpathEntries = System.getProperty("java.class.path")
            .split(File.pathSeparator)
        val daoEntries = classpathEntries.filter(::isExposedDaoEntry)
        val isolatedClasspath = classpathEntries
            .filterNot(::isExposedDaoEntry)
            .joinToString(File.pathSeparator)

        daoEntries.isNotEmpty() shouldBeEqualTo true

        val process = ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp",
            isolatedClasspath,
            CacheSnapshotDaoFreeClasspathTest::class.java.name,
        ).redirectErrorStream(true).start()
        val completed = process.waitFor(30, TimeUnit.SECONDS)
        val output = process.inputStream.bufferedReader().use { it.readText() }

        if (!completed) {
            process.destroyForcibly()
        }

        completed shouldBeEqualTo true
        process.exitValue() shouldBeEqualTo 0
        output shouldBeEqualTo "validated\n"
    }

    companion object {
        private const val serialVersionUID: Long = 1L

        @JvmStatic
        fun main(args: Array<String>) {
            rejectDirectEntitySnapshotValues<Payload>().validate(Payload("detached"))
            println("validated")
        }

        private fun isExposedDaoEntry(entry: String): Boolean =
            Path.of(entry).fileName.toString().startsWith("exposed-dao-")
    }

    private data class Payload(
        val text: String,
    ): Serializable {
        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }
}
