package issue731.consumer

import io.bluetape4k.batch.jdbc.ExposedJdbcBatchJobRepository
import io.bluetape4k.batch.r2dbc.ExposedR2dbcBatchJobRepository
import kotlin.test.Test
import kotlin.test.assertEquals

class ConsumerTest {
    @Test
    fun loadsAggregatorSurfaceAtRuntime() {
        assertEquals("io.bluetape4k.batch.core.BatchJob", compileProbe().removeSurrounding("\""))
        assertEquals("io.bluetape4k.batch.jdbc.ExposedJdbcBatchJobRepository", ExposedJdbcBatchJobRepository::class.qualifiedName)
        assertEquals("io.bluetape4k.batch.r2dbc.ExposedR2dbcBatchJobRepository", ExposedR2dbcBatchJobRepository::class.qualifiedName)
    }
}
