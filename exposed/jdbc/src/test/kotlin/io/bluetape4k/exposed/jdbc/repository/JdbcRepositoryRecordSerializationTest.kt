package io.bluetape4k.exposed.jdbc.repository

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test
import java.io.ObjectStreamClass
import java.io.Serializable

/**
 * Verifies repository test records keep stable Java serialization contracts.
 */
class JdbcRepositoryRecordSerializationTest {

    @Test
    fun `repository test records are Serializable with stable serialVersionUID`() {
        val recordTypes = listOf(
            AuditableJdbcRepositoryTest.ActorRecord::class.java,
            AuditableJdbcRepositoryEdgeCaseTest.AuditableEdgeCaseRecord::class.java,
            AuditableJdbcRepositoryVariantTest.IntAuditableRecord::class.java,
            AuditableJdbcRepositoryVariantTest.UUIDAuditableRecord::class.java,
            EdgeCaseSchema.EdgeCaseRecord::class.java,
            SoftDeletedJdbcRepositoryEdgeCaseTest.EdgeCaseRecord::class.java,
            SoftDeletedJdbcRepositoryTest.ContactRecord::class.java,
        )

        recordTypes.forEach { recordType ->
            Serializable::class.java.isAssignableFrom(recordType).shouldBeTrue()
            ObjectStreamClass.lookup(recordType).serialVersionUID shouldBeEqualTo 1L
        }
    }
}
