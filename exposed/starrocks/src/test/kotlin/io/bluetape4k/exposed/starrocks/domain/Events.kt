package io.bluetape4k.exposed.starrocks.domain

import io.bluetape4k.exposed.starrocks.StarRocksTable

/**
 * Minimal StarRocks fixture table.
 */
object Events: StarRocksTable("events") {
    val eventId = long("event_id")
    val eventName = varchar("event_name", 100)
    val region = varchar("region", 32)
}
