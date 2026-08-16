package io.bluetape4k.spring.data.exposed.jdbc.support

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.spring.data.exposed.jdbc.AbstractExposedJdbcRepositoryTest
import io.bluetape4k.spring.data.exposed.jdbc.mapping.ExposedMappingContext
import io.bluetape4k.spring.data.exposed.jdbc.mapping.ExposedPersistentEntity
import io.bluetape4k.spring.data.exposed.jdbc.repository.support.JdbcExamplePredicateCompiler
import io.bluetape4k.spring.data.exposed.jdbc.repository.support.JdbcPersistentPropertyResolver
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.dao.LongEntity
import org.jetbrains.exposed.v1.dao.LongEntityClass
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.migration.jdbc.MigrationUtils
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.dao.InvalidDataAccessApiUsageException
import org.springframework.data.domain.Example
import org.springframework.data.domain.ExampleMatcher
import java.util.Optional
import java.util.concurrent.atomic.AtomicInteger

class JdbcExamplePredicateCompilerTest: AbstractExposedJdbcRepositoryTest() {

    @BeforeEach
    fun createCompilerTable() {
        transaction {
            MigrationUtils.statementsRequiredForDatabaseMigration(QbeProfiles, withLogs = false)
                .forEach { exec(it) }
            QbeProfiles.deleteAll()
        }
    }

    @AfterEach
    fun clearCompilerTable() {
        transaction { QbeProfiles.deleteAll() }
    }

    @Test
    fun `attached persisted probe compiles matching all and excludes id`() {
        transaction {
            seedProfiles()
            val probe = QbeProfileEntity.find { QbeProfiles.name eq "Alice" }.single()
            val matcher = ExampleMatcher.matchingAll().withIgnorePaths("nickname", "age")

            val matches = QbeProfileEntity.find { compiler().compile(Example.of(probe, matcher)) }.toList()

            matches shouldHaveSize 1
            matches.single().name shouldBeEqualTo "Alice"
        }
    }

    @Test
    fun `matching any and all ignored use stable cardinality`() {
        transaction {
            seedProfiles()
            val probe = QbeProfileEntity.find { QbeProfiles.name eq "Alice" }.single()
            val anyMatcher = ExampleMatcher.matchingAny().withIgnorePaths("nickname")
            val ignoredMatcher = ExampleMatcher.matchingAll()
                .withIgnorePaths("name", "nickname", "age", "displayName")

            QbeProfileEntity.find { compiler().compile(Example.of(probe, anyMatcher)) }.toList() shouldHaveSize 2
            QbeProfileEntity.find { compiler().compile(Example.of(probe, ignoredMatcher)) }.toList() shouldHaveSize 3
        }
    }

    @Test
    fun `property transformer runs once and containing escapes literal wildcard characters`() {
        transaction {
            QbeProfileEntity.new {
                name = "literal%_\\value"; nickname = "target"; age = 10; displayName = "Literal A"
            }
            QbeProfileEntity.new {
                name = "literalXXvalue"; nickname = "other"; age = 11; displayName = "Literal B"
            }
            val probe = QbeProfileEntity.find { QbeProfiles.nickname eq "target" }.single()
            val calls = AtomicInteger()
            val matcher = ExampleMatcher.matchingAll()
                .withIgnorePaths("nickname", "age", "displayName")
                .withMatcher("name", ExampleMatcher.GenericPropertyMatchers.contains())
                .withTransformer("name") { value ->
                    calls.incrementAndGet()
                    Optional.of(value.orElseThrow().toString())
                }

            val matches = QbeProfileEntity.find { compiler().compile(Example.of(probe, matcher)) }.toList()

            calls.get() shouldBeEqualTo 1
            matches shouldHaveSize 1
            matches.single().name shouldBeEqualTo "literal%_\\value"
        }
    }

    @Test
    fun `snake case matcher alias applies to the canonical property`() {
        transaction {
            seedProfiles()
            val probe = QbeProfileEntity.find { QbeProfiles.name eq "Alice" }.single()
            val matcher = ExampleMatcher.matchingAll()
                .withIgnorePaths("name", "nickname", "age")
                .withMatcher("display_name", ExampleMatcher.GenericPropertyMatchers.exact())

            QbeProfileEntity.find { compiler().compile(Example.of(probe, matcher)) }.toList() shouldHaveSize 1

            val ignoredAlias = ExampleMatcher.matchingAll()
                .withIgnorePaths("name", "nickname", "age", "display_name")
            QbeProfileEntity.find { compiler().compile(Example.of(probe, ignoredAlias)) }.toList() shouldHaveSize 3
        }
    }

    @Test
    fun `include null compiles is null while ignore null omits the property`() {
        transaction {
            seedProfiles()
            val probe = QbeProfileEntity.find { QbeProfiles.name eq "NullNick" }.single()
            val include = ExampleMatcher.matchingAll()
                .withIgnorePaths("name", "age", "displayName")
                .withIncludeNullValues()
            val ignore = include.withIgnoreNullValues()

            QbeProfileEntity.find { compiler().compile(Example.of(probe, include)) }.toList() shouldHaveSize 1
            QbeProfileEntity.find { compiler().compile(Example.of(probe, ignore)) }.toList() shouldHaveSize 3
        }
    }

    @Test
    fun `unsupported matcher structure fails before attached probe validation`() {
        transaction {
            val unattachedProbe = QbeProfileEntity(EntityID(999L, QbeProfiles))
            val transformerCalls = AtomicInteger()

            assertFailsWith<UnsupportedOperationException> {
                compiler().compile(Example.of(unattachedProbe, ExampleMatcher.matching().withIgnoreCase()))
            }
            assertFailsWith<UnsupportedOperationException> {
                compiler().compile(
                    Example.of(
                        unattachedProbe,
                        ExampleMatcher.matching().withMatcher(
                            "name",
                            ExampleMatcher.GenericPropertyMatchers.regex(),
                        ),
                    ),
                )
            }
            assertFailsWith<UnsupportedOperationException> {
                compiler().compile(
                    Example.of(
                        unattachedProbe,
                        ExampleMatcher.matching()
                            .withStringMatcher(ExampleMatcher.StringMatcher.REGEX)
                            .withTransformer("name") { value ->
                                transformerCalls.incrementAndGet()
                                value
                            },
                    ),
                )
            }
            transformerCalls.get() shouldBeEqualTo 0
            assertFailsWith<UnsupportedOperationException> {
                compiler().compile(
                    Example.of(
                        unattachedProbe,
                        ExampleMatcher.matching()
                            .withIgnorePaths("name", "nickname")
                            .withStringMatcher(ExampleMatcher.StringMatcher.CONTAINING),
                    ),
                )
            }
            assertFailsWith<InvalidDataAccessApiUsageException> {
                compiler().compile(
                    Example.of(unattachedProbe, ExampleMatcher.matching().withIgnorePaths("nested.name")),
                )
            }
        }
    }

    @Test
    fun `new and wrong transaction probes are rejected`() {
        transaction {
            val newProbe = QbeProfileEntity(EntityID(999L, QbeProfiles))
            assertFailsWith<InvalidDataAccessApiUsageException> {
                compiler().compile(Example.of(newProbe))
            }

            val scheduledInsert = QbeProfileEntity.new {
                name = "Pending"
                nickname = null
                age = 19
                displayName = "Pending Insert"
            }
            assertFailsWith<InvalidDataAccessApiUsageException> {
                compiler().compile(Example.of(scheduledInsert))
            }
        }

        val detached = transaction {
            seedProfiles()
            QbeProfileEntity.find { QbeProfiles.name eq "Alice" }.single()
        }
        transaction {
            assertFailsWith<InvalidDataAccessApiUsageException> {
                compiler().compile(Example.of(detached))
            }
        }
    }

    @Test
    fun `probe getter failure redacts the reflected target cause`() {
        val getter = ThrowingProbe::class.java.getMethod("getSecret")

        val failure = assertFailsWith<InvalidDataAccessApiUsageException> {
            JdbcExamplePredicateCompiler.readProbeProperty(
                getter = getter,
                probe = ThrowingProbe(),
                propertyName = "secret\nproperty",
            )
        }

        throwableGraph(failure).contains("sensitive getter payload").shouldBeFalse()
        failure.message.orEmpty().contains('\n').shouldBeFalse()
    }

    private fun compiler(): JdbcExamplePredicateCompiler<QbeProfileEntity, Long> {
        @Suppress("UNCHECKED_CAST")
        val persistentEntity: ExposedPersistentEntity<QbeProfileEntity> =
            ExposedMappingContext().getRequiredPersistentEntity(QbeProfileEntity::class.java)
                as ExposedPersistentEntity<QbeProfileEntity>
        return JdbcExamplePredicateCompiler(
            persistentEntity = persistentEntity,
            propertyResolver = JdbcPersistentPropertyResolver(persistentEntity),
            entityClass = QbeProfileEntity,
            transaction = TransactionManager.current(),
        )
    }

    private fun seedProfiles() {
        QbeProfileEntity.new { name = "Alice"; nickname = "ally"; age = 30; displayName = "Alice A" }
        QbeProfileEntity.new { name = "Bob"; nickname = "bobby"; age = 30; displayName = "Bob B" }
        QbeProfileEntity.new { name = "NullNick"; nickname = null; age = 20; displayName = "Null N" }
    }

    private fun throwableGraph(failure: Throwable): String = buildString {
        var current: Throwable? = failure
        while (current != null) {
            appendLine(current.message)
            current = current.cause
        }
    }

    private class ThrowingProbe {
        fun getSecret(): String = error("sensitive getter payload")
    }
}

internal object QbeProfiles: LongIdTable("qbe_profiles") {
    val name = varchar("name", 128)
    val nickname = varchar("nickname", 128).nullable()
    val age = integer("age")
    val displayName = varchar("display_name", 128)
}

internal class QbeProfileEntity(id: EntityID<Long>): LongEntity(id) {
    companion object: LongEntityClass<QbeProfileEntity>(QbeProfiles)

    var name: String by QbeProfiles.name
    var nickname: String? by QbeProfiles.nickname
    var age: Int by QbeProfiles.age
    var displayName: String by QbeProfiles.displayName
}
