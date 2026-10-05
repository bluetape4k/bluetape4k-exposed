package io.bluetape4k.exposed.r2dbc.tests

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class R2dbcLegacyConsumerLinkTest {

    @Test
    fun `2 0 0 consumer bytecode links against the current test support`() {
        val consumerType = Class.forName("consumer.R2dbc200Consumer")
        val consumer = consumerType.getDeclaredConstructor().newInstance()

        assertEquals("enableDialects", consumerType.getMethod("dialectMethodName").invoke(consumer))
        assertNotNull(consumerType.getMethod("faker").invoke(consumer))
        assertNotNull(consumerType.getMethod("enabledDialects").invoke(consumer))
        assertNotNull(consumerType.getMethod("schema").invoke(consumer))
        assertEquals("linked-without-transaction", consumerType.getMethod("addIfNotExists").invoke(consumer))
    }
}
