package io.bluetape4k.exposed.tenant.jdbc

/**
 * registry에 등록되지 않은 tenant를 조회할 때 발생하는 예외입니다.
 *
 * 예외 메시지에는 tenant key를 포함하지 않아 민감한 식별자가 오류 경로에 복제되지
 * 않도록 합니다.
 */
class UnknownTenantJdbcResourceException: NoSuchElementException("Unknown tenant JDBC resource.")
