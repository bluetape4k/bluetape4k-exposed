package io.bluetape4k.exposed.tests.migration

import io.bluetape4k.exposed.tests.AbstractExposedTest
import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.exposed.tests.withDb
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
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.exists
import org.jetbrains.exposed.v1.migration.jdbc.MigrationUtils
import java.util.Locale

@Tag("migration-drift")
@OptIn(ExperimentalDatabaseMigrationApi::class)
class JdbcMigrationDriftTest: AbstractExposedTest() {

    @ParameterizedTest(name = "JDBC additive drift converges on {0}")
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `additive description drift is detected applied and converged`(testDB: TestDB) {
        preservingFailure(
            block = {
                withDb(testDB) {
                    assertFalse(JdbcMigrationBaseline.exists())
                    SchemaUtils.create(JdbcMigrationBaseline)

                    val statements = MigrationUtils.statementsRequiredForDatabaseMigration(
                        JdbcMigrationEvolved,
                        withLogs = false,
                    )
                    assertEquals(1, statements.size)
                    val validated = validateAdditiveStatement(
                        statement = statements.single(),
                        expectedTable = JdbcMigrationEvolved.tableName,
                        expectedColumn = JdbcMigrationEvolved.description.name,
                    )

                    this.exec(validated)

                    assertTrue(
                        MigrationUtils.statementsRequiredForDatabaseMigration(
                            JdbcMigrationEvolved,
                            withLogs = false,
                        ).isEmpty(),
                    )
                }
            },
            cleanup = {
                withDb(testDB) {
                    if (JdbcMigrationBaseline.exists()) {
                        SchemaUtils.drop(JdbcMigrationBaseline)
                    }
                    assertFalse(JdbcMigrationBaseline.exists())
                }
            },
        )
    }

    @Test
    fun `H2 varchar to text drift is characterized without execution`() {
        preservingFailure(
            block = {
                withDb(TestDB.H2) {
                    assertFalse(JdbcTypeChangeBaseline.exists())
                    SchemaUtils.create(JdbcTypeChangeBaseline)

                    val statements = MigrationUtils.statementsRequiredForDatabaseMigration(
                        JdbcTypeChangeEvolved,
                        withLogs = false,
                    )

                    assertTrue(statements.isNotEmpty())
                    assertTrue(statements.any { "ALTER" in it.uppercase(Locale.ROOT) })
                }
            },
            cleanup = {
                withDb(TestDB.H2) {
                    if (JdbcTypeChangeBaseline.exists()) {
                        SchemaUtils.drop(JdbcTypeChangeBaseline)
                    }
                    assertFalse(JdbcTypeChangeBaseline.exists())
                }
            },
        )
    }

    @Nested
    inner class HelperContract {

        @Test
        fun `accepts only the complete additive description statement`() {
            val statements = listOf(
                "ALTER TABLE jdbc_migration_drift ADD description VARCHAR(255) NULL",
                "alter table \"jdbc_migration_drift\" add column \"description\" varchar(255) null",
                "ALTER  TABLE `jdbc_migration_drift` ADD  `description` VARCHAR(255) NULL",
            )

            statements.forEach { statement ->
                assertEquals(
                    statement,
                    validateAdditiveStatement(
                        statement = statement,
                        expectedTable = "jdbc_migration_drift",
                        expectedColumn = "description",
                    ),
                )
            }
        }

        @Test
        fun `rejects compound destructive or constraint-bearing statements`() {
            val rejected = listOf(
                "ALTER TABLE jdbc_migration_drift ADD description VARCHAR(255) NULL;",
                "ALTER TABLE jdbc_migration_drift ADD description VARCHAR(255) NULL; DROP TABLE accounts",
                "ALTER TABLE jdbc_migration_drift ADD description VARCHAR(255) NULL -- comment",
                "ALTER TABLE jdbc_migration_drift ADD description VARCHAR(255) NULL, ADD owner VARCHAR(64) NULL",
                "ALTER TABLE another_table ADD description VARCHAR(255) NULL",
                "ALTER TABLE jdbc_migration_drift ADD another_column VARCHAR(255) NULL",
                "ALTER TABLE jdbc_migration_drift DROP description",
                "ALTER TABLE jdbc_migration_drift ADD description VARCHAR(255) DEFAULT 'x'",
                "ALTER TABLE jdbc_migration_drift ADD description VARCHAR(255) NOT NULL",
                "ALTER TABLE jdbc_migration_drift ADD description VARCHAR(255) NULL GENERATED ALWAYS AS ('x')",
                "ALTER TABLE jdbc_migration_drift ADD description VARCHAR(255) NULL REFERENCES owners(id)",
                "ALTER TABLE jdbc_migration_drift ADD description VARCHAR(255) NULL CONSTRAINT description_check CHECK (1 = 1)",
                "ALTER TABLE jdbc_migration_drift ADD description VARCHAR(255) NULL UNIQUE",
                "ALTER TABLE jdbc_migration_drift ADD description VARCHAR(255) NULL PRIMARY KEY",
                "ALTER TABLE jdbc_migration_drift ADD description VARCHAR(255) NULL COLLATE utf8mb4_bin",
            )

            rejected.forEach { statement ->
                assertThrows<IllegalArgumentException>(statement) {
                    validateAdditiveStatement(
                        statement = statement,
                        expectedTable = "jdbc_migration_drift",
                        expectedColumn = "description",
                    )
                }
            }
        }

        @Test
        fun `preserves a primary failure when cleanup succeeds`() {
            val primary = IllegalStateException("primary")

            val thrown = assertThrows<IllegalStateException> {
                preservingFailure(
                    block = { throw primary },
                    cleanup = {},
                )
            }

            assertSame(primary, thrown)
            assertTrue(thrown.suppressed.isEmpty())
        }

        @Test
        fun `throws a cleanup-only failure`() {
            val cleanup = IllegalArgumentException("cleanup")

            val thrown = assertThrows<IllegalArgumentException> {
                preservingFailure(
                    block = {},
                    cleanup = { throw cleanup },
                )
            }

            assertSame(cleanup, thrown)
        }

        @Test
        fun `suppresses cleanup failure under the primary failure`() {
            val primary = IllegalStateException("primary")
            val cleanup = IllegalArgumentException("cleanup")

            val thrown = assertThrows<IllegalStateException> {
                preservingFailure(
                    block = { throw primary },
                    cleanup = { throw cleanup },
                )
            }

            assertSame(primary, thrown)
            assertEquals(listOf(cleanup), thrown.suppressed.toList())
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
            .replace(IDENTIFIER_QUOTES, "")
            .replace(WHITESPACE, " ")
            .trim()
            .uppercase(Locale.ROOT)
        val expected = Regex(
            "^ALTER TABLE ${Regex.escape(expectedTable.uppercase(Locale.ROOT))} " +
                    "ADD(?: COLUMN)? ${Regex.escape(expectedColumn.uppercase(Locale.ROOT))} " +
                    "VARCHAR\\s*\\(\\s*255\\s*\\) NULL$",
        )

        require(expected.matches(normalized)) {
            "Only the reviewed additive migration statement is allowed"
        }
        return statement
    }

    private inline fun preservingFailure(
        block: () -> Unit,
        cleanup: () -> Unit,
    ) {
        var primaryFailure: Throwable? = null
        try {
            block()
        } catch (failure: Throwable) {
            primaryFailure = failure
        }

        try {
            cleanup()
        } catch (cleanupFailure: Throwable) {
            primaryFailure?.addSuppressed(cleanupFailure) ?: throw cleanupFailure
        }

        primaryFailure?.let { throw it }
    }

    companion object {
        private val IDENTIFIER_QUOTES = Regex("[\"`]")
        private val WHITESPACE = Regex("\\s+")

        private object JdbcMigrationBaseline: Table("jdbc_migration_drift") {
            val id = integer("id")
            val name = varchar("name", 64)

            override val primaryKey = PrimaryKey(id)
        }

        private object JdbcMigrationEvolved: Table("jdbc_migration_drift") {
            val id = integer("id")
            val name = varchar("name", 64)
            val description = varchar("description", 255).nullable()

            override val primaryKey = PrimaryKey(id)
        }

        private object JdbcTypeChangeBaseline: Table("jdbc_migration_type_drift") {
            val id = integer("id")
            val value = varchar("value", 64)

            override val primaryKey = PrimaryKey(id)
        }

        private object JdbcTypeChangeEvolved: Table("jdbc_migration_type_drift") {
            val id = integer("id")
            val value = text("value")

            override val primaryKey = PrimaryKey(id)
        }
    }
}
