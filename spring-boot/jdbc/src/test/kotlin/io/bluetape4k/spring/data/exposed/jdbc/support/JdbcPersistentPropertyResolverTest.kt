package io.bluetape4k.spring.data.exposed.jdbc.support

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.spring.data.exposed.jdbc.domain.UserEntity
import io.bluetape4k.spring.data.exposed.jdbc.domain.Users
import io.bluetape4k.spring.data.exposed.jdbc.mapping.ExposedMappingContext
import io.bluetape4k.spring.data.exposed.jdbc.repository.support.JdbcPersistentPropertyResolver
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.dao.LongEntity
import org.jetbrains.exposed.v1.dao.LongEntityClass
import org.junit.jupiter.api.Test
import org.springframework.dao.InvalidDataAccessApiUsageException

class JdbcPersistentPropertyResolverTest {

    @Test
    fun `logical and snake case names resolve to the same column`() {
        val resolver = resolverFor(ProfileEntity::class.java)

        resolver.resolve("displayName").column shouldBeEqualTo Profiles.displayName
        resolver.resolve("display_name").column shouldBeEqualTo Profiles.displayName
    }

    @Test
    fun `domain property resolves to its persistent metadata`() {
        val persistentEntity = ExposedMappingContext().getRequiredPersistentEntity(UserEntity::class.java)
        val resolved = JdbcPersistentPropertyResolver(persistentEntity).resolve("name")

        resolved.logicalName shouldBeEqualTo "name"
        resolved.column shouldBeEqualTo Users.name
        resolved.valueType shouldBeEqualTo String::class.java
        persistentEntity.filter { it.name == "name" } shouldHaveSize 1
    }

    @Test
    fun `unknown nested and ambiguous names fail deterministically`() {
        val resolver = resolverFor(UserEntity::class.java)
        assertFailsWith<InvalidDataAccessApiUsageException> { resolver.resolve("missing") }
        assertFailsWith<InvalidDataAccessApiUsageException> { resolver.resolve("address.city") }
        assertFailsWith<InvalidDataAccessApiUsageException> {
            resolverFor(AmbiguousEntity::class.java).resolve("display_name")
        }
    }

    private fun resolverFor(type: Class<*>): JdbcPersistentPropertyResolver =
        JdbcPersistentPropertyResolver(ExposedMappingContext().getRequiredPersistentEntity(type))

}

internal object Profiles: LongIdTable("projection_profiles") {
    val displayName = varchar("display_name", 64)
}

internal class ProfileEntity(id: EntityID<Long>): LongEntity(id) {
    companion object: LongEntityClass<ProfileEntity>(Profiles)
    var displayName: String by Profiles.displayName
}

internal object AmbiguousProfiles: LongIdTable("ambiguous_projection_profiles") {
    val displayName = varchar("display_name", 64)
    val alternateName = varchar("alternate_name", 64)
}

internal class AmbiguousEntity(id: EntityID<Long>): LongEntity(id) {
    companion object: LongEntityClass<AmbiguousEntity>(AmbiguousProfiles)
    var displayName: String by AmbiguousProfiles.displayName
    @Suppress("VariableNaming")
    var display_name: String by AmbiguousProfiles.alternateName
}
