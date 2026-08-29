package io.bluetape4k.exposed.ktor.tenant.r2dbc

import io.bluetape4k.exposed.ktor.r2dbc.exposedR2dbcTransaction
import io.bluetape4k.ktor.tenant.KtorTenantContext
import io.bluetape4k.tenant.TenantId
import io.ktor.server.application.ApplicationCall
import io.micrometer.core.instrument.MeterRegistry
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction

/**
 * 현재 Ktor call에 binding된 tenant의 database에서 coroutine-native R2DBC transaction을
 * 실행합니다.
 *
 * [databaseResolver]는 route event loop에서 빠르게 끝나는 O(1) 메모리 exact-match
 * 조회여야 합니다. resolver와 database lifecycle, [meterRegistry]는 호출자가 소유합니다.
 * context가 없으면 resolver를 호출하기 전에
 * [io.bluetape4k.tenant.MissingTenantContextException]으로 실패합니다.
 */
suspend fun <T> ApplicationCall.exposedTenantR2dbcTransaction(
    databaseResolver: (TenantId) -> R2dbcDatabase,
    meterRegistry: MeterRegistry? = null,
    block: suspend R2dbcTransaction.() -> T,
): T {
    val tenantId = KtorTenantContext.requireCurrent(this)
    val database = databaseResolver(tenantId)
    return this.exposedR2dbcTransaction(database, meterRegistry, block)
}
