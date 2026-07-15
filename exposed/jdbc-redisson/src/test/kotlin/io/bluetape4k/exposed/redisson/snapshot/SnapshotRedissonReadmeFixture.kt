package io.bluetape4k.exposed.redisson.snapshot.readme

// README-CANONICAL-REDISSON-BEGIN
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import io.bluetape4k.exposed.cache.snapshot.SnapshotCacheConfig
import io.bluetape4k.exposed.cache.snapshot.SnapshotCacheFailureBuffer
import io.bluetape4k.exposed.cache.snapshot.snapshotCacheFailureBuffer
import io.bluetape4k.exposed.redisson.snapshot.JdbcRedissonSnapshotInvalidator
import io.bluetape4k.exposed.redisson.snapshot.JdbcRedissonSnapshotInvalidatorConfig
import io.bluetape4k.exposed.redisson.snapshot.SnapshotRedissonCodec
import io.bluetape4k.exposed.redisson.snapshot.jdbcRedissonSnapshotInvalidator
import io.bluetape4k.exposed.redisson.snapshot.longSnapshotIdentifierPolicy
import io.bluetape4k.exposed.redisson.snapshot.snapshotRedissonCodec
import io.bluetape4k.exposed.redisson.snapshot.stageInvalidation
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.redisson.api.RedissonClient
import org.redisson.codec.TypedJsonJacksonCodec
import java.io.Serializable

data class RedissonOrderSnapshot @JsonCreator constructor(
    @JsonProperty("id") val id: Long,
    @JsonProperty("description") val description: String,
) : Serializable {
    private companion object {
        private const val serialVersionUID: Long = 1L
    }
}

private val orderInvalidationFailures: SnapshotCacheFailureBuffer = snapshotCacheFailureBuffer(capacity = 256)

fun orderSnapshotCodec(): SnapshotRedissonCodec<Long> =
    snapshotRedissonCodec(
        delegate = TypedJsonJacksonCodec(Long::class.javaObjectType, RedissonOrderSnapshot::class.java),
        codecVersion = "typed-json-v1",
        identifierPolicy = longSnapshotIdentifierPolicy(),
    )

fun orderSnapshotInvalidator(
    redissonClient: RedissonClient,
    codec: SnapshotRedissonCodec<Long>,
): JdbcRedissonSnapshotInvalidator<Long> {
    val config = JdbcRedissonSnapshotInvalidatorConfig(
        snapshot = SnapshotCacheConfig(namespace = "orders:v1", schemaVersion = "order-dto-v1"),
    )
    return jdbcRedissonSnapshotInvalidator<Long, RedissonOrderSnapshot>(
        redissonClient = redissonClient,
        codec = codec,
        config = config,
        failureBuffer = orderInvalidationFailures,
    )
}

fun JdbcTransaction.invalidateOrderSnapshot(
    invalidator: JdbcRedissonSnapshotInvalidator<Long>,
    id: Long,
) {
    stageInvalidation(invalidator, id)
}
// README-CANONICAL-REDISSON-END
