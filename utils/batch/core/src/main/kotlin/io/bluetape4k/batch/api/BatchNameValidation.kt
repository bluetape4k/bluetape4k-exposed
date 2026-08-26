package io.bluetape4k.batch.api

/**
 * Job/Step 이름의 저장·로그 경계를 검증한다.
 *
 * 원문 이름은 오류 메시지에 다시 삽입하지 않는다. 제어 문자는 로그 위조와
 * 운영 추적 혼선을 만들 수 있으므로 허용하지 않는다.
 */
fun String.requireValidBatchName(parameterName: String): String {
    require(isNotBlank()) {
        "$parameterName must not be blank"
    }
    require(none(Char::isISOControl)) {
        "$parameterName contains an unsupported control character"
    }
    return this
}
