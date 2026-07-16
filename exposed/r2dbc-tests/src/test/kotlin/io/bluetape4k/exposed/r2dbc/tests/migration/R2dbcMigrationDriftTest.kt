package io.bluetape4k.exposed.r2dbc.tests.migration

import io.bluetape4k.exposed.r2dbc.tests.AbstractExposedR2dbcTest
import io.bluetape4k.exposed.r2dbc.tests.TestDB
import io.bluetape4k.exposed.r2dbc.tests.withDb
import io.bluetape4k.junit5.coroutines.runSuspendIO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.yield
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.jetbrains.exposed.v1.core.ExperimentalDatabaseMigrationApi
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.migration.r2dbc.MigrationUtils
import org.jetbrains.exposed.v1.r2dbc.SchemaUtils
import org.jetbrains.exposed.v1.r2dbc.exists
import java.util.Locale
import java.util.concurrent.CancellationException

@Tag("migration-drift")
@OptIn(ExperimentalDatabaseMigrationApi::class)
class R2dbcMigrationDriftTest: AbstractExposedR2dbcTest() {

    @ParameterizedTest(name = "R2DBC additive drift converges on {0}")
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `additive description drift is detected applied and converged`(testDB: TestDB) = runSuspendIO {
        preservingFailure(
            block = {
                withDb(testDB) {
                    assertFalse(R2dbcMigrationBaseline.exists())
                    SchemaUtils.create(R2dbcMigrationBaseline)

                    val statements = MigrationUtils.statementsRequiredForDatabaseMigration(
                        R2dbcMigrationEvolved,
                        withLogs = false,
                    )
                    assertEquals(1, statements.size)
                    val validated = validateAdditiveStatement(
                        statement = statements.single(),
                        expectedTable = R2dbcMigrationEvolved.tableName,
                        expectedColumn = R2dbcMigrationEvolved.description.name,
                    )

                    this.exec(validated)

                    assertTrue(
                        MigrationUtils.statementsRequiredForDatabaseMigration(
                            R2dbcMigrationEvolved,
                            withLogs = false,
                        ).isEmpty(),
                    )
                }
            },
            cleanup = {
                withDb(testDB) {
                    if (R2dbcMigrationBaseline.exists()) {
                        SchemaUtils.drop(R2dbcMigrationBaseline)
                    }
                    assertFalse(R2dbcMigrationBaseline.exists())
                }
            },
        )
    }

    @Test
    fun `H2 varchar to text drift is characterized without execution`() = runSuspendIO {
        preservingFailure(
            block = {
                withDb(TestDB.H2) {
                    assertFalse(R2dbcTypeChangeBaseline.exists())
                    SchemaUtils.create(R2dbcTypeChangeBaseline)

                    val statements = MigrationUtils.statementsRequiredForDatabaseMigration(
                        R2dbcTypeChangeEvolved,
                        withLogs = false,
                    )

                    assertTrue(statements.isNotEmpty())
                    assertTrue(statements.any { isExpectedH2TypeChange(it, R2dbcTypeChangeEvolved.tableName, "value") })
                }
            },
            cleanup = {
                withDb(TestDB.H2) {
                    if (R2dbcTypeChangeBaseline.exists()) {
                        SchemaUtils.drop(R2dbcTypeChangeBaseline)
                    }
                    assertFalse(R2dbcTypeChangeBaseline.exists())
                }
            },
        )
    }

    @Nested
    inner class HelperContract {

        @Test
        fun `accepts only the complete additive description statement`() {
            val statements = listOf(
                "ALTER TABLE r2dbc_migration_drift ADD description VARCHAR(255) NULL",
                "alter table \"r2dbc_migration_drift\" add column \"description\" varchar(255) null",
                "ALTER  TABLE `r2dbc_migration_drift` ADD  `description` VARCHAR(255) NULL",
            )

            statements.forEach { statement ->
                assertEquals(
                    statement,
                    validateAdditiveStatement(
                        statement = statement,
                        expectedTable = "r2dbc_migration_drift",
                        expectedColumn = "description",
                    ),
                )
            }
        }

        @Test
        fun `rejects compound destructive or constraint-bearing statements`() {
            val rejected = listOf(
                "ALTER TABLE r2dbc_migration_drift ADD description VARCHAR(255) NULL;",
                "ALTER TABLE r2dbc_migration_drift ADD description VARCHAR(255) NULL; DROP TABLE accounts",
                "ALTER TABLE r2dbc_migration_drift ADD description VARCHAR(255) NULL -- comment",
                "ALTER TABLE r2dbc_migration_drift ADD description VARCHAR(255) NULL, ADD owner VARCHAR(64) NULL",
                "ALTER TABLE another_table ADD description VARCHAR(255) NULL",
                "ALTER TABLE r2dbc_migration_drift ADD another_column VARCHAR(255) NULL",
                "ALTER TABLE r2dbc_migration_drift DROP description",
                "ALTER TABLE r2dbc_migration_drift ADD description VARCHAR(255) DEFAULT 'x'",
                "ALTER TABLE r2dbc_migration_drift ADD description VARCHAR(255) NOT NULL",
                "ALTER TABLE r2dbc_migration_drift ADD description VARCHAR(255) NULL GENERATED ALWAYS AS ('x')",
                "ALTER TABLE r2dbc_migration_drift ADD description VARCHAR(255) NULL REFERENCES owners(id)",
                "ALTER TABLE r2dbc_migration_drift ADD description VARCHAR(255) NULL CONSTRAINT description_check CHECK (1 = 1)",
                "ALTER TABLE r2dbc_migration_drift ADD description VARCHAR(255) NULL UNIQUE",
                "ALTER TABLE r2dbc_migration_drift ADD description VARCHAR(255) NULL PRIMARY KEY",
                "ALTER TABLE r2dbc_migration_drift ADD description VARCHAR(255) NULL COLLATE utf8mb4_bin",
                "ALTER TABLE \"r2dbc_migration_drift \" ADD \"description \" VARCHAR(255) NULL",
                "ALTER TABLE \" r2dbc_migration_drift\" ADD \" description\" VARCHAR(255) NULL",
                "ALTER TABLE \"R2DBC_MIGRATION_DRIFT\" ADD \"description\" VARCHAR(255) NULL",
                "ALTER TABLE \"r2dbc_migration_drift\" ADD \"DESCRIPTION\" VARCHAR(255) NULL",
            )

            rejected.forEach { statement ->
                assertThrows<IllegalArgumentException>(statement) {
                    validateAdditiveStatement(
                        statement = statement,
                        expectedTable = "r2dbc_migration_drift",
                        expectedColumn = "description",
                    )
                }
            }
        }

        @Test
        fun `preserves a primary failure when cleanup succeeds`() = runSuspendIO {
            val primary = IllegalStateException("primary")

            val thrown = runCatching {
                preservingFailure(
                    block = { throw primary },
                    cleanup = {},
                )
            }.exceptionOrNull()

            assertSame(primary, thrown)
            assertTrue(checkNotNull(thrown).suppressed.isEmpty())
        }

        @Test
        fun `throws a cleanup-only failure`() = runSuspendIO {
            val cleanup = IllegalArgumentException("cleanup")

            val thrown = runCatching {
                preservingFailure(
                    block = {},
                    cleanup = { throw cleanup },
                )
            }.exceptionOrNull()

            assertSame(cleanup, thrown)
        }

        @Test
        fun `suppresses cleanup failure under the primary failure`() = runSuspendIO {
            val primary = IllegalStateException("primary")
            val cleanup = IllegalArgumentException("cleanup")

            val thrown = runCatching {
                preservingFailure(
                    block = { throw primary },
                    cleanup = { throw cleanup },
                )
            }.exceptionOrNull()

            assertSame(primary, thrown)
            assertEquals(listOf(cleanup), checkNotNull(thrown).suppressed.toList())
        }

        @Test
        fun `runs cleanup after real coroutine cancellation`() = runSuspendIO {
            val primary = CancellationException("cancelled")
            var cleaned = false

            val thrown = coroutineScope {
                val cancelled = async {
                    preservingFailure(
                        block = {
                            currentCoroutineContext().cancel(primary)
                            yield()
                        },
                        cleanup = {
                            yield()
                            cleaned = true
                        },
                    )
                }
                runCatching { cancelled.await() }.exceptionOrNull()
            }

            assertTrue(thrown is CancellationException)
            assertEquals(primary.message, thrown?.message)
            assertTrue(cleaned)
        }
    }

    private fun validateAdditiveStatement(
        statement: String,
        expectedTable: String,
        expectedColumn: String,
    ): String {
        require(statement.isNotBlank()) { "Migration statement must not be blank" }
        require(';' !in statement) { "Multiple or terminated statements are not allowed" }
        require(',' !in statement) { "Compound column operations are not allowed" }
        require("--" !in statement && "/*" !in statement && "*/" !in statement && '#' !in statement) {
            "SQL comments are not allowed"
        }

        val normalized = statement
            .replace(WHITESPACE, " ")
            .trim()
        val match = ADDITIVE_STATEMENT.matchEntire(normalized)

        require(
            match != null &&
                    matchesExpectedIdentifier(match.groupValues[1], expectedTable) &&
                    matchesExpectedIdentifier(match.groupValues[2], expectedColumn),
        ) {
            "Only the reviewed additive migration statement is allowed"
        }
        return statement
    }

    private fun matchesExpectedIdentifier(token: String, expected: String): Boolean {
        return when {
            token.startsWith('"') && token.endsWith('"') -> token.substring(1, token.lastIndex) == expected
            token.startsWith('`') && token.endsWith('`') -> token.substring(1, token.lastIndex) == expected
            else -> token.equals(expected, ignoreCase = true)
        }
    }

    private fun isExpectedH2TypeChange(statement: String, expectedTable: String, expectedColumn: String): Boolean {
        val normalized = statement
            .replace(Regex("[\"`]"), "")
            .replace(WHITESPACE, " ")
            .trim()
            .uppercase(Locale.ROOT)
        return normalized.startsWith("ALTER TABLE ${expectedTable.uppercase(Locale.ROOT)} ") &&
                Regex("\\b${Regex.escape(expectedColumn.uppercase(Locale.ROOT))}\\b").containsMatchIn(normalized) &&
                Regex("\\b(TEXT|CLOB)\\b").containsMatchIn(normalized)
    }

    private suspend fun preservingFailure(
        block: suspend () -> Unit,
        cleanup: suspend () -> Unit,
    ) {
        var primaryFailure: Throwable? = null
        try {
            block()
        } catch (failure: Throwable) {
            primaryFailure = failure
        }

        try {
            if (primaryFailure is CancellationException || !currentCoroutineContext().isActive) {
                withContext(NonCancellable) {
                    cleanup()
                }
            } else {
                cleanup()
            }
        } catch (cleanupFailure: Throwable) {
            primaryFailure?.addSuppressed(cleanupFailure) ?: throw cleanupFailure
        }

        primaryFailure?.let { throw it }
    }

    companion object {
        private const val IDENTIFIER_TOKEN = "(?:\"[^\"]+\"|`[^`]+`|[A-Za-z_][A-Za-z0-9_]*)"
        private val ADDITIVE_STATEMENT = Regex(
            "^ALTER TABLE ($IDENTIFIER_TOKEN) ADD(?: COLUMN)? ($IDENTIFIER_TOKEN) " +
                    "VARCHAR\\s*\\(\\s*255\\s*\\) NULL$",
            RegexOption.IGNORE_CASE,
        )
        private val WHITESPACE = Regex("\\s+")

        private object R2dbcMigrationBaseline: Table("r2dbc_migration_drift") {
            val id = integer("id")
            val name = varchar("name", 64)

            override val primaryKey = PrimaryKey(id)
        }

        private object R2dbcMigrationEvolved: Table("r2dbc_migration_drift") {
            val id = integer("id")
            val name = varchar("name", 64)
            val description = varchar("description", 255).nullable()

            override val primaryKey = PrimaryKey(id)
        }

        private object R2dbcTypeChangeBaseline: Table("r2dbc_migration_type_drift") {
            val id = integer("id")
            val value = varchar("value", 64)

            override val primaryKey = PrimaryKey(id)
        }

        private object R2dbcTypeChangeEvolved: Table("r2dbc_migration_type_drift") {
            val id = integer("id")
            val value = text("value")

            override val primaryKey = PrimaryKey(id)
        }
    }
}
