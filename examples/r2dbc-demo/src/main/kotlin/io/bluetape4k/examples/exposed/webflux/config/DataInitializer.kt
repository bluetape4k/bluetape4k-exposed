package io.bluetape4k.examples.exposed.webflux.config

import io.bluetape4k.examples.exposed.webflux.domain.Products
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.error
import io.bluetape4k.logging.info
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.SchemaUtils
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.springframework.boot.availability.AvailabilityChangeEvent
import org.springframework.boot.availability.ReadinessState
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationContext
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private const val FIRST_PRODUCT_STOCK = 100
private const val SECOND_PRODUCT_STOCK = 50
private const val THIRD_PRODUCT_STOCK = 200

/**
 * WebFlux 예제용 초기 데이터를 애플리케이션 lifecycle에 묶어 적재한다.
 *
 * `ApplicationReadyEvent`를 여러 번 받아도 한 번만 초기화하며,
 * 초기화가 끝날 때까지 Spring Boot readiness를 `REFUSING_TRAFFIC`으로 유지한다.
 * 초기화 실패는 `awaitReady()`와 `/readyz` 상태로 관찰할 수 있다.
 */
@Component
class DataInitializer(
    private val r2dbcDatabase: R2dbcDatabase,
    eventPublisher: ApplicationEventPublisher,
    private val applicationContext: ApplicationContext,
    databaseCoroutineDispatcher: CoroutineDispatcher,
): AutoCloseable {

    private val lifecycle = DataInitializerLifecycle(
        eventPublisher = eventPublisher,
        dispatcher = databaseCoroutineDispatcher,
        initialize = ::initializeData,
    )
    private val initializationMutex = Mutex()

    @EventListener(ApplicationReadyEvent::class)
    fun onApplicationReady(event: ApplicationReadyEvent) {
        if (event.applicationContext === applicationContext) {
            lifecycle.start()
        }
    }

    @EventListener
    fun onReadinessChange(event: AvailabilityChangeEvent<ReadinessState>) {
        lifecycle.onReadinessChange(event)
    }

    /** 초기 데이터 적재가 성공했는지 나타낸다. */
    val isReady: Boolean
        get() = lifecycle.isReady

    /** 초기 데이터 적재가 끝날 때까지 호출자를 suspend한다. */
    suspend fun awaitReady() {
        lifecycle.awaitReady()
    }

    /** 스키마를 보장하고 비어 있는 상품 테이블에 데모 seed를 멱등적으로 적재한다. */
    suspend fun initializeData() = initializationMutex.withLock {
        ensureSchema()
        suspendTransaction(r2dbcDatabase) {
            if (Products.selectAll().count() == 0L) {
                Products.insert {
                    it[name] = "Kotlin Coroutines Book"
                    it[price] = BigDecimal("39.99")
                    it[stock] = FIRST_PRODUCT_STOCK
                }
                Products.insert {
                    it[name] = "Spring WebFlux Guide"
                    it[price] = BigDecimal("49.99")
                    it[stock] = SECOND_PRODUCT_STOCK
                }
                Products.insert {
                    it[name] = "Reactive Programming"
                    it[price] = BigDecimal("29.99")
                    it[stock] = THIRD_PRODUCT_STOCK
                }
            }
        }
    }

    private suspend fun ensureSchema() {
        suspendTransaction(r2dbcDatabase) {
            val tables = SchemaUtils.listTables()
            val productTableExists = tables.any { it.equals(Products.tableName, ignoreCase = true) }
            if (!productTableExists) {
                SchemaUtils.create(Products)
            }
        }
    }

    @PreDestroy
    override fun close() {
        lifecycle.close()
    }
}

/**
 * 데이터 초기화 coroutine의 중복 실행, readiness 전파, 취소 대기를 담당하는 내부 lifecycle이다.
 */
internal class DataInitializerLifecycle(
    private val eventPublisher: ApplicationEventPublisher,
    dispatcher: CoroutineDispatcher,
    private val initialize: suspend () -> Unit,
): AutoCloseable {

    companion object: KLoggingChannel()

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val started = AtomicBoolean(false)
    private val state = AtomicReference(DataInitializationState.NOT_STARTED)
    private val completion = CompletableDeferred<Unit>()

    val isReady: Boolean
        get() = state.get() == DataInitializationState.READY

    @Suppress("TooGenericExceptionCaught") // application event 경계의 failure를 readiness lifecycle에 기록한다.
    fun start() {
        if (!started.compareAndSet(false, true)) {
            return
        }

        state.set(DataInitializationState.INITIALIZING)
        try {
            publishReadiness(ReadinessState.REFUSING_TRAFFIC)
            log.info { "R2DBC demo 데이터 초기화를 시작합니다." }
            val job = scope.launch {
                try {
                    initialize()
                    state.set(DataInitializationState.READY)
                    publishReadiness(ReadinessState.ACCEPTING_TRAFFIC)
                    completion.complete(Unit)
                    log.info { "R2DBC demo 데이터 초기화가 완료되었습니다." }
                } catch (e: CancellationException) {
                    state.set(DataInitializationState.CANCELLED)
                    completion.cancel(e)
                    throw e
                } catch (e: RuntimeException) {
                    fail(e)
                }
            }
            job.invokeOnCompletion { cause ->
                if (cause is CancellationException) {
                    state.set(DataInitializationState.CANCELLED)
                    completion.cancel(cause)
                }
            }
        } catch (e: RuntimeException) {
            fail(e)
        }
    }

    @Suppress("TooGenericExceptionCaught") // readiness listener에서 application state를 관찰 가능한 상태로 유지한다.
    fun onReadinessChange(event: AvailabilityChangeEvent<ReadinessState>) {
        if (event.state == ReadinessState.ACCEPTING_TRAFFIC && !isReady) {
            try {
                publishReadiness(ReadinessState.REFUSING_TRAFFIC)
            } catch (e: RuntimeException) {
                fail(e)
            }
        }
    }

    suspend fun awaitReady() {
        check(started.get()) { "데이터 초기화가 아직 시작되지 않았습니다." }
        completion.await()
    }

    suspend fun closeAndJoin() {
        withContext(NonCancellable) {
            scope.coroutineContext.job.cancelAndJoin()
        }
    }

    override fun close() {
        runBlocking {
            closeAndJoin()
        }
    }

    private fun fail(exception: Exception) {
        state.set(DataInitializationState.FAILED)
        completion.completeExceptionally(exception)
        log.error(exception) { "R2DBC demo 데이터 초기화에 실패했습니다." }
        runCatching { publishReadiness(ReadinessState.REFUSING_TRAFFIC) }
    }

    private fun publishReadiness(state: ReadinessState) {
        eventPublisher.publishEvent(AvailabilityChangeEvent(this, state))
    }

}

internal enum class DataInitializationState {
    NOT_STARTED,
    INITIALIZING,
    READY,
    FAILED,
    CANCELLED,
}
