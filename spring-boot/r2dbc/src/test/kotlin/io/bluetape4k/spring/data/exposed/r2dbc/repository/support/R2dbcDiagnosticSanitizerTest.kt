package io.bluetape4k.spring.data.exposed.r2dbc.repository.support

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import kotlin.test.Test

class R2dbcDiagnosticSanitizerTest {

    companion object: KLogging()

    @Test
    fun `diagnostic tokens remove control and separator characters and cap length`() {
        val token = R2dbcDiagnosticSanitizer.propertyToken("name\nsecret=value;DROP TABLE users")

        log.debug { "token=$token" }
        token.any { it.code < 0x20 }.shouldBeFalse()
        token.any { it in "=;" }.shouldBeFalse()

        val xs = R2dbcDiagnosticSanitizer.propertyToken("x".repeat(256))
        log.debug { "xs=$xs" }
        xs.length shouldBeEqualTo R2dbcDiagnosticSanitizer.MAX_TOKEN_LENGTH
    }

    @Test
    fun `operation labels are fixed allowlist values`() {
        R2dbcDiagnosticSanitizer.operationLabel(R2dbcQbeOperation.FIND_ONE) shouldBeEqualTo "find-one"

        assertFailsWith<IllegalArgumentException> {
            R2dbcDiagnosticSanitizer.validateOperationLabel("user-provided-label")
        }
    }
}
