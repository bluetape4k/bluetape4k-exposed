package io.bluetape4k.spring.data.exposed.common.annotation

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.junit.jupiter.api.Test
import java.lang.reflect.Method

class AnnotationMetadataTest {

    companion object: KLogging()

    @Test
    fun `ExposedEntity is runtime visible on an entity class`() {
        AnnotatedEntity::class.java.isAnnotationPresent(ExposedEntity::class.java).shouldBeTrue()
    }

    @Test
    fun `Query exposes value and countQuery to Spring Data metadata`() {
        val method: Method = QueryHolder::class.java.getDeclaredMethod("findByName", String::class.java)
        val query: Query = method.getAnnotation(Query::class.java)

        log.debug { "query=$query" }
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
