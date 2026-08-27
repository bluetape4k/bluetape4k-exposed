package io.bluetape4k.exposed.ktor.r2dbc

import io.bluetape4k.exposed.ktor.core.ExposedKtorCoreErrorCode
import io.bluetape4k.exposed.ktor.core.ExposedKtorReadinessTimeoutException
import io.bluetape4k.exposed.ktor.core.ExposedKtorTransactionException
import io.bluetape4k.exposed.ktor.core.respondExposedKtorCoreError
import io.ktor.server.plugins.statuspages.StatusPagesConfig
import io.r2dbc.spi.R2dbcException
import kotlinx.coroutines.CancellationException

/** 호출자 소유 StatusPages block에 R2DBC 전용 오류 mapping을 설치합니다. */
fun StatusPagesConfig.bluetape4kExposedR2dbcErrors() {
    exception<CancellationException> { _, cause -> throw cause }
    exception<ExposedKtorReadinessTimeoutException> { call, _ ->
        call.respondExposedKtorCoreError(ExposedKtorCoreErrorCode.READINESS_TIMEOUT)
    }
    exception<ExposedKtorTransactionException> { call, _ ->
        call.respondExposedKtorCoreError(ExposedKtorCoreErrorCode.TRANSACTION)
    }
    exception<R2dbcException> { call, _ ->
        call.respondExposedKtorCoreError(ExposedKtorCoreErrorCode.DATABASE_UNAVAILABLE)
    }
}
