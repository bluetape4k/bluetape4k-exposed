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
 * Opt-in JDBC Caffeine repository base that publishes Spring Modulith events
 * after cache writes reach the JDBC persistence boundary.
 *
 * Contract:
 * - `WRITE_THROUGH` publishes after the synchronous database write succeeds.
 * - `WRITE_BEHIND` publishes after a background flush commits and the retained
 *   in-memory batch has been cleared.
 * - `READ_ONLY`, invalidation, and cache clear operations publish nothing.
 * - Only synchronous JDBC Caffeine repositories are supported. Suspended JDBC
 *   and R2DBC Caffeine repositories are not covered by this base class.
 * - Publication happens outside the Exposed transaction. When
 *   [TransactionOperations] is supplied, publication is wrapped in that Spring
 *   transaction so Spring Modulith transactional listeners can consume the
 *   event after the publication boundary commits. Ordinary mapper or publisher
 *   failures are logged and swallowed as post-commit publication failures;
 *   [CancellationException] is rethrown.
 * - The process-local write-behind queue is not a durable outbox.
 *
 * Example:
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
 * Publish stable, minimal event DTOs. Do not return cached entities, pairs,
 * credentials, tokens, raw secrets, or full records from [toDomainEvent].
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
     * Maps a persisted cache write to a Spring application event.
     *
     * Return `null` to suppress publication for the write. The returned event
     * must be an already constructed, application-owned DTO with a stable type
     * name and a minimal payload. This method must not return serialized
     * payloads, event class names, cached entities, pairs, credentials, tokens,
     * raw secrets, or full records.
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
