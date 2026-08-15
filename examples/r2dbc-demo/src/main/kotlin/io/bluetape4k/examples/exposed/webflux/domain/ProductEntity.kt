package io.bluetape4k.examples.exposed.webflux.domain

import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import java.io.Serializable

object Products: LongIdTable("webflux_products") {
    val name = varchar("name", 255)
    val price = decimal("price", 10, 2)
    val stock = integer("stock").default(0)
}

/**
 * HTTP 전송에 사용하는 예제 레코드입니다.
 *
 * JSON HTTP 경계와 별개로 JVM DTO의 `Serializable` 계약을 유지하므로
 * 명시적인 `serialVersionUID = 1L`을 사용합니다. 직렬화된 데이터에 영향을
 * 주는 필드 변경은 호환성과 마이그레이션을 먼저 검토해야 합니다.
 */
data class ProductRecord(
    val id: Long? = null,
    val name: String,
    val price: java.math.BigDecimal = java.math.BigDecimal.ZERO,
    val stock: Int = 0,
): Serializable {

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
