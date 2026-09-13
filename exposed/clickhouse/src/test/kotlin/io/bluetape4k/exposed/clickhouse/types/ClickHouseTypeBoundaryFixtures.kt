package io.bluetape4k.exposed.clickhouse.types

import io.bluetape4k.exposed.clickhouse.ClickHouseTable
import io.bluetape4k.exposed.clickhouse.engine.mergeTree
import java.math.BigDecimal
import java.net.Inet4Address
import java.net.Inet6Address
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * #866 ClickHouse wire 테스트가 공유하는 고유 schema와 seed.
 *
 * fixture는 테스트 간 충돌을 피하기 위해 고정된 table 이름을 사용하고,
 * 테스트 본문에서 `finally`로 drop한다. JSON/Nested처럼 서버 설정에 영향을
 * 받는 타입도 동일한 선언을 사용해 DDL과 metadata를 함께 검증한다.
 */
internal object ClickHouseComplexTypesTable: ClickHouseTable("clickhouse_v2_complex_types") {
    val id = long("id")
    val nullableArray = chArrayNullableElements("nullable_array", ClickHouseStringColumnType())
    val nestedArray = chArray("nested_array", ClickHouseArrayNullableElementsColumnType(ClickHouseInt32ColumnType()))
    val labels = chMap("labels", ClickHouseStringColumnType(), ClickHouseInt32ColumnType())
    val tuple = chTuple("tuple", listOf(ClickHouseStringColumnType(), ClickHouseInt32ColumnType()))
    val json = chJson("json")
    val uuid = chUuid("uuid")
    val ipv4 = chIpv4("ipv4")
    val ipv6 = chIpv6("ipv6")
    val eventTime = dateTime64("event_time", precision = 6, zone = ZoneOffset.UTC)
    val amount = chDecimal("amount", precision = 18, scale = 4)
    val counter = chUInt64BigInt("counter")
    val state = chEnum(
        "state",
        mapOf(ClickHouseComplexState.ACTIVE to "active", ClickHouseComplexState.DISABLED to "disabled"),
    )

    override val engine = mergeTree { orderBy(id) }
}

internal enum class ClickHouseComplexState {
    ACTIVE,
    DISABLED,
}

internal object ClickHouseTypeBoundaryFixture {
    const val seed: Long = 866L
    const val tableName: String = "clickhouse_v2_complex_types"

    val nullableArrayValue: List<String?> = listOf("alpha", "beta", "omega")
    val nestedArrayValue: List<List<Int?>> = listOf(listOf(1, null, 3), emptyList())
    val labelsValue: Map<String, Int> = linkedMapOf("one" to 1, "two" to 2)
    val tupleValue: List<Any?> = listOf("kr", 7)
    val nestedValue: List<List<Any?>> = listOf(listOf("kr", 7), listOf("us", 8))
    // JDBC V2 returns JSON objects as maps and ClickHouse emits its canonical spacing.
    const val jsonValue: String = "{\"id\":866, \"ok\":true}"
    val uuidValue: UUID = UUID.fromString("00000000-0000-0000-0000-000000000866")
    val ipv4Value: Inet4Address = java.net.InetAddress.getByName("127.0.0.1") as Inet4Address
    val ipv6Value: Inet6Address = java.net.InetAddress.getByName("::1") as Inet6Address
    val eventTimeValue: Instant = Instant.parse("2026-04-25T12:34:56.123456Z")
    val amountValue: BigDecimal = BigDecimal("1234.5678")
    val stateValue: ClickHouseComplexState = ClickHouseComplexState.ACTIVE
}
