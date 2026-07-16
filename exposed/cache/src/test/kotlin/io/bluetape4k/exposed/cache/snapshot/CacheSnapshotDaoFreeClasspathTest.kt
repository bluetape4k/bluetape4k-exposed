package io.bluetape4k.exposed.cache.snapshot

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
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

        daoEntries.isNotEmpty().shouldBeTrue()

        val process = ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp",
            isolatedClasspath,
            CacheSnapshotDaoFreeClasspathTest::class.java.name,
        ).redirectErrorStream(true).start()
        try {
            val completed = process.waitFor(CHILD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                val terminated = process.waitFor(CHILD_CLEANUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                val diagnostic = "DAO-free child timeout=${CHILD_TIMEOUT_SECONDS}s; terminatedAfterDestroy=$terminated."
                throw AssertionError(diagnostic)
            }

            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.exitValue() shouldBeEqualTo 0
            output shouldBeEqualTo "dao-absent\nvalidated\n"
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
                process.waitFor(CHILD_CLEANUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }
        }
    }

    companion object {
        private const val EXPOSED_DAO_ENTITY_CLASS_NAME = "org.jetbrains.exposed.v1.dao.Entity"
        private const val CHILD_TIMEOUT_SECONDS = 30L
        private const val CHILD_CLEANUP_TIMEOUT_SECONDS = 5L

        @JvmStatic
        fun main(args: Array<String>) {
            try {
                Class.forName(
                    EXPOSED_DAO_ENTITY_CLASS_NAME,
                    false,
                    CacheSnapshotDaoFreeClasspathTest::class.java.classLoader,
                )
                throw AssertionError("Exposed DAO Entity was unexpectedly available in the isolated child.")
            } catch (_: ClassNotFoundException) {
                println("dao-absent")
            }

            rejectDirectEntitySnapshotValues<Payload>().validate(Payload("detached"))
            println("validated")
        }

        private fun isExposedDaoEntry(entry: String): Boolean =
            Path.of(entry).fileName.toString().startsWith("exposed-dao-")
    }

    private data class Payload(
        val text: String,
    ) : Serializable {
        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }
}
