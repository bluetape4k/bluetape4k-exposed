package io.bluetape4k.spring.data.exposed.jdbc.query

import io.bluetape4k.logging.KLogging
import io.bluetape4k.spring.data.exposed.common.annotation.Query as CommonQuery
import io.bluetape4k.spring.data.exposed.jdbc.repository.UserJdbcRepository
import io.bluetape4k.spring.data.exposed.jdbc.repository.query.ExposedQueryMethod
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.assertions.shouldStartWith
import org.junit.jupiter.api.Test
import org.springframework.data.projection.SpelAwareProxyProjectionFactory
import org.springframework.data.repository.core.support.DefaultRepositoryMetadata

class ExposedQueryMethodTest {

    companion object : KLogging()

    private val metadata = DefaultRepositoryMetadata(UserJdbcRepository::class.java)
    private val factory = SpelAwareProxyProjectionFactory()

    private fun method(name: String, vararg paramTypes: Class<*>) =
        UserJdbcRepository::class.java.getMethod(name, *paramTypes)

    @Test
    fun `isAnnotatedQuery returns false for PartTree method`() {
        val m = method("findByName", String::class.java)
        val qm = ExposedQueryMethod(m, metadata, factory)

        qm.isAnnotatedQuery.shouldBeFalse()
        qm.getAnnotatedQuery().shouldBeNull()
    }

    @Test
    fun `isAnnotatedQuery returns true for @Query annotated method`() {
        val m = method("findByEmailNative", String::class.java)
        val qm = ExposedQueryMethod(m, metadata, factory)

        qm.isAnnotatedQuery.shouldBeTrue()
        qm.getAnnotatedQuery().shouldNotBeNull()
        val query = qm.getAnnotatedQuery()
        query.shouldNotBeNull()
        query shouldStartWith "SELECT"
    }

    @Test
    fun `getAnnotatedQuery returns the SQL from @Query annotation`() {
        val m = method("findByEmailNative", String::class.java)
        val qm = ExposedQueryMethod(m, metadata, factory)

        val sql = qm.getAnnotatedQuery()
        sql.shouldNotBeNull()
        sql.contains("users").shouldBeTrue()
    }

    @Test
    fun `common Query annotation is accepted while legacy JDBC facade remains available`() {
        val commonMetadata = DefaultRepositoryMetadata(CommonQueryRepository::class.java)
        val method = CommonQueryRepository::class.java.getMethod("findByNameCommon", String::class.java)
        val qm = ExposedQueryMethod(method, commonMetadata, factory)

        qm.isAnnotatedQuery.shouldBeTrue()
        qm.getAnnotatedQuery() shouldBeEqualTo "SELECT * FROM users WHERE name = ?1"
        qm.getCountQuery() shouldBeEqualTo "SELECT COUNT(*) FROM users WHERE name = ?1"
    }

    @Test
    fun `getCountQuery returns null when countQuery is not set`() {
        val m = method("findByEmailNative", String::class.java)
        val qm = ExposedQueryMethod(m, metadata, factory)

        qm.getCountQuery().shouldBeNull()
    }

    @Test
    fun `getName returns the method name`() {
        val m = method("findByName", String::class.java)
        val qm = ExposedQueryMethod(m, metadata, factory)

        qm.name.shouldNotBeNull()
    }

    @Test
    fun `isCollectionQuery returns true for list-returning methods`() {
        val m = method("findByName", String::class.java)
        val qm = ExposedQueryMethod(m, metadata, factory)

        qm.isCollectionQuery.shouldBeTrue()
    }

    @Test
    fun `domainClass returns non-null for UserEntity method`() {
        val m = method("findByName", String::class.java)
        val qm = ExposedQueryMethod(m, metadata, factory)

        qm.entityInformation.javaType.shouldNotBeNull()
    }
}

private interface CommonQueryRepository: org.springframework.data.repository.Repository<
    io.bluetape4k.spring.data.exposed.jdbc.domain.UserEntity,
    Long,
> {
    @CommonQuery(
        value = "SELECT * FROM users WHERE name = ?1",
        countQuery = "SELECT COUNT(*) FROM users WHERE name = ?1",
    )
    fun findByNameCommon(name: String): List<io.bluetape4k.spring.data.exposed.jdbc.domain.UserEntity>
}
