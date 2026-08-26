package issue731.consumer

import io.bluetape4k.batch.CheckpointJson
import io.bluetape4k.batch.jdbc.ExposedJdbcBatchJobRepository

fun compileProbe(): List<Class<*>> = listOf(
    CheckpointJson::class.java,
    ExposedJdbcBatchJobRepository::class.java,
)
