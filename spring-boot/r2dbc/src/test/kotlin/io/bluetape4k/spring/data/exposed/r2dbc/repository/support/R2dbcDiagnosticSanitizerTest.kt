package io.bluetape4k.spring.data.exposed.r2dbc.repository.support

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import kotlin.test.Test

class R2dbcDiagnosticSanitizerTest {

    @Test
    fun `diagnostic tokens remove control and separator characters and cap length`() {
        val token = R2dbcDiagnosticSanitizer.propertyToken("name\nsecret=value;DROP TABLE users")

        token.any { it.code < 0x20 }.shouldBeFalse()
        token.any { it in "=;" }.shouldBeFalse()
        R2dbcDiagnosticSanitizer.propertyToken("x".repeat(256)).length shouldBeEqualTo 128
    }

    @Test
    fun `operation labels are fixed allowlist values`() {
        R2dbcDiagnosticSanitizer.operationLabel(R2dbcQbeOperation.FIND_ONE) shouldBeEqualTo "find-one"
        assertFailsWith<IllegalArgumentException> {
            R2dbcDiagnosticSanitizer.validateOperationLabel("user-provided-label")
        }
    }
}
