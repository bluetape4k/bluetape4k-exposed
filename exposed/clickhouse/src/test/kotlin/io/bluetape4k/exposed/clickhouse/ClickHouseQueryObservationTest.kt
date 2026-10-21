package io.bluetape4k.exposed.clickhouse

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.exposed.clickhouse.support.ClickHouseQueryObservation
import io.bluetape4k.exposed.clickhouse.support.QueryObservationOutcome
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.junit.jupiter.api.Test

class ClickHouseQueryObservationTest {

    companion object: KLogging()

    @Test
    fun `원격 query observation은 빈 id에서 연결 없이 unavailable을 반환한다`() {
        var opened = false
        val result = ClickHouseQueryObservation(
            connectionFactory = {
                opened = true
                error("empty query id must not open an observation connection")
            }
        ).awaitDisappearance("", 1.seconds())

        log.debug { "result=$result" }
        result.outcome shouldBeEqualTo QueryObservationOutcome.UNAVAILABLE
        result.reasonCode shouldBeEqualTo "EMPTY_QUERY_ID"
        opened.shouldBeFalse()
    }
}
