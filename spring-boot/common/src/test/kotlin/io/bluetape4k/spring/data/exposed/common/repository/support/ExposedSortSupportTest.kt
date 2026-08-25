package io.bluetape4k.spring.data.exposed.common.repository.support

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldHaveSize
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.junit.jupiter.api.Test
import org.springframework.data.domain.Sort

class ExposedSortSupportTest {

    private object TestTable: LongIdTable("test_items") {
        val name = varchar("name", 255)
        val createdAt = varchar("created_at", 64)
        val age = integer("age")
    }

    @Test
    fun `property names convert between camelCase and snake_case`() {
        toSnakeCase("createdAt") shouldBeEqualTo "created_at"
        toSnakeCase("myLongPropertyName") shouldBeEqualTo "my_long_property_name"
        toCamelCase("created_at") shouldBeEqualTo "createdAt"
    }

    @Test
    fun `sort maps direction and camelCase property to Exposed order`() {
        val orderBy = Sort.by(Sort.Direction.ASC, "createdAt").toExposedOrderBy(TestTable)

        orderBy shouldHaveSize 1
        orderBy[0].first shouldBeEqualTo TestTable.createdAt
        orderBy[0].second shouldBeEqualTo SortOrder.ASC
    }

    @Test
    fun `unknown and unsorted values produce no order`() {
        Sort.by(Sort.Direction.DESC, "missing").toExposedOrderBy(TestTable) shouldHaveSize 0
        Sort.unsorted().toExposedOrderBy(TestTable) shouldHaveSize 0
    }
}
