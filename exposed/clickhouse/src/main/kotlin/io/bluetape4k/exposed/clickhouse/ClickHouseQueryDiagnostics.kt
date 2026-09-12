package io.bluetape4k.exposed.clickhouse

import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** ClickHouse 조회 lifecycle에서 관찰할 수 있는 이벤트 종류입니다. */
enum class ClickHouseQueryEventKind {
    Started,
    RequestPrepared,
    ResponseReceived,
    Completed,
    Failed,
    Cancelled,
}

/** 취소가 관찰된 경계를 나타냅니다. 원격 종료 성공을 추정하지 않습니다. */
enum class ClickHouseQueryCancellationState {
    LocalCancellation,
    CancelRequested,
    RemoteTerminationObserved,
    Unknown,
}

/** 조회의 최종 상태입니다. */
sealed interface ClickHouseQueryOutcome {
    /** 조회가 정상적으로 완료되었습니다. */
    data object Success: ClickHouseQueryOutcome

    /** JDBC 오류가 발생했습니다. */
    data class Failure(
        val sqlState: String?,
        val vendorCode: Int?,
    ): ClickHouseQueryOutcome

    /** 로컬에서 조회가 취소되었습니다. */
    data class Cancelled(val state: ClickHouseQueryCancellationState): ClickHouseQueryOutcome
}

/** listener 또는 sink callback에서 발생한 오류의 안전한 요약입니다. */
data class ClickHouseCallbackFailure(
    val exceptionType: String,
    val reasonCode: String,
    val sanitizedMessage: String?,
)

/**
 * ClickHouse 조회 한 건에 대한 immutable 진단 값입니다.
 *
 * SQL, 바인딩, 행 데이터와 인증 비밀값은 이 모델에 저장하지 않습니다. [sessionSettings]는
 * recorder가 snapshot한 값이며, 호출자는 callback에서 blocking I/O나 재진입 조회를 수행하지
 * 않아야 합니다.
 */
data class ClickHouseQueryDiagnostics(
    val queryId: String,
    val logComment: String?,
    val clientName: String?,
    val sessionSettings: Map<String, String>,
    val startedAt: Instant,
    val finishedAt: Instant?,
    val elapsed: java.time.Duration?,
    val returnedRows: Long?,
    val vendorCode: Int?,
    val outcome: ClickHouseQueryOutcome?,
    val callbackFailure: ClickHouseCallbackFailure?,
    val serverDisplayName: String? = null,
    val responseHeaders: Map<String, String> = emptyMap(),
)

/** 조회 lifecycle event를 현재 query dispatcher에서 동기로 전달하는 callback입니다. */
fun interface ClickHouseQueryListener {
    /** 짧고 non-blocking하게 event를 처리해야 합니다. */
    fun onEvent(event: ClickHouseQueryEvent)
}

/** terminal cleanup 이후 최종 diagnostics를 한 번 전달하는 callback입니다. */
fun interface ClickHouseQueryDiagnosticsSink {
    /** 짧고 non-blocking하게 최종 diagnostics를 저장해야 합니다. */
    fun accept(diagnostics: ClickHouseQueryDiagnostics)
}

/** 호출자 query ID가 없을 때 사용할 안전한 ID 생성기입니다. */
fun interface ClickHouseQueryIdGenerator {
    /** 요청 범위에서 충돌하지 않는 opaque ID를 반환해야 합니다. */
    fun generate(): String
}

/**
 * queryList/queryFlow에 선택적으로 연결하는 diagnostics 설정입니다.
 *
 * [queryId]가 지정되면 그대로 우선 사용합니다. 지정하지 않으면 [queryIdGenerator]가 호출되며,
 * 기본 생성기는 UUID입니다. 명시적 ID를 여러 동시 요청에서 재사용하는 경우의 전역 유일성은
 * 호출자가 보장해야 합니다.
 */
data class ClickHouseQueryDiagnosticsConfig(
    val queryId: String? = null,
    val queryIdGenerator: ClickHouseQueryIdGenerator = ClickHouseQueryIdGenerator { UUID.randomUUID().toString() },
    val logComment: String? = null,
    val clientName: String? = null,
    val sessionSettings: Map<String, String> = emptyMap(),
    val sessionDbRoles: List<String> = emptyList(),
    val sessionTimezone: ZoneId? = null,
    val listener: ClickHouseQueryListener? = null,
    val sink: ClickHouseQueryDiagnosticsSink? = null,
) {
    init {
        queryId?.let { validateText("queryId", it, allowBlank = false) }
        logComment?.let { validateText("logComment", it, allowBlank = false) }
        clientName?.let { validateText("clientName", it, allowBlank = true) }
        sessionSettings.forEach { (key, value) ->
            validateText("sessionSettings key", key, allowBlank = false)
            validateText("sessionSettings[$key]", value, allowBlank = true)
        }
        sessionDbRoles.forEach { validateText("sessionDbRoles", it, allowBlank = false) }
        require(sessionDbRoles.distinct().size == sessionDbRoles.size) {
            "sessionDbRoles에는 중복 역할을 사용할 수 없습니다."
        }
    }

    /** mutable collection과 분리된 session settings snapshot입니다. */
    internal val immutableSessionSettings: Map<String, String> = sessionSettings.toMap()

    /** mutable collection과 분리된 DB role snapshot입니다. */
    internal val immutableSessionDbRoles: List<String> = sessionDbRoles.toList()

    companion object {
        /** #865 connection options에서 diagnostics metadata를 복사합니다. */
        fun from(
            options: ClickHouseV2Options,
            listener: ClickHouseQueryListener? = null,
            sink: ClickHouseQueryDiagnosticsSink? = null,
        ): ClickHouseQueryDiagnosticsConfig = ClickHouseQueryDiagnosticsConfig(
            queryId = options.queryId,
            logComment = options.logComment,
            clientName = options.clientName,
            sessionSettings = options.serverSettings,
            sessionDbRoles = options.sessionDbRoles,
            sessionTimezone = options.sessionTimezone,
            listener = listener,
            sink = sink,
        )
    }
}

/** lifecycle event와 terminal 여부를 함께 전달하는 값입니다. */
data class ClickHouseQueryEvent(
    val kind: ClickHouseQueryEventKind,
    val diagnostics: ClickHouseQueryDiagnostics,
    val isTerminal: Boolean,
)

private fun validateText(name: String, value: String, allowBlank: Boolean) {
    if (!allowBlank) require(value.isNotBlank()) { "${name}은 공백일 수 없습니다." }
    require(value.none(Char::isISOControl)) { "${name}에는 제어 문자를 사용할 수 없습니다." }
}
