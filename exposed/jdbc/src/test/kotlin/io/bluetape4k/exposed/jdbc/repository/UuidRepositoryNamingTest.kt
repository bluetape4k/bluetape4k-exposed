package io.bluetape4k.exposed.jdbc.repository

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.exposed.core.dao.id.SoftDeletedIdTable
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** JDBC UUID 특수화가 case-only JVM 이름을 만들지 않는지 검증합니다. */
class UuidRepositoryNamingTest {

    @Test
    fun `canonical UUID repository names are filesystem safe`() {
        val names =
            listOf(
                KotlinUuidJdbcRepository::class.java,
                JavaUuidJdbcRepository::class.java,
                KotlinUuidSoftDeletedJdbcRepository::class.java,
                JavaUuidSoftDeletedJdbcRepository::class.java,
            ).map(Class<*>::getName)

        names.distinct().size shouldBeEqualTo names.size
        names.all { it.substringAfterLast('.').contains("Uuid") }.shouldBeTrue()
        names.none { it.substringAfterLast('.').contains("UUID") }.shouldBeTrue()
    }

    @Test
    @OptIn(ExperimentalUuidApi::class)
    @Suppress("DEPRECATION")
    fun `legacy aliases remain source compatible`() {
        val kotlinJdbc: UuidJdbcRepository<Any>? = null
        val javaJdbc: UUIDJdbcRepository<Any>? = null
        val kotlinSoftDeleted: UuidSoftDeletedJdbcRepository<Any, SoftDeletedIdTable<Uuid>>? = null
        val javaSoftDeleted: UUIDSoftDeletedJdbcRepository<Any, SoftDeletedIdTable<UUID>>? = null

        val aliases = listOf(kotlinJdbc, javaJdbc, kotlinSoftDeleted, javaSoftDeleted)

        aliases.size shouldBeEqualTo 4
    }
}
