package io.bluetape4k.spring.data.exposed.r2dbc.repository.support

import io.bluetape4k.assertions.assertFailsWith
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class R2dbcTransactionLeaseTest {

    @Test
    fun `same lease rejects overlapping terminal and releases after success`() = runBlocking {
        val lease = R2dbcTransactionLease()
        lease.withLease {
            assertFailsWith<org.springframework.dao.InvalidDataAccessApiUsageException> {
                runBlocking { lease.withLease { } }
            }
        }
        assertEquals(Unit, lease.withLease { Unit })
    }

    @Test
    fun `ordinary failure and cancellation release lease without wrapping`() = runBlocking {
        val lease = R2dbcTransactionLease()
        val failure = IllegalStateException("failure")
        assertSame(failure, assertFailsWith<IllegalStateException> { lease.withLease { throw failure } })
        lease.withLease { }

        val cancellation = CancellationException("cancel")
        assertSame(cancellation, assertFailsWith<CancellationException> { lease.withLease { throw cancellation } })
        lease.withLease { }
    }
}
