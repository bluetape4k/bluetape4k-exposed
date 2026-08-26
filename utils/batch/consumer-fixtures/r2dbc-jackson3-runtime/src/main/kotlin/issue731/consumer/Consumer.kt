package issue731.consumer

import io.bluetape4k.batch.CheckpointJson
import io.bluetape4k.batch.r2dbc.ExposedR2dbcBatchJobRepository

fun compileProbe(): List<Class<*>> = listOf(
    CheckpointJson.jackson3()::class.java,
    ExposedR2dbcBatchJobRepository::class.java,
)
