package io.bluetape4k.spring.data.exposed.jdbc.mapping

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.dao.EntityClass
import org.springframework.data.mapping.PersistentEntity

/**
 * JDBC 저장소의 기존 PersistentEntity 계약입니다.
 *
 * @deprecated common.mapping.ExposedPersistentEntity를 사용하십시오.
 */
@Deprecated("common.mapping.ExposedPersistentEntity를 사용하십시오.")
interface ExposedPersistentEntity<T: Any>: PersistentEntity<T, ExposedPersistentProperty> {

    /** 이 Entity의 companion object에서 추출한 [EntityClass] 인스턴스입니다. */
    fun getEntityClass(): EntityClass<*, *>?

    /** 이 Entity가 매핑되는 [Table] 인스턴스입니다. */
    fun getTable(): Table?
}
