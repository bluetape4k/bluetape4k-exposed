package io.bluetape4k.spring.data.exposed.r2dbc.repository.support

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

class R2dbcTransactionLeaseTest {

    @Test
    fun `same lease rejects overlapping terminal and releases after success`() = runBlocking {
        val lease = R2dbcTransactionLease()
        lease.withLease {
            assertFailsWith<org.springframework.dao.InvalidDataAccessApiUsageException> {
                runBlocking { lease.withLease { } }
            }
        }
        lease.withLease { Unit } shouldBeEqualTo Unit
    }

    @Test
    fun `ordinary failure and cancellation release lease without wrapping`() = runBlocking {
        val lease = R2dbcTransactionLease()
        val failure = IllegalStateException("failure")
        (assertFailsWith<IllegalStateException> { lease.withLease { throw failure } }) shouldBeSameInstanceAs failure
        lease.withLease { }

        val cancellation = CancellationException("cancel")
        val thrownCancellation = assertFailsWith<CancellationException> {
            lease.withLease { throw cancellation }
        }
        thrownCancellation shouldBeSameInstanceAs cancellation
        lease.withLease { }
    }
}
