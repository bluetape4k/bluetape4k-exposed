package io.bluetape4k.examples.exposed.mvc

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.examples.exposed.mvc.domain.ProductRecord
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test
import java.io.ObjectStreamClass

class ProductRecordSerializationTest {

    companion object: KLogging()

    @Test
    fun `ProductRecord declares the explicit serialVersionUID`() {
        ObjectStreamClass.lookup(ProductRecord::class.java).serialVersionUID shouldBeEqualTo 1L
    }
}
