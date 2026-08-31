package io.bluetape4k.batch.api

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test

class BatchRepositoryRecoveryExhaustedExceptionTest {

    @Test
    fun `correlation id는 16자 Base58만 허용한다`() {
        val correlationId = "123456789ABCDEFG"

        BatchRepositoryRecoveryExhaustedException(correlationId).correlationId shouldBeEqualTo correlationId

        assertFailsWith<IllegalArgumentException> {
            BatchRepositoryRecoveryExhaustedException("too-short")
        }
        assertFailsWith<IllegalArgumentException> {
            BatchRepositoryRecoveryExhaustedException("0000000000000000")
        }
    }
}
