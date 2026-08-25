package io.bluetape4k.exposed.r2dbc.repository

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.exposed.core.dao.id.SoftDeletedIdTable
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** R2DBC UUID 특수화가 case-only JVM 이름을 만들지 않는지 검증합니다. */
class UuidRepositoryNamingTest {

    @Test
    fun `canonical UUID repository names are filesystem safe`() {
        val names =
            listOf(
                KotlinUuidR2dbcRepository::class.java,
                JavaUuidR2dbcRepository::class.java,
                KotlinUuidSoftDeletedR2dbcRepository::class.java,
                JavaUuidSoftDeletedR2dbcRepository::class.java,
            ).map(Class<*>::getName)

        names.distinct().size shouldBeEqualTo names.size
        names.all { it.substringAfterLast('.').contains("Uuid") }.shouldBeTrue()
        names.none { it.substringAfterLast('.').contains("UUID") }.shouldBeTrue()
    }

    @Test
    @OptIn(ExperimentalUuidApi::class)
    @Suppress("DEPRECATION")
    fun `legacy aliases remain source compatible`() {
        val kotlinR2dbc: UuidR2dbcRepository<Any>? = null
        val javaR2dbc: UUIDR2dbcRepository<Any>? = null
        val kotlinSoftDeleted: UuidSoftDeletedR2dbcRepository<Any, SoftDeletedIdTable<Uuid>>? = null
        val javaSoftDeleted: UUIDSoftDeletedR2dbcRepository<Any, SoftDeletedIdTable<UUID>>? = null

        val aliases = listOf(kotlinR2dbc, javaR2dbc, kotlinSoftDeleted, javaSoftDeleted)

        aliases.size shouldBeEqualTo 4
    }
}
