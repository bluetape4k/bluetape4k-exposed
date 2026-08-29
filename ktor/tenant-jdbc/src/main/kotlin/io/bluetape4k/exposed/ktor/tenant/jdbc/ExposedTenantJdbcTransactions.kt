package io.bluetape4k.exposed.ktor.tenant.jdbc

import io.bluetape4k.exposed.ktor.jdbc.exposedJdbcTransaction
import io.bluetape4k.ktor.tenant.KtorTenantContext
import io.bluetape4k.tenant.TenantId
import io.ktor.server.application.ApplicationCall
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.CoroutineDispatcher
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction

/**
 * 현재 Ktor call에 binding된 tenant의 database에서 JDBC transaction을 실행합니다.
 *
 * [databaseResolver]는 route event loop에서 빠르게 끝나는 O(1) 메모리 exact-match
 * 조회여야 합니다. resolver와 database lifecycle, [blockingDispatcher],
 * [meterRegistry]는 호출자가 소유합니다. context가 없으면 resolver를 호출하기 전에
 * [io.bluetape4k.tenant.MissingTenantContextException]으로 실패합니다.
 */
suspend fun <T> ApplicationCall.exposedTenantJdbcTransaction(
    databaseResolver: (TenantId) -> Database,
    blockingDispatcher: CoroutineDispatcher,
    meterRegistry: MeterRegistry? = null,
    block: JdbcTransaction.() -> T,
): T {
    val tenantId = KtorTenantContext.requireCurrent(this)
    val database = databaseResolver(tenantId)
    return this.exposedJdbcTransaction(database, blockingDispatcher, meterRegistry, block)
}
