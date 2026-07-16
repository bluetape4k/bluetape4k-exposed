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
            val declarations = publicSnapshotApiDeclarations(pair)
            val publicApiNames = declarations.mapTo(sortedSetOf()) { it.name }
            val englishNames = publicApiNames.filterTo(sortedSetOf()) { it in english }
            val koreanNames = publicApiNames.filterTo(sortedSetOf()) { it in korean }
            val readmeRequiredNames = declarations
                .filterNot(PublicSnapshotDeclaration::internalApi)
                .mapTo(sortedSetOf(), PublicSnapshotDeclaration::name)
            val undocumentedEnglish = readmeRequiredNames.filterTo(sortedSetOf()) { it !in english }
            val undocumentedKorean = readmeRequiredNames.filterTo(sortedSetOf()) { it !in korean }

            englishNames shouldBeEqualTo koreanNames
            undocumentedEnglish shouldBeEqualTo pair.intentionalUndocumentedApi.keys
            undocumentedKorean shouldBeEqualTo pair.intentionalUndocumentedApi.keys
            pair.intentionalUndocumentedApi.values.all(String::isNotBlank).shouldBeTrue()
            pair.requiredNames.forEach { requiredName ->
                (requiredName in english).shouldBeTrue()
                (requiredName in korean).shouldBeTrue()
            }
        }
    }

    @Test
    fun `Redisson rollout docs require a write-quiesced all-traffic cutover without cross-namespace invalidation`() {
        README_PAIRS.filter { it.english.contains("jdbc-redisson") }.forEach { pair ->
            listOf(pair.english, pair.korean).forEach { readme ->
                val source = Files.readString(projectFile(readme))
                (ROLLOUT_CONTRACT_MARKER in source).shouldBeTrue()
            }
        }
    }

    @Test
    fun `public snapshot API discovery covers Kotlin declaration modifiers but excludes internal declarations`() {
        topLevelPublicDeclarations(
            """
            data class PublicSnapshot(val id: Long)
            sealed interface PublicOutcome
            @JvmInline
            value class PublicId(val value: Long)
            suspend fun <ID : Any> PublicSnapshot.load(id: ID): PublicSnapshot = this
            const val PUBLIC_LIMIT: Int = 1
            @InternalSnapshotCacheApi
            interface InternalAdapterSpi
            internal class InternalSnapshot
            private fun privateLoader(): Unit = Unit
            """.trimIndent(),
        ).shouldBeEqualTo(
            setOf(
                PublicSnapshotDeclaration("PublicSnapshot", internalApi = false),
                PublicSnapshotDeclaration("PublicOutcome", internalApi = false),
                PublicSnapshotDeclaration("PublicId", internalApi = false),
                PublicSnapshotDeclaration("load", internalApi = false),
                PublicSnapshotDeclaration("PUBLIC_LIMIT", internalApi = false),
                PublicSnapshotDeclaration("InternalAdapterSpi", internalApi = true),
            ),
        )
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

    private fun publicSnapshotApiDeclarations(pair: ReadmePair): Set<PublicSnapshotDeclaration> =
        pair.sourceDirectories
            .flatMap { sourceDirectory ->
                Files.walk(projectFile(sourceDirectory)).use { paths ->
                    paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".kt") }
                        .map { Files.readString(it) }
                        .toList()
                }
            }
            .flatMapTo(linkedSetOf(), ::topLevelPublicDeclarations)

    private fun topLevelPublicDeclarations(source: String): Set<PublicSnapshotDeclaration> = buildSet {
        PUBLIC_TOP_LEVEL_TYPE.findAll(source).forEach { match ->
            add(PublicSnapshotDeclaration(match.groupValues[1], source.hasInternalApiAnnotationBefore(match.range.first)))
        }
        PUBLIC_TOP_LEVEL_FUNCTION.findAll(source).forEach { match ->
            TOP_LEVEL_FUNCTION_NAME.findAll(match.groupValues[1]).lastOrNull()?.value?.let { name ->
                add(PublicSnapshotDeclaration(name, source.hasInternalApiAnnotationBefore(match.range.first)))
            }
        }
        PUBLIC_TOP_LEVEL_PROPERTY.findAll(source).forEach { match ->
            add(PublicSnapshotDeclaration(match.groupValues[1], source.hasInternalApiAnnotationBefore(match.range.first)))
        }
    }

    private fun String.hasInternalApiAnnotationBefore(declarationStart: Int): Boolean =
        substring(0, declarationStart).trimEnd().lineSequence().lastOrNull()?.trim() == "@InternalSnapshotCacheApi"

    private data class ReadmePair(
        val english: String,
        val korean: String,
        val sourceDirectories: List<String>,
        val requiredNames: Set<String>,
        val intentionalUndocumentedApi: Map<String, String> = emptyMap(),
    )

    private data class PublicSnapshotDeclaration(
        val name: String,
        val internalApi: Boolean,
    )

    companion object {
        private val BLUETAPE_VERSIONED_COORDINATE =
            Regex("""io\.github\.bluetape4k(?:\.[A-Za-z0-9_-]+)*:[A-Za-z0-9_.-]+:[^"')\s]+""")
        private val PUBLIC_TOP_LEVEL_TYPE = Regex(
            """(?m)^(?!(?:internal|private|protected)\b)(?:(?:public|expect|actual|data|sealed|enum|annotation|value|fun|open|abstract)\s+)*(?:class|interface|object|typealias)\s+([A-Za-z_]\w*)""",
        )
        private val PUBLIC_TOP_LEVEL_FUNCTION = Regex(
            """(?m)^(?!(?:internal|private|protected)\b)(?:(?:public|expect|actual|inline|suspend|operator|infix|tailrec|external)\s+)*fun\s+(?:<[^>\n]+>\s+)?([^\n(]+)\(""",
        )
        private val PUBLIC_TOP_LEVEL_PROPERTY = Regex(
            """(?m)^(?!(?:internal|private|protected)\b)(?:(?:public|expect|actual|const|lateinit)\s+)*(?:val|var)\s+([A-Za-z_]\w*)""",
        )
        private val TOP_LEVEL_FUNCTION_NAME = Regex("""[A-Za-z_]\w*""")
        private const val ROLLOUT_CONTRACT_MARKER =
            "<!-- SNAPSHOT-ROLLOUT-CONTRACT: shadow-warm-only; no-v2-user-reads-or-writes; write-quiesced-cutover; " +
                "rebuild-v2-from-db; switch-all-traffic; no-overlapping-user-traffic; resume-writes; " +
                "no-cross-namespace-invalidation -->"

        private val README_PAIRS = listOf(
            ReadmePair(
                "exposed/cache/README.md",
                "exposed/cache/README.ko.md",
                listOf("exposed/cache/src/main/kotlin/io/bluetape4k/exposed/cache/snapshot"),
                setOf(
                    "CacheSnapshot",
                    "CacheSnapshotMapper",
                    "SnapshotCacheConfig",
                    "CaffeineSnapshotCacheConfig",
                    "SnapshotCacheFailureBuffer",
                    "snapshotCacheFailureBuffer",
                ),
                mapOf(
                    "CacheSnapshotValueValidator" to "Advanced validator hook; KDoc is the adapter contract.",
                    "InternalSnapshotCacheApi" to "Opt-in SPI marker; adapter KDoc is authoritative.",
                    "MeasuredInvalidation" to "Low-level adapter measurement model; KDoc is authoritative.",
                    "SnapshotCacheApplyReport" to "Low-level adapter report model; KDoc is authoritative.",
                    "SnapshotCacheDeadline" to "Low-level adapter deadline SPI; KDoc is authoritative.",
                    "SnapshotCacheDrainResult" to "Failure-drain result model; KDoc is authoritative.",
                    "SnapshotCacheFailureObserver" to "Advanced failure observer hook; KDoc is authoritative.",
                    "SnapshotCacheLimits" to "Low-level adapter limit model; KDoc is authoritative.",
                    "SnapshotCacheLookup" to "Opaque lookup result; the README documents lookup behavior instead.",
                    "SnapshotCacheMutation" to "Low-level adapter mutation model; KDoc is authoritative.",
                    "SnapshotCacheOperation" to "Low-level failure/report enum; KDoc is authoritative.",
                    "SnapshotCacheOperationResult" to "Low-level adapter result model; KDoc is authoritative.",
                    "SnapshotCacheOutcome" to "Low-level failure/report enum; KDoc is authoritative.",
                    "SnapshotStoreId" to "Low-level bounded store identity; KDoc is authoritative.",
                    "SnapshotValueSizer" to "Advanced sizing hook; KDoc is the adapter contract.",
                    "loggingSnapshotCacheFailureObserver" to "Optional logging adapter; KDoc is authoritative.",
                    "maximumEstimatedPayloadBytes" to "Advanced sizing helper; KDoc is authoritative.",
                ),
            ),
            ReadmePair(
                "exposed/jdbc-caffeine/README.md",
                "exposed/jdbc-caffeine/README.ko.md",
                listOf("exposed/jdbc-caffeine/src/main/kotlin/io/bluetape4k/exposed/jdbc/caffeine/snapshot"),
                setOf("JdbcCaffeineSnapshotCache", "jdbcCaffeineSnapshotCache", "stageSnapshot", "stageInvalidation"),
            ),
            ReadmePair(
                "exposed/r2dbc-caffeine/README.md",
                "exposed/r2dbc-caffeine/README.ko.md",
                listOf("exposed/r2dbc-caffeine/src/main/kotlin/io/bluetape4k/exposed/r2dbc/caffeine/snapshot"),
                setOf("R2dbcCaffeineSnapshotCache", "r2dbcCaffeineSnapshotCache", "stageSnapshot", "stageInvalidation"),
            ),
            ReadmePair(
                "exposed/jdbc-redisson/README.md",
                "exposed/jdbc-redisson/README.ko.md",
                listOf("exposed/jdbc-redisson/src/main/kotlin/io/bluetape4k/exposed/redisson/snapshot"),
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

    }
}
