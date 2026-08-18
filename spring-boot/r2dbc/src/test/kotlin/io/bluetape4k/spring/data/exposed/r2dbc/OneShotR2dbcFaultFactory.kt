package io.bluetape4k.spring.data.exposed.r2dbc

import io.r2dbc.spi.Connection
import io.r2dbc.spi.ConnectionFactory
import io.r2dbc.spi.R2dbcTransientResourceException
import org.reactivestreams.Publisher
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 첫 transaction의 commit을 한 번만 fault로 종료시키는 R2DBC adapter입니다.
 *
 * transaction block과 `saveAll` accumulator가 모두 완료된 뒤 commit 경계에서
 * 실패시키므로, Exposed가 rollback 후 새 connection에서 block/input을 재실행하고
 * 실패 attempt 결과를 버리는지 검증할 수 있습니다. 실제 driver와 connection
 * lifecycle은 delegate에 위임합니다.
 */
internal class OneShotR2dbcFaultFactory(
    private val delegate: ConnectionFactory,
) : ConnectionFactory {

    private val failureInjected = AtomicBoolean(false)

    val connectionCount = AtomicInteger(0)
    val beginCount = AtomicInteger(0)
    val commitCount = AtomicInteger(0)
    val rollbackCount = AtomicInteger(0)
    val closeCount = AtomicInteger(0)
    val failureCount = AtomicInteger(0)

    override fun create(): Publisher<out Connection> =
        Flux.from(delegate.create()).map { connection ->
            connectionCount.incrementAndGet()
            connectionProxy(connection)
        }

    override fun getMetadata() = delegate.metadata

    private fun connectionProxy(delegate: Connection): Connection =
        Proxy.newProxyInstance(
            Connection::class.java.classLoader,
            arrayOf(Connection::class.java),
        ) { _, method, args ->
            when (method.name) {
                "beginTransaction" -> {
                    beginCount.incrementAndGet()
                    invoke(delegate, method, args)
                }

                "commitTransaction" -> {
                    commitCount.incrementAndGet()
                    Mono.defer {
                        if (failureInjected.compareAndSet(false, true)) {
                            failureCount.incrementAndGet()
                            Mono.error<Void>(R2dbcTransientResourceException("one-shot commit retry fault"))
                        } else {
                            @Suppress("UNCHECKED_CAST")
                            Mono.from(invoke(delegate, method, args) as Publisher<Void>)
                        }
                    }
                }

                "rollbackTransaction" -> {
                    rollbackCount.incrementAndGet()
                    invoke(delegate, method, args)
                }

                "close" -> {
                    closeCount.incrementAndGet()
                    invoke(delegate, method, args)
                }

                else -> invoke(delegate, method, args)
            }
        } as Connection

    private fun invoke(target: Any, method: Method, args: Array<out Any?>?): Any? = try {
        method.invoke(target, *(args ?: emptyArray()))
    } catch (cause: InvocationTargetException) {
        throw cause.targetException
    }
}
