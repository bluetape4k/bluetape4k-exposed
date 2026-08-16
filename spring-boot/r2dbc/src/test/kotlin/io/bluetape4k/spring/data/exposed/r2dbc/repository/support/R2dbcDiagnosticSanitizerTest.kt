package io.bluetape4k.spring.data.exposed.r2dbc.repository.support

import io.bluetape4k.assertions.assertFailsWith
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class R2dbcDiagnosticSanitizerTest {

    @Test
    fun `diagnostic tokens remove control and separator characters and cap length`() {
        val token = R2dbcDiagnosticSanitizer.propertyToken("name\nsecret=value;DROP TABLE users")

        assertFalse(token.any { it.code < 0x20 })
        assertFalse(token.any { it in "=;" })
        assertEquals(128, R2dbcDiagnosticSanitizer.propertyToken("x".repeat(256)).length)
    }

    @Test
    fun `operation labels are fixed allowlist values`() {
        assertEquals("find-one", R2dbcDiagnosticSanitizer.operationLabel(R2dbcQbeOperation.FIND_ONE))
        assertFailsWith<IllegalArgumentException> {
            R2dbcDiagnosticSanitizer.validateOperationLabel("user-provided-label")
        }
    }
}
