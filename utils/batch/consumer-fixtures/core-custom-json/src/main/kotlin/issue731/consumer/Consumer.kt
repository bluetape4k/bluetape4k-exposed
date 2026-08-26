package issue731.consumer

import io.bluetape4k.batch.CheckpointJson

private object PlainJson : CheckpointJson {
    override fun read(json: String): Any = json.removeSurrounding("\"", "\"")
    override fun write(obj: Any): String = "\"$obj\""
}

fun runtimeProbe(): String = PlainJson.read(PlainJson.write("without-jackson")) as String
