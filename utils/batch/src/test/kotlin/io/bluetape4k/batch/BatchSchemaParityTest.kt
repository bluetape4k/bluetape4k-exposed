package io.bluetape4k.batch

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeEqualTo
import io.bluetape4k.batch.jdbc.tables.BatchJobExecutionTable as JdbcJobExecutionTable
import io.bluetape4k.batch.jdbc.tables.BatchStepExecutionTable as JdbcStepExecutionTable
import io.bluetape4k.batch.r2dbc.tables.BatchJobExecutionTable as R2dbcJobExecutionTable
import io.bluetape4k.batch.r2dbc.tables.BatchStepExecutionTable as R2dbcStepExecutionTable
import org.jetbrains.exposed.v1.core.AutoIncColumnType
import org.jetbrains.exposed.v1.core.DecimalColumnType
import org.jetbrains.exposed.v1.core.EntityIDColumnType
import org.jetbrains.exposed.v1.core.EnumerationNameColumnType
import org.jetbrains.exposed.v1.core.IColumnType
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.VarCharColumnType
import org.jetbrains.exposed.v1.core.Table
import org.junit.jupiter.api.Test

/** JDBC와 R2DBC adapter가 같은 영속 schema 계약을 소유하는지 확인한다. */
class BatchSchemaParityTest {

    /**
     * JDBC/R2DBC ResultRow mapper가 공유하는 checkpoint 저장 계약이다.
     * 실제 codec 변경 시 버전을 올려 schema drift를 놓치지 않도록 한다.
     */
    private companion object {
        const val CHECKPOINT_STORAGE_CONTRACT =
            "text:CheckpointJson.write/read:v1:typed-envelope(className,payload):allowlisted-registry"
        const val PARAMS_HASH_STORAGE_CONTRACT =
            "varchar(64):Map.toParamsHash:v1:SHA-256(sorted-key=value,UTF-8,lowercase-hex,empty-map-empty)"
    }

    @Test
    fun `JDBC와 R2DBC batch table의 영속 schema descriptor가 일치`() {
        JdbcJobExecutionTable.schemaSignature() shouldBeEqualTo R2dbcJobExecutionTable.schemaSignature()
        JdbcStepExecutionTable.schemaSignature() shouldBeEqualTo R2dbcStepExecutionTable.schemaSignature()
    }

    @Test
    fun `schema descriptor는 type default index drift를 구분한다`() {
        val descriptor = JdbcJobExecutionTable.schemaSignature()
        val changed = descriptor.copy(
            columns = descriptor.columns.map { column ->
                if (column.name == "job_name") {
                    column.copy(typeDescriptor = "varchar(length=101,collate=null)")
                } else {
                    column
                }
            },
        )

        changed shouldNotBeEqualTo descriptor
    }

    @Test
    fun `schema descriptor는 field별 default nullable storage index foreign-key drift를 구분한다`() {
        val descriptor = JdbcJobExecutionTable.schemaSignature()
        val mutations = listOf(
            descriptor.copy(
                columns = descriptor.columns.map { column ->
                    if (column.name == "version") column.copy(clientDefault = "1", databaseDefault = "1") else column
                },
            ),
            descriptor.copy(
                columns = descriptor.columns.map { column ->
                    if (column.name == "params") column.copy(nullable = !column.nullable) else column
                },
            ),
            descriptor.copy(
                columns = descriptor.columns.map { column ->
                    if (column.name == "params") {
                        column.copy(storageContract = "$CHECKPOINT_STORAGE_CONTRACT:v2")
                    } else {
                        column
                    }
                },
            ),
            descriptor.copy(indexes = descriptor.indexes.drop(1)),
            descriptor.copy(
                columns = descriptor.columns.map { column ->
                    if (column.name == "id") column.copy(foreignKey = listOf("id->other_table.id")) else column
                },
            ),
        )

        mutations.forEach { it shouldNotBeEqualTo descriptor }
    }

    @Test
    fun `Decimal column descriptor는 precision과 scale drift를 구분한다`() {
        DecimalColumnType(10, 2).schemaTypeDescriptor() shouldNotBeEqualTo DecimalColumnType(10, 3)
            .schemaTypeDescriptor()
        DecimalColumnType(10, 2).schemaTypeDescriptor() shouldNotBeEqualTo DecimalColumnType(11, 2)
            .schemaTypeDescriptor()
    }

    @Test
    fun `storage codec와 hash contract drift를 구분한다`() {
        val descriptor = JdbcJobExecutionTable.schemaSignature()
        descriptor.columns.single { it.name == "params" }.storageContract shouldBeEqualTo CHECKPOINT_STORAGE_CONTRACT
        descriptor.columns.single { it.name == "params_hash" }.storageContract shouldBeEqualTo
            PARAMS_HASH_STORAGE_CONTRACT

        val checkpointContractChanged = descriptor.copy(
            columns = descriptor.columns.map { column ->
                if (column.name == "params") {
                    column.copy(storageContract = "$CHECKPOINT_STORAGE_CONTRACT:v2")
                } else {
                    column
                }
            },
        )
        val hashContractChanged = descriptor.copy(
            columns = descriptor.columns.map { column ->
                if (column.name == "params_hash") {
                    column.copy(storageContract = "$PARAMS_HASH_STORAGE_CONTRACT:v2")
                } else {
                    column
                }
            },
        )

        checkpointContractChanged shouldNotBeEqualTo descriptor
        hashContractChanged shouldNotBeEqualTo descriptor
    }

    private data class ColumnSignature(
        val name: String,
        val typeDescriptor: String,
        val nullable: Boolean,
        val hasClientDefault: Boolean,
        val hasDatabaseDefault: Boolean,
        val clientDefault: String?,
        val databaseDefault: String?,
        val storageContract: String?,
        val databaseGenerated: Boolean,
        val primaryKey: Boolean,
        val foreignKey: List<String>,
    )

    private data class IndexSignature(
        val columns: List<String>,
        val unique: Boolean,
        val customName: String?,
        val indexType: String?,
        val filterExpression: String?,
        val functions: List<String>,
    )

    private data class ForeignKeySignature(
        val customName: String?,
        val references: List<String>,
        val updateRule: String?,
        val deleteRule: String?,
    )

    private data class SchemaSignature(
        val tableName: String,
        val columns: List<ColumnSignature>,
        val primaryKey: List<String>,
        val indexes: List<IndexSignature>,
        val foreignKeys: List<ForeignKeySignature>,
    )

    private fun Table.schemaSignature(): SchemaSignature = SchemaSignature(
        tableName = tableName,
        columns = columns.map { column ->
            ColumnSignature(
                name = column.name,
                typeDescriptor = column.columnType.schemaTypeDescriptor(),
                nullable = column.columnType.nullable,
                hasClientDefault = column.defaultValueFun != null,
                hasDatabaseDefault = column.defaultValueInDb() != null,
                clientDefault = column.defaultValueFun?.invoke()?.toString(),
                databaseDefault = column.defaultValueInDb()?.toString(),
                storageContract = column.storageContract(),
                databaseGenerated = column.isDatabaseGenerated(),
                primaryKey = primaryKey?.columns?.contains(column) == true,
                foreignKey = column.foreignKey?.references.orEmpty().entries
                    .map { (from, to) -> "${from.name}->${to.table.tableName}.${to.name}" }
                    .sorted(),
            )
        },
        primaryKey = primaryKey?.columns?.map { it.name }.orEmpty(),
        indexes = indices.map { index ->
            IndexSignature(
                columns = index.columns.map { it.name },
                unique = index.unique,
                customName = index.customName,
                indexType = index.indexType,
                filterExpression = index.filterCondition?.toString(),
                functions = index.functions.orEmpty().map { it.toString() },
            )
        }.sortedWith(compareBy({ it.customName }, { it.columns.joinToString(",") })),
        foreignKeys = foreignKeys.map { foreignKey ->
            ForeignKeySignature(
                customName = foreignKey.customFkName,
                references = foreignKey.references.entries
                    .map { (from, to) -> "${from.name}->${to.table.tableName}.${to.name}" }
                    .sorted(),
                updateRule = foreignKey.updateRule?.name,
                deleteRule = foreignKey.deleteRule?.name,
            )
        }.sortedBy { it.customName },
    )

    private fun IColumnType<*>.schemaTypeDescriptor(): String = when (this) {
        is AutoIncColumnType<*> -> "auto-inc(delegate=${delegate.schemaTypeDescriptor()})"
        is EntityIDColumnType<*> -> "entity-id(delegate=${idColumn.columnType.schemaTypeDescriptor()})"
        is DecimalColumnType -> "decimal(precision=$precision,scale=$scale)"
        is VarCharColumnType -> "varchar(length=$colLength,collate=$collate)"
        is EnumerationNameColumnType<*> -> "enum-name(class=${klass.qualifiedName},length=$colLength)"
        is TextColumnType -> "text(collate=$collate,eagerLoading=$eagerLoading)"
        else -> this::class.qualifiedName.orEmpty()
    }

    private fun org.jetbrains.exposed.v1.core.Column<*>.storageContract(): String? = when (name) {
        "status" -> "enum-name-status:v1:BatchStatus,length=20"
        "params_hash" -> PARAMS_HASH_STORAGE_CONTRACT
        "params", "checkpoint" -> CHECKPOINT_STORAGE_CONTRACT
        else -> null
    }
}
