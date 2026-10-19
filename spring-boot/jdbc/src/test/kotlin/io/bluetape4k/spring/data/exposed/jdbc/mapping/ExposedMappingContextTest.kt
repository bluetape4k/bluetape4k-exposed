package io.bluetape4k.spring.data.exposed.jdbc.mapping

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.logging.KLogging
import io.bluetape4k.spring.data.exposed.jdbc.domain.UserEntity
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ExposedMappingContextTest {

    companion object: KLogging()

    private lateinit var context: ExposedMappingContext

    @BeforeEach
    fun setUp() {
        context = ExposedMappingContext()
    }

    @Test
    fun `createPersistentEntity returns DefaultExposedPersistentEntity for UserEntity`() {
        val entity = context.getRequiredPersistentEntity<UserEntity>()
        entity.shouldNotBeNull()
        entity.type shouldBeEqualTo UserEntity::class.java
    }

    @Test
    fun `getPersistentEntity returns entity for registered class`() {
        context.setInitialEntitySet(setOf(UserEntity::class.java))
        val entity = context.getPersistentEntity<UserEntity>()
        entity.shouldNotBeNull()
    }

    @Test
    fun `persistent entity for UserEntity has companion entityClass`() {
        val entity = context.getRequiredPersistentEntity<UserEntity>()
        entity.shouldNotBeNull()

        val entityClass = entity.getEntityClass()
        entityClass.shouldNotBeNull()
    }

    @Test
    fun `persistent entity for UserEntity has table`() {
        val entity = context.getRequiredPersistentEntity<UserEntity>()
        val table = entity.getTable()
        table.shouldNotBeNull()
        table.tableName shouldBeEqualTo "users"
    }

    @Test
    fun `persistent entity for plain data class has null table`() {
        data class PlainClass(val id: Long, val name: String)

        val entity = context.getRequiredPersistentEntity<PlainClass>()
        entity.shouldNotBeNull()
        entity.getTable().shouldBeNull()
        entity.getEntityClass().shouldBeNull()
    }

    @Test
    fun `hasPersistentEntityFor returns true after accessing entity`() {
        // Force entity creation via getRequiredPersistentEntity first
        context.getRequiredPersistentEntity<UserEntity>()
        context.hasPersistentEntityFor<UserEntity>().shouldBeTrue()
    }

    @Test
    fun `hasPersistentEntityFor returns false for unregistered class`() {
        data class NotRegistered(val x: Int)
        context.hasPersistentEntityFor<NotRegistered>().shouldBeFalse()
    }
}
