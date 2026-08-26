package io.bluetape4k.examples.exposed.mvc.config

import io.bluetape4k.examples.exposed.mvc.domain.ProductEntity
import io.bluetape4k.examples.exposed.mvc.domain.Products
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.migration.jdbc.MigrationUtils
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
import java.math.BigDecimal

@Component
class DataInitializer: ApplicationRunner {

    override fun run(args: ApplicationArguments) {
        transaction {
            MigrationUtils.statementsRequiredForDatabaseMigration(Products).forEach { exec(it) }

            if (ProductEntity.count() == 0L) {
                ProductEntity.new {
                    name = "Kotlin Programming Book"
                    price = BigDecimal("39.99")
                    stock = FIRST_PRODUCT_STOCK
                }
                ProductEntity.new {
                    name = "Spring Boot Guide"
                    price = BigDecimal("49.99")
                    stock = SECOND_PRODUCT_STOCK
                }
                ProductEntity.new {
                    name = "Exposed ORM Tutorial"
                    price = BigDecimal("29.99")
                    stock = THIRD_PRODUCT_STOCK
                }
            }
        }
    }

    private companion object {
        const val FIRST_PRODUCT_STOCK = 100
        const val SECOND_PRODUCT_STOCK = 50
        const val THIRD_PRODUCT_STOCK = 200
    }
}
