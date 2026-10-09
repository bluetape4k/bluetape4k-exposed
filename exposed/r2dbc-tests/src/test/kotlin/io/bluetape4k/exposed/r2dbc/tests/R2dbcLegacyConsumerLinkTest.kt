package io.bluetape4k.exposed.r2dbc.tests

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.junit5.coroutines.runSuspendIO
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationTargetException

class R2dbcLegacyConsumerLinkTest {

    @Test
    fun `2 0 0 consumer bytecode links against the current test support`() {
        val consumerType = Class.forName("consumer.R2dbc200Consumer")
        val consumer = consumerType.getDeclaredConstructor().newInstance()

        consumerType.getMethod("dialectMethodName").invoke(consumer) shouldBeEqualTo "enableDialects"
        consumerType.getMethod("faker").invoke(consumer).shouldNotBeNull()
        consumerType.getMethod("enabledDialects").invoke(consumer).shouldNotBeNull()
        consumerType.getMethod("schema").invoke(consumer).shouldNotBeNull()

        val noTransaction = assertFailsWith<InvocationTargetException> {
            consumerType.getMethod("addIfNotExists").invoke(consumer)
        }.cause
        val cause = noTransaction.shouldNotBeNull()
        cause::class.java shouldBeEqualTo IllegalStateException::class.java
    }

    @Test
    fun `2 0 0 consumer invokes the restored bridge inside a transaction`() = runSuspendIO {
        val consumerType = Class.forName("consumer.R2dbc200Consumer")
        val consumer = consumerType.getDeclaredConstructor().newInstance()

        withDb(TestDB.H2) {
            consumerType.getMethod("addIfNotExists").invoke(consumer) shouldBeEqualTo "IF NOT EXISTS "
        }
    }
}
