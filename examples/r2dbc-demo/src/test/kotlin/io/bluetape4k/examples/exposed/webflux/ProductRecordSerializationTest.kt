package io.bluetape4k.examples.exposed.webflux

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.examples.exposed.webflux.domain.ProductRecord
import org.junit.jupiter.api.Test
import java.io.ObjectStreamClass

class ProductRecordSerializationTest {

    @Test
    fun `ProductRecord declares the explicit serialVersionUID`() {
        ObjectStreamClass.lookup(ProductRecord::class.java).serialVersionUID shouldBeEqualTo 1L
    }
}
