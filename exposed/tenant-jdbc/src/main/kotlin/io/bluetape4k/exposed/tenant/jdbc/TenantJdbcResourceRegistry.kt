@file:Suppress("TooGenericExceptionCaught")

package io.bluetape4k.exposed.tenant.jdbc

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.util.Collections
import java.util.IdentityHashMap
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import javax.sql.DataSource

/**
 * tenant key와 JDBC resource의 생성·조회·종료 수명을 한 곳에서 소유하는 registry입니다.
 *
 * registry는 유한한 입력을 materialize한 뒤 tenant 순서대로 [dataSourceFactory]를 호출하고
 * 각 DataSource를 Exposed [Database]로 등록합니다. factory가 non-null DataSource를 반환한
 * 순간부터 registry가 그 instance의 cleanup을 소유하며, 같은 DataSource reference의 재사용은
 * 거부합니다. [Database.connect] 성공은 연결 가능성이나 schema readiness를 보장하지 않습니다.
 *
 * [close]는 Exposed 등록을 해제한 뒤 [disposeDataSource]를 동기적으로 호출합니다. 호출자는
 * registry를 닫기 전에 새 요청을 차단하고 진행 중인 요청을 drain해야 하며, callback의 timeout도
 * 직접 보장해야 합니다. 이 registry는 authorization, lease, secret redaction, Spring/Ktor lifecycle,
 * pool 설정 또는 readiness 확인을 대신하지 않습니다.
 */
class TenantJdbcResourceRegistry<K : Any> private constructor(
    private val resources: Map<K, OwnedTenantJdbcResource>,
    /** registry 생성 시 복사한 tenant key의 변경 불가능한 insertion-order view입니다. */
    val configuredTenants: Set<K>,
    private val hooks: TenantJdbcRegistryHooks,
) : AutoCloseable {

    /**
     * 등록된 tenant의 resource를 정확히 조회합니다.
     *
     * 이 조회는 authorization이나 사용 lease를 제공하지 않습니다. OPEN 상태를 관찰한 조회는
     * 동시 [close]와 경합하더라도 시작한 map 조회를 끝냅니다. registry가 종료를 시작한 뒤에
     * 새로 진입한 호출은 map을 조회하지 않고 고정된
     * [IllegalStateException]으로 실패합니다. 등록되지 않은 key는
     * [UnknownTenantJdbcResourceException]으로 실패합니다.
     */
    fun resourceFor(tenant: K): TenantJdbcResource {
        checkOpen()
        return resources[tenant] ?: throw UnknownTenantJdbcResourceException()
    }

    /** 등록된 tenant의 Exposed [Database]를 조회합니다. */
    fun databaseFor(tenant: K): Database = resourceFor(tenant).database

    /** 등록된 tenant의 JDBC [DataSource]를 조회합니다. */
    fun dataSourceFor(tenant: K): DataSource = resourceFor(tenant).dataSource

    /**
     * 모든 resource를 생성 역순으로 unregister한 뒤 caller disposer를 호출합니다.
     *
     * 여러 caller가 동시에 호출하면 한 caller만 cleanup을 수행하고 나머지는 동일한
     * 최종 lifecycle 결과를 관찰합니다. 종료 중인 owner thread의 재진입은 no-op입니다.
     * 일반 실패는 첫 Throwable identity와 이후 suppressed 순서를 보존합니다. fatal cleanup은
     * owner에게 원형으로 전달하고, 이후 호출에는 secret을 포함하지 않는 고정 예외를 반환합니다.
     */
    override fun close() {
        while (true) {
            when (val observed = state.get()) {
                CloseState.Open -> {
                    val closing = CloseState.Closing(Thread.currentThread(), CountDownLatch(1))
                    if (state.compareAndSet(observed, closing)) {
                        closeAsOwner(closing)
                    }
                }

                is CloseState.Closing -> {
                    if (observed.owner === Thread.currentThread()) return

                    hooks.beforeCloseWait()
                    var interrupted = false
                    while (true) {
                        try {
                            observed.completion.await()
                            break
                        } catch (_: InterruptedException) {
                            interrupted = true
                        }
                    }
                    if (interrupted) Thread.currentThread().interrupt()
                }

                CloseState.ClosedSuccess -> return
                is CloseState.ClosedFailure -> throw observed.failure
                is CloseState.ClosedFatal -> throw IllegalStateException(FATAL_CLOSE_MESSAGE)
            }
        }
    }

    private val state = AtomicReference<CloseState>(CloseState.Open)

    private fun checkOpen() {
        if (state.get() !is CloseState.Open) {
            throw IllegalStateException(CLOSED_MESSAGE)
        }
    }

    private fun closeAsOwner(closing: CloseState.Closing) {
        val accumulator = FailureAccumulator()
        for (resource in resources.values.reversed()) {
            try {
                hooks.unregisterDatabase(resource.database)
            } catch (failure: Throwable) {
                accumulator.record(failure)
            }
            try {
                resource.dispose()
            } catch (failure: Throwable) {
                accumulator.record(failure)
            }
        }

        val primary = accumulator.finish()
        val finalState = when {
            primary == null -> CloseState.ClosedSuccess
            primary.isFatal() -> CloseState.ClosedFatal(primary)
            else -> CloseState.ClosedFailure(primary)
        }
        state.set(finalState)
        closing.completion.countDown()
        if (accumulator.wasInterrupted) Thread.currentThread().interrupt()

        when (finalState) {
            CloseState.ClosedSuccess -> Unit
            is CloseState.ClosedFailure -> throw finalState.failure
            is CloseState.ClosedFatal -> throw finalState.failure
            else -> error("Close owner published an invalid lifecycle state.")
        }
    }

    companion object {
        private const val CLOSED_MESSAGE = "Tenant JDBC resource registry is closed."
        private const val FATAL_CLOSE_MESSAGE = "Tenant JDBC resource registry closed after a fatal cleanup failure."

        /**
         * tenant별 DataSource와 Exposed Database를 조립하는 registry를 생성합니다.
         *
         * [tenants]는 유한한 insertion-order 입력이어야 하며 null 또는 중복 key는 factory
         * 호출 전에 거부됩니다. tenant key의 equality/hashCode는 registry lifetime 동안 안정적이어야
         * 합니다. factory가 반환하기 전까지는 caller가 DataSource를 소유하고, non-null 반환 뒤에는
         * registry가 인수하여 조립 실패나 [close] 시 [disposeDataSource]에 tenant와 함께 전달합니다.
         * 동일한 DataSource reference가 여러 tenant에 반환되면 조립을 중단하고 그 instance는 한 번만
         * 정리합니다. callback이 던진 원형 Throwable graph는 가능한 한 그대로 보존됩니다.
         */
        @JvmStatic
        fun <K : Any, D : DataSource> create(
            tenants: Iterable<K>,
            dataSourceFactory: (K) -> D,
            disposeDataSource: (K, D) -> Unit,
        ): TenantJdbcResourceRegistry<K> = createInternal(
            tenants = tenants,
            dataSourceFactory = dataSourceFactory,
            disposeDataSource = disposeDataSource,
            hooks = TenantJdbcRegistryHooks(),
        )

        internal fun <K : Any, D : DataSource> createForTesting(
            tenants: Iterable<K>,
            dataSourceFactory: (K) -> D,
            disposeDataSource: (K, D) -> Unit,
            hooks: TenantJdbcRegistryHooks,
        ): TenantJdbcResourceRegistry<K> = createInternal(tenants, dataSourceFactory, disposeDataSource, hooks)

        @Suppress("SwallowedException", "ThrowsCount")
        private fun <K : Any, D : DataSource> createInternal(
            tenants: Iterable<K>,
            dataSourceFactory: (K) -> D,
            disposeDataSource: (K, D) -> Unit,
            hooks: TenantJdbcRegistryHooks,
        ): TenantJdbcResourceRegistry<K> {
            val keys = LinkedHashSet<K>()
            for (candidate in tenants) {
                val tenant = requireNotNull(candidate) { "Tenant key must not be null." }
                require(keys.add(tenant)) { "Duplicate tenant key." }
            }

            val seenDataSources = IdentityHashMap<DataSource, Unit>()
            val ownedResources = ArrayList<OwnedTenantJdbcResource>(keys.size)
            val resources = LinkedHashMap<K, OwnedTenantJdbcResource>(keys.size)
            val accumulator = FailureAccumulator()

            try {
                for (tenant in keys) {
                    val dataSource = try {
                        requireNotNull(dataSourceFactory(tenant)) { "dataSourceFactory returned null." }
                    } catch (failure: Throwable) {
                        accumulator.record(failure)
                        throw AssemblyFailure(failure)
                    }
                    if (seenDataSources.put(dataSource, Unit) != null) {
                        val failure = IllegalArgumentException("Tenant JDBC DataSource is reused.")
                        accumulator.record(failure)
                        throw AssemblyFailure(failure)
                    }

                    val database = try {
                        requireNotNull(hooks.connectDatabase(dataSource)) { "connectDatabase returned null." }
                    } catch (failure: Throwable) {
                        accumulator.record(failure)
                        disposeCurrent(tenant, dataSource, disposeDataSource, accumulator)
                        throw AssemblyFailure(failure)
                    }
                    val owned = OwnedTenantJdbcResource(
                        dataSource = dataSource,
                        database = database,
                        dispose = { disposeDataSource(tenant, dataSource) },
                    )
                    ownedResources += owned
                    try {
                        hooks.beforeResourceCommit()
                        resources[tenant] = owned
                    } catch (failure: Throwable) {
                        accumulator.record(failure)
                        throw AssemblyFailure(failure)
                    }
                }

                hooks.beforeRegistryPublish()
                val immutableResources = Collections.unmodifiableMap(LinkedHashMap(resources))
                val immutableKeys = Collections.unmodifiableSet(LinkedHashSet(keys))
                return TenantJdbcResourceRegistry(immutableResources, immutableKeys, hooks)
            } catch (assembly: AssemblyFailure) {
                cleanup(ownedResources, hooks, accumulator)
                throw accumulator.finishAndRestoreInterrupt() ?: assembly.failure
            } catch (failure: Throwable) {
                accumulator.record(failure)
                cleanup(ownedResources, hooks, accumulator)
                throw accumulator.finishAndRestoreInterrupt() ?: failure
            }
        }

        private fun <K : Any, D : DataSource> disposeCurrent(
            tenant: K,
            dataSource: D,
            disposer: (K, D) -> Unit,
            accumulator: FailureAccumulator,
        ) {
            try {
                disposer(tenant, dataSource)
            } catch (failure: Throwable) {
                accumulator.record(failure)
            }
        }

        private fun cleanup(
            resources: List<OwnedTenantJdbcResource>,
            hooks: TenantJdbcRegistryHooks,
            accumulator: FailureAccumulator,
        ) {
            for (resource in resources.asReversed()) {
                try {
                    hooks.unregisterDatabase(resource.database)
                } catch (failure: Throwable) {
                    accumulator.record(failure)
                }
                try {
                    resource.dispose()
                } catch (failure: Throwable) {
                    accumulator.record(failure)
                }
            }
        }
    }
}

private class OwnedTenantJdbcResource(
    override val dataSource: DataSource,
    override val database: Database,
    val dispose: () -> Unit,
) : TenantJdbcResource

internal data class TenantJdbcRegistryHooks(
    val connectDatabase: (DataSource) -> Database = { dataSource -> Database.connect(dataSource) },
    val unregisterDatabase: (Database) -> Unit = { database -> TransactionManager.closeAndUnregister(database) },
    val beforeResourceCommit: () -> Unit = {},
    val beforeRegistryPublish: () -> Unit = {},
    val beforeCloseWait: () -> Unit = {},
)

private sealed interface CloseState {
    data object Open : CloseState

    data class Closing(val owner: Thread, val completion: CountDownLatch) : CloseState

    data object ClosedSuccess : CloseState

    data class ClosedFailure(val failure: Throwable) : CloseState

    data class ClosedFatal(val failure: Throwable) : CloseState
}

private class AssemblyFailure(
    val failure: Throwable,
) : RuntimeException(null, null, false, false)

private class FailureAccumulator {
    private val failures = ArrayList<Throwable>()
    private val identities = IdentityHashMap<Throwable, Unit>()
    var wasInterrupted: Boolean = false
        private set

    var primary: Throwable? = null
        private set

    fun record(failure: Throwable) {
        if (failure is InterruptedException) wasInterrupted = true
        if (identities.put(failure, Unit) != null) return
        failures += failure
        val current = primary
        if (current == null || (!current.isFatal() && failure.isFatal())) {
            primary = failure
        }
    }

    fun finish(): Throwable? {
        val selected = primary ?: return null
        for (failure in failures) {
            if (failure !== selected && selected.suppressed.none { it === failure }) {
                selected.addSuppressed(failure)
            }
        }
        return selected
    }

    fun finishAndRestoreInterrupt(): Throwable? = finish().also {
        if (wasInterrupted) Thread.currentThread().interrupt()
    }
}

@Suppress("DEPRECATION")
private fun Throwable.isFatal(): Boolean =
    this is VirtualMachineError || this is ThreadDeath || this is LinkageError
