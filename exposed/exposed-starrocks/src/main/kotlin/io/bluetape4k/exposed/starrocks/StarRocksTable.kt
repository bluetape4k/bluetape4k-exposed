package io.bluetape4k.exposed.starrocks

import org.jetbrains.exposed.v1.core.Table

/**
 * Exposed table base for narrow StarRocks OLAP DDL smoke tests.
 *
 * The class removes generic primary-key syntax that StarRocks OLAP tables do
 * not accept as plain MySQL DDL and appends conservative StarRocks table
 * options. Use it only for simple local fixtures until broader StarRocks DDL
 * behavior is proven by tests.
 */
open class StarRocksTable(
    name: String = "",
): Table(name) {

    override fun createStatement(): List<String> =
        super.createStatement().map { sql -> sql.sanitizeForStarRocks() }
}

private val STARROCKS_CONSTRAINT_PK_REGEX =
    Regex(",\\s*CONSTRAINT\\s+\\S+\\s+PRIMARY\\s+KEY\\s*\\([^)]*\\)", RegexOption.IGNORE_CASE)
private val STARROCKS_INLINE_PK_REGEX = Regex("\\s+PRIMARY\\s+KEY", RegexOption.IGNORE_CASE)
private val STARROCKS_STANDALONE_NULL_REGEX = Regex("\\s+NULL\\b")

private fun String.sanitizeForStarRocks(): String =
    this
        .replace(STARROCKS_CONSTRAINT_PK_REGEX, "")
        .replace(STARROCKS_INLINE_PK_REGEX, "")
        .replace("NOT NULL", "__STARROCKS_NOT_NULL__")
        .replace(STARROCKS_STANDALONE_NULL_REGEX, "")
        .replace("__STARROCKS_NOT_NULL__", "NOT NULL")
        .let { sql ->
            if (sql.contains("ENGINE=", ignoreCase = true)) sql
            else "$sql ENGINE=OLAP PROPERTIES (\"replication_num\" = \"1\")"
        }
