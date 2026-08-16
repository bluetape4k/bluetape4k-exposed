package io.bluetape4k.spring.data.exposed.jdbc.support

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.spring.data.exposed.jdbc.mapping.ExposedMappingContext
import io.bluetape4k.spring.data.exposed.jdbc.repository.support.JdbcFluentQueryPlan
import io.bluetape4k.spring.data.exposed.jdbc.repository.support.JdbcFluentQueryScope
import org.junit.jupiter.api.Test
import org.springframework.dao.InvalidDataAccessApiUsageException
import org.springframework.data.domain.Example
import org.springframework.data.domain.Sort
import org.springframework.data.projection.SpelAwareProxyProjectionFactory
import java.util.concurrent.Executors

class JdbcFluentQueryPlanTest {

    private data class Probe(val name: String)
    private data class NameProjection(val name: String)
    private data class AlternateProjection(val name: String)

    private val transactionIdentity = Any()
    private val scope = JdbcFluentQueryScope.open(transactionIdentity)
    private val plan = JdbcFluentQueryPlan.create(
        example = Example.of(Probe("alice")),
        domainType = Probe::class.java,
        projectionFactory = SpelAwareProxyProjectionFactory(),
        persistentEntity = ExposedMappingContext().getRequiredPersistentEntity(Probe::class.java),
        scope = scope,
    )

    @Test
    fun `as and project are immutable last wins transitions`() {
        val requested = linkedSetOf("name")
        val first = plan.asType(NameProjection::class.java).withProperties(requested)
        requested += "ignoredAfterSnapshot"
        val second = first.asType(AlternateProjection::class.java).withProperties(listOf("name"))

        plan.resultType shouldBeEqualTo Probe::class.java
        plan.explicitProperties.isEmpty().shouldBeTrue()
        first.resultType shouldBeEqualTo NameProjection::class.java
        first.explicitProperties shouldBeEqualTo linkedSetOf("name")
        second.resultType shouldBeEqualTo AlternateProjection::class.java
        second.explicitProperties shouldBeEqualTo linkedSetOf("name")
    }

    @Test
    fun `as and project order produces equivalent final plan state`() {
        val asThenProject = plan.asType(NameProjection::class.java).withProperties(listOf("name"))
        val projectThenAs = plan.withProperties(listOf("name")).asType(NameProjection::class.java)

        asThenProject.resultType shouldBeEqualTo projectThenAs.resultType
        asThenProject.explicitProperties shouldBeEqualTo projectThenAs.explicitProperties
    }

    @Test
    fun `empty project restores automatic required property selection`() {
        val automatic = plan.withProperties(listOf("name")).withProperties(emptyList())

        automatic.explicitProperties.isEmpty().shouldBeTrue()
        automatic.propertiesSpecified.shouldBeFalse()
    }

    @Test
    fun `sort appends and unsorted is a no-op`() {
        val sorted = plan
            .withSort(Sort.by(Sort.Order.asc("name")))
            .withSort(Sort.unsorted())
            .withSort(Sort.by(Sort.Order.desc("id")))

        sorted.sort.toList().map { it.property } shouldBeEqualTo listOf("name", "id")
        sorted.sort.toList().map { it.direction } shouldBeEqualTo
            listOf(Sort.Direction.ASC, Sort.Direction.DESC)
    }

    @Test
    fun `limit last wins and zero means unlimited`() {
        plan.withLimit(10).withLimit(3).limit shouldBeEqualTo 3
        plan.withLimit(10).withLimit(0).hasLimit.shouldBeFalse()
        assertFailsWith<IllegalArgumentException> { plan.withLimit(-1) }
    }

    @Test
    fun `null sort and properties are rejected`() {
        assertFailsWith<IllegalArgumentException> { plan.withSort(null) }
        assertFailsWith<IllegalArgumentException> { plan.withProperties(null) }
    }

    @Test
    fun `closed callback wrong transaction and wrong thread fail before terminal work`() {
        plan.validateScope(transactionIdentity)
        assertFailsWith<InvalidDataAccessApiUsageException> { plan.validateScope(Any()) }

        Executors.newSingleThreadExecutor().use { executor ->
            val failure = executor.submit<Throwable?> {
                runCatching { plan.validateScope(transactionIdentity) }.exceptionOrNull()
            }.get()
            (failure is InvalidDataAccessApiUsageException).shouldBeTrue()
        }

        scope.close()
        assertFailsWith<InvalidDataAccessApiUsageException> { plan.validateScope(transactionIdentity) }
    }
}
