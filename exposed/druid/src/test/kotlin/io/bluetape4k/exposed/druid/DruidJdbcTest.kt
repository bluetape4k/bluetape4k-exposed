package io.bluetape4k.exposed.druid

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import org.junit.jupiter.api.Test

class DruidJdbcTest {

    @Test
    fun `Avatica remote driver name is explicit`() {
        DruidJdbc.DRIVER shouldBeEqualTo "org.apache.calcite.avatica.remote.Driver"
    }

    @Test
    fun `metadata SQL stays query-only and parameterized`() {
        DruidJdbc.DRUID_COLUMNS_SQL shouldContain "INFORMATION_SCHEMA.COLUMNS"
        DruidJdbc.DRUID_COLUMNS_SQL shouldContain "TABLE_SCHEMA = ?"
        DruidJdbc.DRUID_COLUMNS_SQL shouldContain "TABLE_NAME = ?"
    }
    @Test
    fun `query helper rejects non query statements before opening a connection`() {
        assertFailsWith<IllegalArgumentException> {
            DruidJdbc.query("INSERT INTO foo SELECT 1") { rs -> rs.getInt(1) }
        }
    }
}
