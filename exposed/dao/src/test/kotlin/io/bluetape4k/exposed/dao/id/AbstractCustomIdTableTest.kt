package io.bluetape4k.exposed.dao.id

import io.bluetape4k.exposed.tests.AbstractExposedTest
import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.params.provider.Arguments
import kotlin.uuid.Uuid

abstract class AbstractCustomIdTableTest: AbstractExposedTest() {

    companion object: KLogging()

    fun getTestDBAndEntityCount(): List<Arguments> {
        val recordCounts = listOf(50, 500)

        return TestDB.enabledDialects().flatMap { testDB ->
            recordCounts.map { entityCount ->
                Arguments.of(testDB, entityCount)
            }
        }
    }

    // HINT: bluetape4k-core 에 uuidVersion() 함수를 추가한다.
    internal fun Uuid.uuidVersion(): Int = (toByteArray()[6].toInt() ushr 4) and 0x0F
}
