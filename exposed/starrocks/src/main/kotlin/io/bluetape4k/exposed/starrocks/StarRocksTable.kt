package io.bluetape4k.exposed.starrocks

import org.jetbrains.exposed.v1.core.Table

/**
 * 제한된 StarRocks OLAP DDL smoke test를 위한 Exposed table base입니다.
 *
 * StarRocks OLAP table이 일반 MySQL DDL로 허용하지 않는 generic primary-key 문법을 제거하고
 * 보수적인 StarRocks table option을 추가합니다. 더 넓은 StarRocks DDL 동작이 test로 입증될 때까지
 * 단순 local fixture에만 사용합니다.
 *
 * [options]와 [storageParameters]는 빈 목록만 허용합니다. MySQL engine/charset이나
 * PostgreSQL WITH 옵션을 OLAP 설정으로 해석하지 않으며, raw/custom 옵션도 SQL 생성 전에
 * [IllegalArgumentException]으로 거부합니다. 옵션 값의 `toSQL()`은 호출하지 않습니다.
 * 기본 `ENGINE=OLAP`과 `replication_num=1`은 유지합니다. 옵션 변경은 Exposed migration
 * diff로 추적되지 않으므로 별도의 검토된 수동 migration이 필요합니다.
 */
open class StarRocksTable(
    name: String = "",
): Table(name) {

    override fun createStatement(): List<String> {
        require(options.isEmpty()) { "StarRocksTable.options is unsupported; use the fixed OLAP table contract." }
        require(storageParameters.isEmpty()) {
            "StarRocksTable.storageParameters is unsupported; WITH parameters are not OLAP PROPERTIES."
        }
        return super.createStatement().map { sql -> sql.sanitizeForStarRocks() }
    }
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
