package io.bluetape4k.exposed.clickhouse

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.exposed.clickhouse.support.ClickHouseQueryObservation
import io.bluetape4k.exposed.clickhouse.support.QueryObservationOutcome
import org.junit.jupiter.api.Test
import java.time.Duration

class ClickHouseQueryObservationTest {

    @Test
    fun `원격 query observation은 빈 id에서 연결 없이 unavailable을 반환한다`() {
        var opened = false
        val result = ClickHouseQueryObservation(connectionFactory = {
            opened = true
            error("empty query id must not open an observation connection")
        }).awaitDisappearance("", Duration.ofSeconds(1))

        result.outcome shouldBeEqualTo QueryObservationOutcome.UNAVAILABLE
        result.reasonCode shouldBeEqualTo "EMPTY_QUERY_ID"
        opened.shouldBeFalse()
    }
}
