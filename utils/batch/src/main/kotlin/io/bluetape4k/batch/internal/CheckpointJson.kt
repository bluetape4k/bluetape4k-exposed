package io.bluetape4k.batch.internal

import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.jvm.javaObjectType
import kotlin.reflect.KClass

/**
 * Checkpoint 객체를 직렬화 봉투([TypedCheckpoint])로 감싸 round-trip을 보장한다.
 *
 * Jackson 3는 activateDefaultTyping이 제거되어 `Any` 역직렬화 시 원래 타입을 잃는다.
 * 예: writeValueAsString(42L) → readValue("42", Any::class) → Integer(42) (Long 아님)
 * TypedCheckpoint는 className을 함께 저장하여 이 문제를 해결한다.
 */
internal data class TypedCheckpoint(val className: String, val payload: String)

/**
 * Checkpoint 객체를 문자열로 직렬화/역직렬화하는 전략 인터페이스.
 *
 * ## round-trip 보장 필수
 * [read]가 원래 타입을 완전히 복원하지 못하면 `BatchReader.restoreFrom(checkpoint as K)`에서
 * [ClassCastException]이 발생하여 **silent 재시작 실패**가 된다.
 * toString() 기반 fallback 같은 lossy 직렬화는 금지된다.
 *
 * ## 기본 구현
 * [jackson3] — `bluetape4k-jackson3` (tools.jackson) 모듈이 classpath에 있을 때만 사용 가능.
 * jackson3 없으면 사용자가 직접 구현하여 `ExposedJdbc/R2dbcBatchJobRepository` 생성자에 주입한다.
 *
 * ## InMemoryBatchJobRepository
 * 인메모리 구현은 `Any` 객체 그대로 `ConcurrentHashMap`에 저장하므로 본 인터페이스가 필요 없다.
 */
interface CheckpointJson {
    fun write(obj: Any): String
    fun read(json: String): Any

    companion object {
        private val defaultAllowedCheckpointClasses: Set<KClass<out Any>> = setOf(
            String::class,
            Boolean::class,
            Byte::class,
            Short::class,
            Int::class,
            Long::class,
            Float::class,
            Double::class,
            BigDecimal::class,
            BigInteger::class,
            Instant::class,
            LocalDate::class,
            LocalDateTime::class,
            OffsetDateTime::class,
            UUID::class,
            Map::class,
            List::class,
            Set::class,
            java.util.LinkedHashMap::class,
            java.util.HashMap::class,
            java.util.ArrayList::class,
            java.util.LinkedHashSet::class,
            java.util.HashSet::class,
        )

        /**
         * `bluetape4k-jackson3` 기반 팩토리.
         * `tools.jackson.databind.json.JsonMapper`가 classpath에 없으면
         * 즉시 [IllegalStateException]을 던진다 (Jackson 3는 `tools.jackson.*` 패키지).
         */
        fun jackson3(): CheckpointJson =
            jackson3(emptySet())

        /**
         * `bluetape4k-jackson3` 기반 팩토리.
         *
         * 기본 scalar/collection 타입 외 checkpoint 타입은 명시적으로 등록해야 한다.
         * persisted JSON의 `className`은 이 registry에서만 해석하며 임의 [Class.forName]을 호출하지 않는다.
         */
        fun jackson3(vararg allowedCheckpointClasses: KClass<out Any>): CheckpointJson =
            jackson3(allowedCheckpointClasses.asIterable())

        /**
         * `bluetape4k-jackson3` 기반 팩토리.
         *
         * 기본 scalar/collection 타입 외 checkpoint 타입은 명시적으로 등록해야 한다.
         */
        fun jackson3(allowedCheckpointClasses: Iterable<KClass<out Any>>): CheckpointJson = try {
            Class.forName("tools.jackson.databind.json.JsonMapper")
            Jackson3CheckpointJson(defaultAllowedCheckpointClasses + allowedCheckpointClasses)
        } catch (e: ClassNotFoundException) {
            throw IllegalStateException(
                "CheckpointJson.jackson3() requires bluetape4k-jackson3 (tools.jackson) on classpath. " +
                    "Provide a custom CheckpointJson or add the bluetape4k-jackson3 dependency.",
                e,
            )
        }
        // toString() fallback은 round-trip 불가 → 제공하지 않음
    }
}

/**
 * `bluetape4k-jackson3`의 [io.bluetape4k.jackson3.Jackson.defaultJsonMapper] 기반 구현.
 *
 * [TypedCheckpoint] 봉투로 className을 보존하여 Jackson 3의 Default Typing 제거에도
 * 타입 round-trip을 보장한다.
 *
 * - write(42L) → `{"className":"java.lang.Long","payload":"42"}`
 * - read(json) → Long(42)
 */
internal class Jackson3CheckpointJson(
    allowedCheckpointClasses: Iterable<KClass<out Any>>,
) : CheckpointJson {
    private val mapper = io.bluetape4k.jackson3.Jackson.defaultJsonMapper
    private val registry = CheckpointClassRegistry(allowedCheckpointClasses)

    override fun write(obj: Any): String {
        val checkpointClass = registry.serializationClass(obj)
        val envelope = TypedCheckpoint(
            className = checkpointClass.name,
            payload = mapper.writeValueAsString(obj),
        )
        return mapper.writeValueAsString(envelope)
    }

    override fun read(json: String): Any {
        val envelope = mapper.readValue(json, TypedCheckpoint::class.java)
        val clazz = registry.deserializationClass(envelope.className)
        return mapper.readValue(envelope.payload, clazz)
    }
}

private class CheckpointClassRegistry(
    allowedCheckpointClasses: Iterable<KClass<out Any>>,
) {
    private val registeredClasses: List<Class<out Any>> = allowedCheckpointClasses
        .map { it.javaObjectType }
        .distinctBy { it.name }

    private val registeredClassesByName: Map<String, Class<out Any>> =
        registeredClasses.associateBy { it.name }

    fun serializationClass(obj: Any): Class<out Any> =
        registeredClassesByName[obj.javaClass.name]
            ?: registeredClasses.firstOrNull { it.isInstance(obj) }
            ?: throw IllegalArgumentException("Checkpoint class is not registered: ${obj.javaClass.name}")

    fun deserializationClass(className: String): Class<out Any> =
        registeredClassesByName[className]
            ?: throw IllegalArgumentException("Checkpoint class is not registered: $className")
}
