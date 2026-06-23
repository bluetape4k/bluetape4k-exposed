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
    fun `SummingMergeTree with sumColumns`() {
        val engine = summingMergeTree {
            orderBy(EngineDslEvents.eventId)
            sumColumns(EngineDslEvents.amount)
        }
        val clause = engine.toClause()
        clause shouldContain "ENGINE = SummingMergeTree(amount)"
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
}
