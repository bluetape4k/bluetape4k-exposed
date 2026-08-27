package io.bluetape4k.exposed.ktor.jdbc

import io.bluetape4k.exposed.ktor.core.ExposedKtorCoreErrorCode
import io.bluetape4k.exposed.ktor.core.ExposedKtorReadinessTimeoutException
import io.bluetape4k.exposed.ktor.core.ExposedKtorTransactionException
import io.bluetape4k.exposed.ktor.core.respondExposedKtorCoreError
import io.ktor.server.plugins.statuspages.StatusPagesConfig
import kotlinx.coroutines.CancellationException
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import java.sql.SQLException

/** 호출자 소유 StatusPages block에 JDBC 전용 오류 mapping을 설치합니다. */
fun StatusPagesConfig.bluetape4kExposedJdbcErrors() {
    exception<CancellationException> { _, cause -> throw cause }
    exception<ExposedKtorReadinessTimeoutException> { call, _ ->
        call.respondExposedKtorCoreError(ExposedKtorCoreErrorCode.READINESS_TIMEOUT)
    }
    exception<ExposedKtorTransactionException> { call, _ ->
        call.respondExposedKtorCoreError(ExposedKtorCoreErrorCode.TRANSACTION)
    }
    exception<ExposedSQLException> { call, _ ->
        call.respondExposedKtorCoreError(ExposedKtorCoreErrorCode.DATABASE_UNAVAILABLE)
    }
    exception<SQLException> { call, _ ->
        call.respondExposedKtorCoreError(ExposedKtorCoreErrorCode.DATABASE_UNAVAILABLE)
    }
}
