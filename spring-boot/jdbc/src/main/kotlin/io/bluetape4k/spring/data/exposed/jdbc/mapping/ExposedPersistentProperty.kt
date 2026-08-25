package io.bluetape4k.spring.data.exposed.jdbc.mapping

import org.jetbrains.exposed.v1.core.Column
import org.springframework.data.mapping.PersistentProperty

/**
 * JDBC 저장소의 기존 PersistentProperty 계약입니다.
 *
 * @deprecated common.mapping.ExposedPersistentProperty를 사용하십시오.
 */
@Deprecated("common.mapping.ExposedPersistentProperty를 사용하십시오.")
interface ExposedPersistentProperty: PersistentProperty<ExposedPersistentProperty> {

    /** 이 프로퍼티에 대응하는 Exposed [Column] 인스턴스입니다. */
    fun getColumn(): Column<*>?
}
