package io.bluetape4k.exposed.clickhouse.engine

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.exposed.clickhouse.functions.toYYYYMM
import io.bluetape4k.exposed.clickhouse.sanitizeForClickHouse
import org.jetbrains.exposed.v1.core.Table
import org.junit.jupiter.api.Test

class MergeTreeDslTest {

    private object EngineDslEvents: Table("engine_dsl_events") {
        val eventId = long("event_id")
        val eventName = varchar("event_name", 255)
        val eventMonth = integer("event_month")
        val version = long("version")
        val amount = long("amount")
    }

    @Test
    fun `Memory engine toClause`() {
        Memory.toClause() shouldBeEqualTo "ENGINE = Memory()"
    }

    @Test
    fun `Log engine toClause`() {
        Log.toClause() shouldBeEqualTo "ENGINE = Log()"
    }

    @Test
    fun `TinyLog engine toClause`() {
        TinyLog.toClause() shouldBeEqualTo "ENGINE = TinyLog()"
    }

    @Test
    fun `MergeTree basic with orderBy`() {
        val engine = mergeTree { unsafeRawOrderBy("a", "b") }
        val clause = engine.toClause()
        clause shouldContain "ENGINE = MergeTree()"
        clause shouldContain "ORDER BY (a, b)"
    }

    @Test
    fun `MergeTree with partitionBy`() {
        val engine = mergeTree {
            unsafeRawOrderBy("a", "b")
            unsafeRawPartitionBy("toYYYYMM(c)")
        }
        val clause = engine.toClause()
        clause shouldContain "PARTITION BY toYYYYMM(c)"
    }

    @Test
    fun `MergeTree with settings`() {
        val engine = mergeTree {
            orderBy(EngineDslEvents.eventId)
            setting("index_granularity", 8192)
        }
        val clause = engine.toClause()
        clause shouldContain "SETTINGS index_granularity = 8192"
    }

    @Test
    fun `MergeTree renders typed expressions and settings`() {
        val engine = mergeTree {
            orderBy(EngineDslEvents.eventId, EngineDslEvents.eventName)
            partitionBy(EngineDslEvents.eventMonth.toYYYYMM())
            primaryKey(EngineDslEvents.eventId)
            sampleBy(EngineDslEvents.eventId)
            setting("index_granularity", 8192)
            setting("storage_policy", "hot's policy")
        }

        val clause = engine.toClause()

        clause shouldContain "ORDER BY (event_id, event_name)"
        clause shouldContain "PARTITION BY toYYYYMM(event_month)"
        clause shouldContain "PRIMARY KEY (event_id)"
        clause shouldContain "SAMPLE BY event_id"
        clause shouldContain "index_granularity = 8192"
        clause shouldContain "storage_policy = 'hot''s policy'"
    }

    @Test
    fun `MergeTree renders raw primary sample and all setting overloads`() {
        val engine = mergeTree {
            unsafeRawOrderBy("event_id")
            unsafeRawPrimaryKey("event_id")
            unsafeRawSampleBy("event_id")
            applyAllSettingOverloads()
        }

        val clause = engine.toClause()

        clause shouldContain "ORDER BY (event_id)"
        clause shouldContain "PRIMARY KEY (event_id)"
        clause shouldContain "SAMPLE BY event_id"
        assertAllSettingsRendered(clause)
    }

    @Test
    fun `safe settings reject unknown identifiers`() {
        assertFailsWith<IllegalArgumentException> {
            mergeTree {
                orderBy(EngineDslEvents.eventId)
                setting("unknown_merge_tree_setting", 1)
            }
        }

        val engine = mergeTree {
            orderBy(EngineDslEvents.eventId)
            unsafeRawSetting("unknown_merge_tree_setting", "1")
        }

        engine.toClause() shouldContain "unknown_merge_tree_setting = 1"
    }

    @Test
    fun `unsafe raw fragments reject statement and clause boundary injection`() {
        val unsafeFragments = listOf(
            "event_id; DROP TABLE events",
            "event_id -- comment",
            "event_id /* comment */",
            "event_id\nSETTINGS index_granularity = 1",
            "event_id'quoted",
            "event_id) SETTINGS index_granularity = 1",
            "toYYYYMM(event_month) PARTITION BY event_id",
        )

        unsafeFragments.forEach { fragment ->
            assertFailsWith<IllegalArgumentException> {
                mergeTree {
                    unsafeRawOrderBy(fragment)
                }
            }
            assertFailsWith<IllegalArgumentException> {
                mergeTree {
                    orderBy(EngineDslEvents.eventId)
                    unsafeRawPartitionBy(fragment)
                }
            }
        }
    }

    @Test
    fun `unsafe raw fragments reject blank values in all raw merge tree clauses`() {
        assertFailsWith<IllegalArgumentException> {
            mergeTree { unsafeRawOrderBy(" ") }
        }
        assertFailsWith<IllegalArgumentException> {
            mergeTree {
                unsafeRawOrderBy("event_id")
                unsafeRawPrimaryKey("")
            }
        }
        assertFailsWith<IllegalArgumentException> {
            mergeTree {
                unsafeRawOrderBy("event_id")
                unsafeRawSampleBy("\t")
            }
        }
    }

    @Test
    fun `unsafe raw settings reject comments delimiters quotes and clause boundaries`() {
        val unsafeSettingValues = listOf(
            "8192; DROP TABLE events",
            "8192 -- comment",
            "8192 /* comment */",
            "8192\nPARTITION BY event_id",
            "8192'quoted",
            "8192 SETTINGS allow_nullable_key = 1",
        )

        unsafeSettingValues.forEach { value ->
            assertFailsWith<IllegalArgumentException> {
                mergeTree {
                    orderBy(EngineDslEvents.eventId)
                    unsafeRawSetting("index_granularity", value)
                }
            }
        }
    }

    @Test
    fun `setting names require valid identifiers`() {
        listOf("", " ", "1index_granularity", "index-granularity").forEach { name ->
            assertFailsWith<IllegalArgumentException> {
                mergeTree {
                    orderBy(EngineDslEvents.eventId)
                    unsafeRawSetting(name, "1")
                }
            }
        }
    }

    @Test
    fun `MergeTree requires at least one orderBy`() {
        try {
            mergeTree { /* orderBy 없음 */ }
            error("Should have thrown")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `ReplacingMergeTree with versionColumn`() {
        val engine = replacingMergeTree {
            orderBy(EngineDslEvents.eventId)
            versionColumn(EngineDslEvents.version)
        }
        val clause = engine.toClause()
        clause shouldContain "ENGINE = ReplacingMergeTree(version)"
        clause shouldContain "ORDER BY (event_id)"
    }

    @Test
    fun `ReplacingMergeTree renders raw clauses and all setting overloads`() {
        val engine = replacingMergeTree {
            unsafeRawOrderBy("event_id")
            unsafeRawVersionColumn("version")
            unsafeRawPartitionBy("toYYYYMM(event_month)")
            applyAllSettingOverloads()
        }

        val clause = engine.toClause()

        clause shouldContain "ENGINE = ReplacingMergeTree(version)"
        clause shouldContain "ORDER BY (event_id)"
        clause shouldContain "PARTITION BY toYYYYMM(event_month)"
        assertAllSettingsRendered(clause)
    }

    @Test
    fun `SummingMergeTree with sumColumns`() {
        val engine = summingMergeTree {
            orderBy(EngineDslEvents.eventId)
            sumColumns(EngineDslEvents.amount)
        }
        val clause = engine.toClause()
        clause shouldContain "ENGINE = SummingMergeTree(amount)"
    }

    @Test
    fun `SummingMergeTree renders raw clauses and all setting overloads`() {
        val engine = summingMergeTree {
            unsafeRawOrderBy("event_id")
            unsafeRawSumColumns("amount")
            unsafeRawPartitionBy("toYYYYMM(event_month)")
            applyAllSettingOverloads()
        }

        val clause = engine.toClause()

        clause shouldContain "ENGINE = SummingMergeTree(amount)"
        clause shouldContain "ORDER BY (event_id)"
        clause shouldContain "PARTITION BY toYYYYMM(event_month)"
        assertAllSettingsRendered(clause)
    }

    @Test
    fun `AggregatingMergeTree basic`() {
        val engine = aggregatingMergeTree {
            orderBy(EngineDslEvents.eventId)
            partitionBy(EngineDslEvents.eventMonth)
        }
        val clause = engine.toClause()
        clause shouldContain "ENGINE = AggregatingMergeTree()"
        clause shouldContain "ORDER BY (event_id)"
    }

    @Test
    fun `AggregatingMergeTree renders raw clauses and all setting overloads`() {
        val engine = aggregatingMergeTree {
            unsafeRawOrderBy("event_id")
            unsafeRawPartitionBy("toYYYYMM(event_month)")
            applyAllSettingOverloads()
        }

        val clause = engine.toClause()

        clause shouldContain "ENGINE = AggregatingMergeTree()"
        clause shouldContain "ORDER BY (event_id)"
        clause shouldContain "PARTITION BY toYYYYMM(event_month)"
        assertAllSettingsRendered(clause)
    }

    @Test
    fun `MergeTree family requires order by expressions`() {
        assertFailsWith<IllegalArgumentException> { replacingMergeTree {} }
        assertFailsWith<IllegalArgumentException> { summingMergeTree {} }
        assertFailsWith<IllegalArgumentException> { aggregatingMergeTree {} }
    }

    @Test
    fun `sanitizeForClickHouse removes PRIMARY KEY`() {
        val sql = "CREATE TABLE t (id BIGINT PRIMARY KEY, name VARCHAR(255) NOT NULL)"
        val sanitized = sanitizeForClickHouse(sql)
        sanitized shouldNotContain "PRIMARY KEY"
        sanitized shouldNotContain "NOT NULL"
    }

    @Test
    fun `sanitizeForClickHouse removes CONSTRAINT PRIMARY KEY`() {
        val sql = "CREATE TABLE t (id BIGINT, CONSTRAINT pk PRIMARY KEY (id))"
        val sanitized = sanitizeForClickHouse(sql)
        sanitized shouldNotContain "CONSTRAINT"
        sanitized shouldNotContain "PRIMARY KEY"
    }

    @Test
    fun `sanitizeForClickHouse removes REFERENCES`() {
        val sql = "CREATE TABLE t (user_id BIGINT REFERENCES users(id))"
        val sanitized = sanitizeForClickHouse(sql)
        sanitized shouldNotContain "REFERENCES"
    }

    @Test
    fun `sanitizeForClickHouse removes NULL and NOT NULL`() {
        val sql = "CREATE TABLE t (a INT NOT NULL, b INT NULL, c INT)"
        val sanitized = sanitizeForClickHouse(sql)
        sanitized shouldNotContain "NOT NULL"
        sanitized shouldNotContain " NULL"
    }

    private fun MergeTreeBuilder.applyAllSettingOverloads() {
        settings(ClickHouseSetting.of("index_granularity", 8192))
        setting("index_granularity", 8193)
        setting("index_granularity_bytes", 1024L)
        setting("min_rows_for_wide_part", 1.5)
        setting("allow_nullable_key", true)
        setting("storage_policy", "hot")
        setting(ClickHouseSettingName.INDEX_GRANULARITY, 8194)
        setting(ClickHouseSettingName.INDEX_GRANULARITY_BYTES, 2048L)
        setting(ClickHouseSettingName.MIN_BYTES_FOR_WIDE_PART, 2.5)
        setting(ClickHouseSettingName.ALLOW_NULLABLE_KEY, false)
        setting(ClickHouseSettingName.STORAGE_POLICY, "cold")
    }

    private fun ReplacingMergeTreeBuilder.applyAllSettingOverloads() {
        settings(ClickHouseSetting.of("index_granularity", 8192))
        setting("index_granularity", 8193)
        setting("index_granularity_bytes", 1024L)
        setting("min_rows_for_wide_part", 1.5)
        setting("allow_nullable_key", true)
        setting("storage_policy", "hot")
        setting(ClickHouseSettingName.INDEX_GRANULARITY, 8194)
        setting(ClickHouseSettingName.INDEX_GRANULARITY_BYTES, 2048L)
        setting(ClickHouseSettingName.MIN_BYTES_FOR_WIDE_PART, 2.5)
        setting(ClickHouseSettingName.ALLOW_NULLABLE_KEY, false)
        setting(ClickHouseSettingName.STORAGE_POLICY, "cold")
    }

    private fun SummingMergeTreeBuilder.applyAllSettingOverloads() {
        settings(ClickHouseSetting.of("index_granularity", 8192))
        setting("index_granularity", 8193)
        setting("index_granularity_bytes", 1024L)
        setting("min_rows_for_wide_part", 1.5)
        setting("allow_nullable_key", true)
        setting("storage_policy", "hot")
        setting(ClickHouseSettingName.INDEX_GRANULARITY, 8194)
        setting(ClickHouseSettingName.INDEX_GRANULARITY_BYTES, 2048L)
        setting(ClickHouseSettingName.MIN_BYTES_FOR_WIDE_PART, 2.5)
        setting(ClickHouseSettingName.ALLOW_NULLABLE_KEY, false)
        setting(ClickHouseSettingName.STORAGE_POLICY, "cold")
    }

    private fun AggregatingMergeTreeBuilder.applyAllSettingOverloads() {
        settings(ClickHouseSetting.of("index_granularity", 8192))
        setting("index_granularity", 8193)
        setting("index_granularity_bytes", 1024L)
        setting("min_rows_for_wide_part", 1.5)
        setting("allow_nullable_key", true)
        setting("storage_policy", "hot")
        setting(ClickHouseSettingName.INDEX_GRANULARITY, 8194)
        setting(ClickHouseSettingName.INDEX_GRANULARITY_BYTES, 2048L)
        setting(ClickHouseSettingName.MIN_BYTES_FOR_WIDE_PART, 2.5)
        setting(ClickHouseSettingName.ALLOW_NULLABLE_KEY, false)
        setting(ClickHouseSettingName.STORAGE_POLICY, "cold")
    }

    private fun assertAllSettingsRendered(clause: String) {
        clause shouldContain "index_granularity = 8192"
        clause shouldContain "index_granularity = 8193"
        clause shouldContain "index_granularity_bytes = 1024"
        clause shouldContain "min_rows_for_wide_part = 1.5"
        clause shouldContain "allow_nullable_key = 1"
        clause shouldContain "storage_policy = 'hot'"
        clause shouldContain "index_granularity = 8194"
        clause shouldContain "index_granularity_bytes = 2048"
        clause shouldContain "min_bytes_for_wide_part = 2.5"
        clause shouldContain "allow_nullable_key = 0"
        clause shouldContain "storage_policy = 'cold'"
    }
}
