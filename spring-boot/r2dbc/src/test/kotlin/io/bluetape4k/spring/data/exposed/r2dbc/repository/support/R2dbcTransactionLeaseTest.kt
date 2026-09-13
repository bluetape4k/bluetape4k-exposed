package io.bluetape4k.spring.data.exposed.r2dbc.repository.support

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.junit5.coroutines.runSuspendIO
import kotlinx.coroutines.CancellationException
import org.springframework.dao.InvalidDataAccessApiUsageException
import kotlin.test.Test

class R2dbcTransactionLeaseTest {

    @Test
    fun `same lease rejects overlapping terminal and releases after success`() = runSuspendIO {
        val lease = R2dbcTransactionLease()

        lease.withLease {
            assertFailsWith<InvalidDataAccessApiUsageException> {
                lease.withLease { }
            }
        }

        lease.withLease { } shouldBeEqualTo Unit
    }

    @Test
    fun `ordinary failure and cancellation release lease without wrapping`() = runSuspendIO {
        val lease = R2dbcTransactionLease()
        val failure = IllegalStateException("failure")

        assertFailsWith<IllegalStateException> {
            lease.withLease { throw failure }
        } shouldBeSameInstanceAs failure
        lease.withLease { }

        val cancellation = CancellationException("cancel")
        val thrownCancellation = assertFailsWith<CancellationException> {
            lease.withLease { throw cancellation }
        }
        thrownCancellation shouldBeSameInstanceAs cancellation
        lease.withLease { }
    }
}
