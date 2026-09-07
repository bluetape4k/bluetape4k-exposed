package io.bluetape4k.exposed.tenant.jdbc

import org.jetbrains.exposed.v1.jdbc.Database
import javax.sql.DataSource

/**
 * 하나의 tenant가 소유하는 JDBC 연결 공급원과 Exposed database의 읽기 전용 묶음입니다.
 *
 * [dataSource]와 [database]의 종료는 이 resource를 반환한
 * [TenantJdbcResourceRegistry]가 담당합니다. 이 view는 lifecycle lease가 아니므로 호출자는
 * registry의 종료 전에 새 요청을 차단하고 진행 중인 요청 처리를 끝내야 합니다.
 */
interface TenantJdbcResource {
    /** tenant 전용 JDBC 연결 공급원입니다. 직접 dispose하지 않습니다. */
    val dataSource: DataSource

    /** [dataSource]에서 등록된 Exposed database handle입니다. readiness 보장은 포함하지 않습니다. */
    val database: Database
}
