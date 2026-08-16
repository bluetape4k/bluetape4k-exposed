package io.bluetape4k.spring.data.exposed.r2dbc.repository.support

import io.bluetape4k.spring.data.exposed.r2dbc.domain.User
import io.bluetape4k.spring.data.exposed.r2dbc.domain.UserNameRecord
import io.bluetape4k.spring.data.exposed.r2dbc.domain.Users
import io.bluetape4k.spring.data.exposed.r2dbc.repository.ExposedCoroutineFluentQuery
import io.bluetape4k.spring.data.exposed.r2dbc.repository.ExposedCoroutineQueryByExampleExecutor
import io.bluetape4k.spring.data.exposed.r2dbc.repository.ExposedR2dbcRepository
import io.bluetape4k.spring.data.exposed.r2dbc.repository.ExposedR2dbcQueryByExampleRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ResultRow
import org.springframework.data.domain.Example
import org.springframework.data.domain.Sort
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Public coroutine QBE API consumer fixture.
 *
 * The fixture intentionally exercises only the Kotlin-native contract. Reactor types and
 * Java-only `Class<R>` overloads are not part of this compatibility surface.
 */
class ExposedR2dbcRepositoryAbiCompatibilityTest {

    @Test
    fun `coroutine QBE consumer fixture is loadable`() {
        ApiFixtureRepository::class.java
        assertEquals(UserNameRecord::class.java, ExposedR2dbcRepositoryJavaConsumerFixture.projectionType())
    }

    @Test
    fun `public descriptors match the checked snapshot`() {
        val expected = javaClass.getResourceAsStream("/abi/exposed-r2dbc-repository-public.txt")!!
            .bufferedReader()
            .lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split('|', limit = 3) }
            .groupBy({ it[0] }, { "${it[1]} ${it[2]}" })
            .mapValues { (_, members) -> members.sorted() }

        assertEquals(
            expected[ExposedR2dbcRepository::class.java.name],
            declaredDescriptors(ExposedR2dbcRepository::class.java),
        )
        assertEquals(
            expected[ExposedCoroutineQueryByExampleExecutor::class.java.name],
            declaredDescriptors(ExposedCoroutineQueryByExampleExecutor::class.java),
        )
        assertEquals(
            expected[ExposedCoroutineFluentQuery::class.java.name],
            declaredDescriptors(ExposedCoroutineFluentQuery::class.java),
        )

        val constructors = SimpleExposedR2dbcRepository::class.java.declaredConstructors
            .filter { Modifier.isPublic(it.modifiers) }
        assertEquals(1, constructors.size)
        assertEquals(
            expected[SimpleExposedR2dbcRepository::class.java.name],
            constructors.map { "<init> ${constructorDescriptor(it)}" },
        )
        assertTrue(SimpleExposedR2dbcRepository::class.java.constructors.single().parameterCount == 4)
    }

    private fun declaredDescriptors(type: Class<*>): List<String> =
        type.declaredMethods
            .filterNot { it.isSynthetic }
            .map { "${it.name} ${methodDescriptor(it.parameterTypes, it.returnType)}" }
            .sorted()

    private fun constructorDescriptor(constructor: java.lang.reflect.Constructor<*>): String =
        methodDescriptor(constructor.parameterTypes, Void.TYPE)

    private fun methodDescriptor(parameters: Array<Class<*>>, returnType: Class<*>): String =
        parameters.joinToString(separator = "", prefix = "(", postfix = ")") { it.jvmDescriptor() } +
            returnType.jvmDescriptor()

    private fun Class<*>.jvmDescriptor(): String = when {
        isPrimitive -> when (this) {
            java.lang.Void.TYPE -> "V"
            java.lang.Boolean.TYPE -> "Z"
            java.lang.Byte.TYPE -> "B"
            java.lang.Character.TYPE -> "C"
            java.lang.Short.TYPE -> "S"
            java.lang.Integer.TYPE -> "I"
            java.lang.Long.TYPE -> "J"
            java.lang.Float.TYPE -> "F"
            java.lang.Double.TYPE -> "D"
            else -> error("unknown primitive: $this")
        }
        isArray -> name.replace('.', '/')
        else -> "L${name.replace('.', '/')};"
    }
}

private interface ApiFixtureRepository: ExposedR2dbcQueryByExampleRepository<User, Long> {
    override val table: Users get() = Users

    override fun extractId(entity: User): Long? = entity.id

    override fun toDomain(row: ResultRow): User = User(
        id = row[Users.id].value,
        name = row[Users.name],
        email = row[Users.email],
        age = row[Users.age],
    )

    override fun toPersistValues(domain: User): Map<Column<*>, Any?> = mapOf(
        Users.name to domain.name,
        Users.email to domain.email,
        Users.age to domain.age,
    )
}

@Suppress("UNUSED_PARAMETER")
private suspend fun compileCoroutineQbeConsumer(
    repository: ApiFixtureRepository,
    example: Example<User>,
): List<NameProjection> {
    repository.findOne(example)
    repository.findAll(example)
    repository.findAll(example, Sort.by("name"))
    repository.count(example)
    repository.exists(example)

    val names: Flow<NameProjection> = repository.findBy(example) { query ->
        query
            .asType(NameProjection::class)
            .project("name")
            .all()
    }
    return names.toList()
}

private interface NameProjection {
    val name: String
}
