package io.bluetape4k.batch.jdbc

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.exposed.tests.withTables
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insert
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class BatchSchemaMigrationTest : AbstractBatchJdbcTest() {

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `preflight는 terminal NULL을 진단하고 populated legacy schema를 migration한다`(testDB: TestDB) {
        withTables(testDB, LegacyBatchJobExecutionTable) {
            val dialect = testDB.schemaDialect()
            val preflight = schemaScript(dialect, "V001__active_job_execution_key_preflight.sql")
            prepareLegacyRows(preflight)
            assertRerunnableMigration(dialect)
        }
    }

    private fun JdbcTransaction.prepareLegacyRows(preflight: String) {
        LegacyBatchJobExecutionTable.insert { row ->
            row[jobName] = "terminal-null"
            row[paramsHash] = null
            row[status] = "COMPLETED"
        }
        val nullDiagnostic = scriptStatements(preflight)
            .single { it.contains("null_params_hash_count") }
        val terminalNulls = exec(nullDiagnostic) { resultSet ->
            buildMap {
                while (resultSet.next()) {
                    put(resultSet.getString("status"), resultSet.getLong("null_params_hash_count"))
                }
            }
        }.orEmpty()
        terminalNulls shouldBeEqualTo mapOf("COMPLETED" to 1L)

        exec("UPDATE batch_job_execution SET params_hash = 'legacy-terminal' WHERE params_hash IS NULL")
        insertSharedLegacyRows()
        assertLegacyActiveHash(preflight, expectedCount = 1L)
        val v2Hash = "a".repeat(64)
        exec(
            "UPDATE batch_job_execution SET params_hash = '$v2Hash' WHERE status = 'STARTING'",
        )
        assertLegacyActiveHash(preflight, expectedCount = 0L)
    }

    private fun JdbcTransaction.insertSharedLegacyRows() {
        listOf("STARTING", "COMPLETED").forEach { batchStatus ->
            LegacyBatchJobExecutionTable.insert { row ->
                row[jobName] = "shared-job"
                row[paramsHash] = "shared-hash"
                row[status] = batchStatus
            }
        }
    }

    private fun JdbcTransaction.assertLegacyActiveHash(preflight: String, expectedCount: Long) {
        val diagnostic = scriptStatements(preflight)
            .single { it.contains("legacy_active_params_hash_count") }
        val count = exec(diagnostic) { resultSet ->
            resultSet.next()
            resultSet.getLong("legacy_active_params_hash_count")
        } ?: 0L
        count shouldBeEqualTo expectedCount
    }

    private fun JdbcTransaction.assertRerunnableMigration(dialect: String) {
        exec(
            "CREATE UNIQUE INDEX batch_job_exec_active_uidx " +
                "ON batch_job_execution (job_name, params_hash, status)",
        )
        val migration = schemaScript(dialect, "V001__active_job_execution_key_migrate.sql")
        repeat(2) {
            executeScript(migration)
            activeKeyMappings() shouldBeEqualTo listOf(
                "COMPLETED:null",
                "COMPLETED:null",
                "STARTING:ACTIVE",
            )
        }

        val postflight = schemaScript(dialect, "V001__active_job_execution_key_postflight.sql")
        assertLegacyActiveHash(postflight, expectedCount = 0L)
        LegacyBatchJobExecutionTable.insert { row ->
            row[jobName] = "shared-job"
            row[paramsHash] = "shared-hash"
            row[status] = "COMPLETED"
        }
    }

    private fun TestDB.schemaDialect(): String = when {
        this in TestDB.ALL_H2 -> "h2"
        this in TestDB.ALL_POSTGRES -> "postgresql"
        this in TestDB.ALL_MYSQL_MARIADB -> "mysql"
        else -> error("Unsupported batch schema migration test database: $this")
    }

    private fun schemaScript(dialect: String, fileName: String): String {
        val resourceName = "schema/$dialect/$fileName"
        val resource = requireNotNull(javaClass.classLoader.getResource(resourceName)) {
            "Batch schema artifact is missing from the runtime classpath: $resourceName"
        }
        return resource.readText()
    }

    private fun scriptStatements(script: String): List<String> =
        script.lineSequence()
            .filterNot { it.trimStart().startsWith("--") }
            .joinToString("\n")
            .split(';')
            .map(String::trim)
            .filter(String::isNotEmpty)

    private fun JdbcTransaction.executeScript(script: String) {
        scriptStatements(script)
            .filterNot { statement ->
                statement.equals("BEGIN", ignoreCase = true) ||
                    statement.equals("COMMIT", ignoreCase = true) ||
                    statement.equals("ROLLBACK", ignoreCase = true)
            }
            .forEach { statement -> exec(statement) }
    }

    private fun JdbcTransaction.activeKeyMappings(): List<String> =
        exec(
            "SELECT status, active_key FROM batch_job_execution ORDER BY status, id",
        ) { resultSet ->
            buildList {
                while (resultSet.next()) {
                    add("${resultSet.getString("status")}:${resultSet.getString("active_key")}")
                }
            }
        }.orEmpty()
}

private object LegacyBatchJobExecutionTable : LongIdTable("batch_job_execution") {
    val jobName = varchar("job_name", 100)
    val paramsHash = varchar("params_hash", 64).nullable()
    val status = varchar("status", 20)
}
