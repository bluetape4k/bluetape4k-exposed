package io.bluetape4k.spring.data.exposed.jdbc.support

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.spring.data.exposed.jdbc.domain.UserEntity
import io.bluetape4k.spring.data.exposed.jdbc.repository.support.ExposedEntityInformation
import io.bluetape4k.spring.data.exposed.jdbc.repository.support.ExposedEntityInformationImpl
import io.bluetape4k.spring.data.exposed.jdbc.repository.support.ExposedJdbcRepositoryFactory
import io.bluetape4k.spring.data.exposed.jdbc.repository.support.SimpleExposedJdbcRepository
import org.junit.jupiter.api.Test
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.lang.reflect.Modifier

class ExposedJdbcRepositoryAbiCompatibilityTest {

    @Test
    fun `Kotlin consumer keeps the public one argument repository constructor`() {
        val entityInformation: ExposedEntityInformation<UserEntity, Long> =
            ExposedEntityInformationImpl(UserEntity::class.java)

        val repository = ExposedJdbcRepositoryKotlinConsumerFixture.createRepository(entityInformation)
        val factory = ExposedJdbcRepositoryKotlinConsumerFixture.createFactory()

        repository.javaClass shouldBeEqualTo SimpleExposedJdbcRepository::class.java
        factory.javaClass shouldBeEqualTo ExposedJdbcRepositoryFactory::class.java
    }

    @Test
    fun `internal companion factory stays hidden from Java source`() {
        val factory = SimpleExposedJdbcRepository.Companion::class.java.declaredMethods.single { method ->
            method.name.startsWith("create")
        }

        factory.isSynthetic.shouldBeTrue()
    }

    @Test
    fun `public constructor and method descriptors match the checked in baseline`() {
        val actual = buildList {
            add("# ExposedJdbcRepositoryFactory")
            addAll(publicAbiOf(ExposedJdbcRepositoryFactory::class.java))
            add("")
            add("# SimpleExposedJdbcRepository")
            addAll(publicAbiOf(SimpleExposedJdbcRepository::class.java))
        }.joinToString("\n")

        val expected = checkNotNull(javaClass.getResource("/abi/simple-exposed-jdbc-repository-public.txt"))
            .readText()
            .trimEnd()

        actual shouldBeEqualTo expected
    }

    private fun publicAbiOf(type: Class<*>): List<String> =
        buildList {
            type.declaredConstructors
                .filter { Modifier.isPublic(it.modifiers) }
                .mapTo(this) { "C ${it.jvmDescriptor()}" }
            type.declaredMethods
                .filter { Modifier.isPublic(it.modifiers) }
                .mapTo(this) { "M ${it.name}${it.jvmDescriptor()}" }
        }.sorted()

    private fun Executable.jvmDescriptor(): String =
        parameterTypes.joinToString(separator = "", prefix = "(", postfix = ")") { it.jvmDescriptor() } +
            if (this is Method) returnType.jvmDescriptor() else "V"

    private fun Class<*>.jvmDescriptor(): String = when {
        isPrimitive -> when (this) {
            Void.TYPE -> "V"
            Boolean::class.javaPrimitiveType -> "Z"
            Byte::class.javaPrimitiveType -> "B"
            Char::class.javaPrimitiveType -> "C"
            Short::class.javaPrimitiveType -> "S"
            Int::class.javaPrimitiveType -> "I"
            Long::class.javaPrimitiveType -> "J"
            Float::class.javaPrimitiveType -> "F"
            Double::class.javaPrimitiveType -> "D"
            else -> error("지원하지 않는 primitive type: $name")
        }
        isArray -> name.replace('.', '/')
        else -> "L${name.replace('.', '/')};"
    }
}
