package io.bluetape4k.spring.data.exposed.common.annotation

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test

class AnnotationMetadataTest {

    @Test
    fun `ExposedEntity is runtime visible on an entity class`() {
        AnnotatedEntity::class.java.isAnnotationPresent(ExposedEntity::class.java).shouldBeTrue()
    }

    @Test
    fun `Query exposes value and countQuery to Spring Data metadata`() {
        val method = QueryHolder::class.java.getDeclaredMethod("findByName", String::class.java)
        val query = method.getAnnotation(Query::class.java)

        query.value shouldBeEqualTo "SELECT * FROM users WHERE name = ?1"
        query.countQuery shouldBeEqualTo "SELECT COUNT(*) FROM users WHERE name = ?1"
    }
}

@ExposedEntity
private class AnnotatedEntity

private class QueryHolder {

    @Query(
        value = "SELECT * FROM users WHERE name = ?1",
        countQuery = "SELECT COUNT(*) FROM users WHERE name = ?1",
    )
    @Suppress("UnusedParameter")
    fun findByName(name: String): List<AnnotatedEntity> = emptyList()
}
