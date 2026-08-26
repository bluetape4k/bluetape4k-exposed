package io.bluetape4k.spring.data.exposed.common.mapping

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.dao.EntityClass
import org.springframework.data.mapping.PersistentEntity

/**
 * Exposed DAO Entity를 Spring Data PersistentEntity로 표현합니다.
 *
 * JDBC와 R2DBC 어댑터는 이 계약으로 companion object의 [EntityClass]와 [Table]을
 * 조회하고, 각 실행 방식의 저장소 로직만 별도로 소유합니다.
 */
interface ExposedPersistentEntity<T: Any>: PersistentEntity<T, ExposedPersistentProperty> {

    /** 이 Entity의 companion object에서 추출한 [EntityClass] 인스턴스입니다. */
    fun getEntityClass(): EntityClass<*, *>?

    /** 이 Entity가 매핑되는 [Table] 인스턴스입니다. */
    fun getTable(): Table?
}
