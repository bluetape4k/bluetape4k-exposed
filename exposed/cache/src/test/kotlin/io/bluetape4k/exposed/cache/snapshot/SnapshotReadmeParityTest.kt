package io.bluetape4k.exposed.cache.snapshot

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class SnapshotReadmeParityTest {

    @Test
    fun `README locale pairs contain the same public snapshot API names`() {
        README_PAIRS.forEach { pair ->
            val english = Files.readString(projectFile(pair.english))
            val korean = Files.readString(projectFile(pair.korean))
            val englishNames = PUBLIC_API_NAMES.filterTo(sortedSetOf()) { it in english }
            val koreanNames = PUBLIC_API_NAMES.filterTo(sortedSetOf()) { it in korean }

            englishNames shouldBeEqualTo koreanNames
            pair.requiredNames.forEach { requiredName ->
                (requiredName in english).shouldBeTrue()
                (requiredName in korean).shouldBeTrue()
            }
        }
    }

    @Test
    fun `snapshot dependency coordinates are versionless under the consumer BOM`() {
        README_PAIRS.forEach { pair ->
            listOf(pair.english, pair.korean).forEach { readme ->
                val source = Files.readString(projectFile(readme))
                hasExplicitBluetapeVersion(source).shouldBeFalse()
            }
        }
    }

    @Test
    fun `explicit versions after bluetape artifact coordinates are detected`() {
        hasExplicitBluetapeVersion(
            "implementation(\"io.github.bluetape4k.exposed:bluetape4k-exposed-cache:1.12.0\")",
        ).shouldBeTrue()
    }

    @Test
    fun `paired READMEs expose the transaction and operator contract sections`() {
        val markers = mapOf(
            "exposed/cache/README.md" to "<!-- SNAPSHOT-CACHE-CONTRACT -->",
            "exposed/cache/README.ko.md" to "<!-- SNAPSHOT-CACHE-CONTRACT -->",
            "exposed/jdbc-caffeine/README.md" to "<!-- JDBC-SNAPSHOT-CACHE -->",
            "exposed/jdbc-caffeine/README.ko.md" to "<!-- JDBC-SNAPSHOT-CACHE -->",
            "exposed/r2dbc-caffeine/README.md" to "<!-- R2DBC-SNAPSHOT-CACHE -->",
            "exposed/r2dbc-caffeine/README.ko.md" to "<!-- R2DBC-SNAPSHOT-CACHE -->",
            "exposed/jdbc-redisson/README.md" to "<!-- REDISSON-SNAPSHOT-INVALIDATION -->",
            "exposed/jdbc-redisson/README.ko.md" to "<!-- REDISSON-SNAPSHOT-INVALIDATION -->",
        )

        markers.forEach { (readme, marker) ->
            Files.readString(projectFile(readme)).contains(marker).shouldBeTrue()
        }
    }

    private fun projectFile(relativePath: String): Path {
        val rootCandidate = Path.of(relativePath)
        if (Files.exists(rootCandidate)) return rootCandidate
        return Path.of("../..", relativePath).normalize()
    }

    private fun hasExplicitBluetapeVersion(source: String): Boolean =
        BLUETAPE_VERSIONED_COORDINATE.containsMatchIn(source)

    private data class ReadmePair(
        val english: String,
        val korean: String,
        val requiredNames: Set<String>,
    )

    companion object {
        private val BLUETAPE_VERSIONED_COORDINATE =
            Regex("""io\.github\.bluetape4k(?:\.[A-Za-z0-9_-]+)*:[A-Za-z0-9_.-]+:[^"')\s]+""")

        private val README_PAIRS = listOf(
            ReadmePair(
                "exposed/cache/README.md",
                "exposed/cache/README.ko.md",
                setOf(
                    "CacheSnapshot",
                    "CacheSnapshotMapper",
                    "SnapshotCacheConfig",
                    "CaffeineSnapshotCacheConfig",
                    "SnapshotCacheFailureBuffer",
                    "snapshotCacheFailureBuffer",
                ),
            ),
            ReadmePair(
                "exposed/jdbc-caffeine/README.md",
                "exposed/jdbc-caffeine/README.ko.md",
                setOf("JdbcCaffeineSnapshotCache", "jdbcCaffeineSnapshotCache", "stageSnapshot", "stageInvalidation"),
            ),
            ReadmePair(
                "exposed/r2dbc-caffeine/README.md",
                "exposed/r2dbc-caffeine/README.ko.md",
                setOf("R2dbcCaffeineSnapshotCache", "r2dbcCaffeineSnapshotCache", "stageSnapshot", "stageInvalidation"),
            ),
            ReadmePair(
                "exposed/jdbc-redisson/README.md",
                "exposed/jdbc-redisson/README.ko.md",
                setOf(
                    "JdbcRedissonSnapshotInvalidator",
                    "jdbcRedissonSnapshotInvalidator",
                    "SnapshotRedissonCodec",
                    "quotaHealth",
                    "clearSnapshotNamespace",
                    "clearMapRetainingMarker",
                ),
            ),
        )

        private val PUBLIC_API_NAMES = setOf(
            "CacheSnapshot",
            "CacheSnapshotMapper",
            "CacheSnapshotValueValidator",
            "SnapshotValueSizer",
            "SnapshotCacheConfig",
            "CaffeineSnapshotCacheConfig",
            "SnapshotCacheFailure",
            "SnapshotCacheFailureBuffer",
            "SnapshotCacheDrainResult",
            "SnapshotCacheLookup",
            "SnapshotCacheMiss",
            "rejectDirectEntitySnapshotValues",
            "maximumEstimatedPayloadBytes",
            "snapshotCacheFailureBuffer",
            "JdbcCaffeineSnapshotCache",
            "jdbcCaffeineSnapshotCache",
            "R2dbcCaffeineSnapshotCache",
            "r2dbcCaffeineSnapshotCache",
            "stageSnapshot",
            "stageInvalidation",
            "JdbcRedissonSnapshotInvalidator",
            "JdbcRedissonSnapshotInvalidatorConfig",
            "SnapshotRedissonCodec",
            "SnapshotIdentifierPolicy",
            "longSnapshotIdentifierPolicy",
            "uuidSnapshotIdentifierPolicy",
            "snapshotRedissonCodec",
            "SnapshotInvalidationQuotaHealth",
            "quotaHealth",
            "DelicateSnapshotCacheAdminApi",
            "SnapshotNamespaceCleanupOutcome",
            "SnapshotNamespaceCleanupResult",
            "clearSnapshotNamespace",
            "clearMapRetainingMarker",
        )
    }
}
