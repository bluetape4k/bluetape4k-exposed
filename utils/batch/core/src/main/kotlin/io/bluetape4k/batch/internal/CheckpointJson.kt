package io.bluetape4k.batch.internal

import kotlin.reflect.KClass

/**
 * `io.bluetape4k.batch.CheckpointJson`으로 이동한 이전 공개 JVM descriptor를
 * 보존하는 호환 브리지입니다. 새 코드는 안정 패키지의 타입을 사용하세요.
 */
@Deprecated(
    message = "Use io.bluetape4k.batch.CheckpointJson",
    replaceWith = ReplaceWith("io.bluetape4k.batch.CheckpointJson"),
)
interface CheckpointJson : io.bluetape4k.batch.CheckpointJson {
    override fun write(obj: Any): String

    override fun read(json: String): Any

    companion object {
        fun jackson3(): CheckpointJson =
            LegacyCheckpointJson(io.bluetape4k.batch.CheckpointJson.jackson3())

        fun jackson3(vararg allowedCheckpointClasses: KClass<out Any>): CheckpointJson =
            LegacyCheckpointJson(io.bluetape4k.batch.CheckpointJson.jackson3(*allowedCheckpointClasses))

        fun jackson3(allowedCheckpointClasses: Iterable<KClass<out Any>>): CheckpointJson =
            LegacyCheckpointJson(io.bluetape4k.batch.CheckpointJson.jackson3(allowedCheckpointClasses))
    }
}

private class LegacyCheckpointJson(
    private val delegate: io.bluetape4k.batch.CheckpointJson,
) : CheckpointJson {
    override fun write(obj: Any): String = delegate.write(obj)

    override fun read(json: String): Any = delegate.read(json)
}
