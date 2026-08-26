package issue731.consumer

import io.bluetape4k.batch.core.BatchJob
import io.bluetape4k.batch.CheckpointJson

fun compileProbe(): String {
    val customJson = object : CheckpointJson {
        override fun read(json: String): Any = json.removeSurrounding("\"")
        override fun write(obj: Any): String = "\"$obj\""
    }
    return customJson.write(BatchJob::class.qualifiedName.orEmpty())
}
