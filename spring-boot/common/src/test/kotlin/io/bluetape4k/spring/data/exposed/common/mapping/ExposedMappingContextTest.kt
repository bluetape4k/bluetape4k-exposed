package io.bluetape4k.spring.data.exposed.common.mapping

import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.junit5.concurrency.MultithreadingTester
import java.util.concurrent.ConcurrentLinkedQueue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ExposedMappingContextTest {

    private lateinit var context: ExposedMappingContext

    @BeforeEach
    fun setUp() {
        context = ExposedMappingContext()
    }

    @Test
    fun `createPersistentEntity returns an Exposed entity for UserEntity`() {
        val entity = context.getRequiredPersistentEntity(UserEntity::class.java)

        entity.shouldNotBeNull()
        entity.type shouldBeEqualTo UserEntity::class.java
    }

    @Test
    fun `persistent entity exposes its companion entityClass and table`() {
        val entity = context.getRequiredPersistentEntity(UserEntity::class.java)

        entity.getEntityClass().shouldNotBeNull()
        entity.getTable().shouldNotBeNull()
        entity.getTable()?.tableName shouldBeEqualTo "users"
    }

    @Test
    fun `persistent properties resolve Exposed columns and ignore unsupported properties`() {
        val entity = context.getRequiredPersistentEntity(UserEntity::class.java)

        entity.getPersistentProperty("name")?.getColumn()?.name shouldBeEqualTo "name"
        entity.getPersistentProperty("email")?.getColumn()?.name shouldBeEqualTo "email"
        entity.getPersistentProperty("missing").shouldBeNull()
    }

    @Test
    fun `plain class has no Exposed entityClass or table`() {
        data class PlainClass(val id: Long, val name: String)

        val entity = context.getRequiredPersistentEntity(PlainClass::class.java)

        entity.getEntityClass().shouldBeNull()
        entity.getTable().shouldBeNull()
    }

    @Test
    fun `hasPersistentEntityFor reflects only accessed classes`() {
        data class NotRegistered(val value: Int)

        context.hasPersistentEntityFor(NotRegistered::class.java).shouldBeFalse()
        context.getRequiredPersistentEntity(UserEntity::class.java)
        context.hasPersistentEntityFor(UserEntity::class.java).shouldBeTrue()
    }

    @Test
    fun `concurrent lookups publish one cached persistent entity`() {
        val entities = ConcurrentLinkedQueue<ExposedPersistentEntity<*>>()

        MultithreadingTester()
            .workers(8)
            .rounds(4)
            .add {
                entities += context.getRequiredPersistentEntity(UserEntity::class.java)
            }
            .run()

        entities shouldHaveSize 32
        val first = entities.first()
        entities.all { it === first }.shouldBeTrue()
        entities.forEach { entity ->
            entity.getTable()?.tableName shouldBeEqualTo "users"
        }
    }
}
