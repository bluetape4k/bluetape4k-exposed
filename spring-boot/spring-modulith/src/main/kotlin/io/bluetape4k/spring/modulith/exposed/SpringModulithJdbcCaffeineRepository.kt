package io.bluetape4k.spring.modulith.exposed

import io.bluetape4k.exposed.cache.CacheWriteMode
import io.bluetape4k.exposed.cache.LocalCacheConfig
import io.bluetape4k.exposed.jdbc.caffeine.repository.AbstractJdbcCaffeineRepository
import io.bluetape4k.exposed.jdbc.caffeine.repository.CachePersistedWrite
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import org.springframework.context.ApplicationEventPublisher
import org.springframework.transaction.support.TransactionOperations
import java.io.Serializable
import kotlin.coroutines.cancellation.CancellationException

/**
 * cache write가 JDBC 영속성 경계에 도달한 뒤 Spring Modulith 이벤트를 발행하는
 * 명시적 opt-in JDBC Caffeine Repository 기반 클래스입니다.
 *
 * ## 계약
 * - `WRITE_THROUGH`는 동기식 database write가 성공한 뒤 발행합니다.
 * - `WRITE_BEHIND`는 background flush가 commit되고 보관하던 in-memory batch를 비운 뒤 발행합니다.
 * - `READ_ONLY`, invalidation, cache clear 연산은 이벤트를 발행하지 않습니다.
 * - 동기식 JDBC Caffeine Repository만 지원합니다. suspend JDBC와 R2DBC Caffeine Repository는
 *   이 기반 클래스의 적용 대상이 아닙니다.
 * - 이벤트 발행은 Exposed 트랜잭션 밖에서 수행합니다. [TransactionOperations]를 제공하면
 *   발행을 해당 Spring 트랜잭션으로 감싸므로 Spring Modulith transactional listener가
 *   발행 경계의 commit 이후 이벤트를 소비할 수 있습니다. 일반 mapper 또는 publisher 실패는
 *   commit 이후 발행 실패로 기록하고 삼키지만 [CancellationException]은 다시 던집니다.
 * - process-local write-behind queue는 durable outbox가 아닙니다.
 *
 * ## 사용 예
 * ```kotlin
 * data class ActorUpdatedEvent(val actorId: Long) : Serializable {
 *     companion object {
 *         private const val serialVersionUID: Long = 1L
 *     }
 * }
 *
 * class ActorRepository(
 *     events: ApplicationEventPublisher,
 *     transactions: TransactionOperations,
 * ) :
 *     SpringModulithJdbcCaffeineRepository<Long, ActorRecord>(
 *         config = LocalCacheConfig.WRITE_THROUGH,
 *         eventPublisher = events,
 *         transactionOperations = transactions,
 *     ) {
 *     override fun toDomainEvent(id: Long, entity: ActorRecord): Any =
 *         ActorUpdatedEvent(actorId = id)
 * }
 * ```
 *
 * 안정적이고 최소한의 event DTO를 발행하세요. [toDomainEvent]에서 cache entity, pair,
 * credential, token, raw secret, 전체 record를 반환하면 안 됩니다.
 */
abstract class SpringModulithJdbcCaffeineRepository<ID: Any, E: Serializable>(
    config: LocalCacheConfig,
    private val eventPublisher: ApplicationEventPublisher,
    private val transactionOperations: TransactionOperations? = null,
): AbstractJdbcCaffeineRepository<ID, E>(config) {

    companion object: KLogging()

    init {
        if (config.writeMode == CacheWriteMode.READ_ONLY) {
            log.warn {
                "Spring Modulith cache event publication is disabled in READ_ONLY mode. " +
                    "cacheName=$cacheName"
            }
        }
    }

    /**
     * 영속화된 cache write를 Spring application event로 매핑합니다.
     *
     * 해당 write의 발행을 생략하려면 `null`을 반환합니다. 반환 이벤트는 안정적인 타입 이름과
     * 최소 payload를 가진, 애플리케이션이 소유하고 이미 생성된 DTO여야 합니다.
     * serialized payload, event class name, cache entity, pair, credential, token,
     * raw secret, 전체 record를 반환하면 안 됩니다.
     */
    protected abstract fun toDomainEvent(id: ID, entity: E): Any?

    override fun afterPersisted(id: ID, entity: E) {
        publishMappedEvent(id, entity)
    }

    override fun afterPersisted(writes: List<CachePersistedWrite<ID, E>>) {
        writes.forEach { write -> publishMappedEvent(write.id, write.entity) }
    }

    private fun publishMappedEvent(id: ID, entity: E) {
        val event =
            try {
                toDomainEvent(id, entity)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn(e) {
                    "Cache event mapping failed. " +
                        "cacheName=$cacheName, mode=$cacheWriteMode, idType=${id::class.qualifiedName}, " +
                        "exceptionType=${e::class.qualifiedName}"
                }
                return
            }

        if (event == null) {
            log.debug {
                "Cache event publication skipped. " +
                    "cacheName=$cacheName, mode=$cacheWriteMode, idType=${id::class.qualifiedName}"
            }
            return
        }

        try {
            publishEvent(event)
            log.debug {
                "Cache event published. " +
                    "cacheName=$cacheName, mode=$cacheWriteMode, eventType=${event::class.qualifiedName}"
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) {
                "Cache event publication failed. " +
                    "cacheName=$cacheName, mode=$cacheWriteMode, eventType=${event::class.qualifiedName}, " +
                    "exceptionType=${e::class.qualifiedName}"
            }
        }
    }

    private fun publishEvent(event: Any) {
        if (transactionOperations == null) {
            eventPublisher.publishEvent(event)
            return
        }

        transactionOperations.executeWithoutResult {
            eventPublisher.publishEvent(event)
        }
    }
}
