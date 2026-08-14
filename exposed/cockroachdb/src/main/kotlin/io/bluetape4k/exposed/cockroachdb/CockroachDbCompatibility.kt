package io.bluetape4k.exposed.cockroachdb

import io.bluetape4k.support.requireNotBlank
import java.io.Serializable

internal enum class CockroachDbCompatibilityStatus {
    Supported,
    Deferred,
    OutOfScope,
}

internal data class CockroachDbCompatibilityItem(
    val feature: String,
    val status: CockroachDbCompatibilityStatus,
    val evidence: String,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

internal object CockroachDbCompatibility {

    val items: List<CockroachDbCompatibilityItem> = listOf(
        CockroachDbCompatibilityItem(
            feature = PRIMARY_KEY_DDL,
            status = CockroachDbCompatibilityStatus.Supported,
            evidence = "Verified by SchemaUtils.create/drop against CockroachServer.",
        ),
        CockroachDbCompatibilityItem(
            feature = UNIQUE_AND_INDEX_DDL,
            status = CockroachDbCompatibilityStatus.Supported,
            evidence = "Verified by unique constraint failure and JDBC index metadata.",
        ),
        CockroachDbCompatibilityItem(
            feature = GENERATED_ID,
            status = CockroachDbCompatibilityStatus.Supported,
            evidence = "Verified by LongIdTable.insertAndGetId.",
        ),
        CockroachDbCompatibilityItem(
            feature = RETURNING,
            status = CockroachDbCompatibilityStatus.Supported,
            evidence = "Verified by raw INSERT ... RETURNING through PostgreSQL JDBC.",
        ),
        CockroachDbCompatibilityItem(
            feature = SCHEMA_METADATA,
            status = CockroachDbCompatibilityStatus.Supported,
            evidence = "Verified by JDBC table and index metadata lookup.",
        ),
        CockroachDbCompatibilityItem(
            feature = MIGRATION_DIFF,
            status = CockroachDbCompatibilityStatus.Deferred,
            evidence = "MigrationUtils still proposes generated-ID sequence ownership updates after create.",
        ),
        CockroachDbCompatibilityItem(
            feature = CREATE_DOMAIN,
            status = CockroachDbCompatibilityStatus.Deferred,
            evidence = "CockroachDB documents this PostgreSQL feature as unsupported.",
        ),
        CockroachDbCompatibilityItem(
            feature = RANGE_TYPES,
            status = CockroachDbCompatibilityStatus.Deferred,
            evidence = "CockroachDB documents PostgreSQL range types as unsupported.",
        ),
        CockroachDbCompatibilityItem(
            feature = CUSTOM_DIALECT,
            status = CockroachDbCompatibilityStatus.OutOfScope,
            evidence = "Exposed 1.4.0 on the 1.13.0 development line keeps the " +
                    "helper-only contract until an accepted path requires a dialect.",
        ),
    )

    fun requireFeature(feature: String): CockroachDbCompatibilityItem {
        feature.requireNotBlank("feature")
        return items.single { it.feature == feature }
    }

    const val PRIMARY_KEY_DDL: String = "Primary key DDL"
    const val UNIQUE_AND_INDEX_DDL: String = "Unique and index DDL"
    const val GENERATED_ID: String = "Generated ID"
    const val RETURNING: String = "RETURNING"
    const val SCHEMA_METADATA: String = "Schema metadata"
    const val MIGRATION_DIFF: String = "Migration diff"
    const val CREATE_DOMAIN: String = "CREATE DOMAIN"
    const val RANGE_TYPES: String = "PostgreSQL range types"
    const val CUSTOM_DIALECT: String = "Custom CockroachDB dialect"
}
