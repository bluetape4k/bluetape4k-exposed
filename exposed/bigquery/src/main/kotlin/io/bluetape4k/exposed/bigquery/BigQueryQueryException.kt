package io.bluetape4k.exposed.bigquery

/**
 * BigQuery REST API 쿼리 실행 중 발생한 오류를 나타내는 예외.
 *
 * [BigQueryContext.runRawQuery] 또는 페이지네이션 과정에서 BigQuery 서버가 오류를 반환할 때 던져집니다.
 *
 * 기존 [RuntimeException]을 직접 던지면 호출자가 BigQuery 오류인지 다른 런타임 오류인지 구분하기
 * 어려웠습니다. 전용 예외 타입을 도입하여 `catch (e: BigQueryQueryException)` 으로 BigQuery 오류만
 * 선택적으로 처리할 수 있도록 개선했습니다.
 *
 * ```kotlin
 * try {
 *     context.runRawQuery("INVALID SQL")
 * } catch (e: BigQueryQueryException) {
 *     log.error { "BigQuery 쿼리 오류: ${e.message}" }
 * }
 * ```
 *
 * 메시지는 원문 SQL, SQL 리터럴, 서버 오류 message를 포함하지 않습니다. 대신 statement kind,
 * SQL SHA-256 fingerprint, 안전한 reason과 provider job ID만 진단 정보로 제공합니다.
 *
 * @param message redacted BigQuery 오류 진단 메시지
 * @param cause 원인 예외 (있는 경우)
 */
class BigQueryQueryException(
    message: String,
    cause: Throwable? = null,
): RuntimeException(message, cause)
