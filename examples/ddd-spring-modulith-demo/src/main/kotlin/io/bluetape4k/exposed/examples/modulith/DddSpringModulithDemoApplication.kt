package io.bluetape4k.exposed.examples.modulith

import io.bluetape4k.exposed.examples.modulith.orders.events.OrderAcceptedEvent
import io.bluetape4k.exposed.examples.modulith.orders.OrderId
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.spring7.transaction.SpringTransactionManager
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.modulith.events.core.EventSerializer
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import javax.sql.DataSource

@SpringBootApplication
class DddSpringModulithDemoApplication {

    @Bean("springTransactionManager")
    fun springTransactionManager(dataSource: DataSource): PlatformTransactionManager =
        SpringTransactionManager(dataSource, DatabaseConfig {}, false)

    @Bean
    fun transactionTemplate(
        springTransactionManager: PlatformTransactionManager,
    ): TransactionTemplate =
        TransactionTemplate(springTransactionManager)

    @Bean
    fun orderAcceptedEventSerializer(): EventSerializer =
        OrderAcceptedEventSerializer()
}

fun main(args: Array<String>) {
    runApplication<DddSpringModulithDemoApplication>(*args)
}

class OrderAcceptedEventSerializer : EventSerializer {

    override fun serialize(event: Any): Any =
        when (event) {
            is OrderAcceptedEvent -> event.toDeterministicJson()
            else -> throw IllegalArgumentException("Unsupported event type: ${event::class.java.name}")
        }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> deserialize(serialized: Any, type: Class<T>): T {
        require(type == OrderAcceptedEvent::class.java) {
            "Unsupported event type: ${type.name}"
        }

        val fields = parseJsonObject(serialized.toString())
        return OrderAcceptedEvent(
            aggregateId = OrderId(fields.getValue("aggregateId")),
            eventId = fields.getValue("eventId"),
            occurredAt = Instant.parse(fields.getValue("occurredAt")),
        ) as T
    }

    private fun OrderAcceptedEvent.toDeterministicJson(): String =
        buildString {
            append('{')
            append("\"aggregateId\":\"").append(aggregateId.value.escapeJson()).append("\",")
            append("\"eventId\":\"").append(eventId.escapeJson()).append("\",")
            append("\"occurredAt\":\"").append(occurredAt.toString().escapeJson()).append("\"")
            append('}')
        }

    private fun parseJsonObject(value: String): Map<String, String> {
        val pattern = """"([^"]+)":"((?:\\.|[^"\\])*)"""".toRegex()
        return pattern.findAll(value)
            .associate { match ->
                match.groupValues[1] to match.groupValues[2].unescapeJson()
            }
            .also { fields ->
                require(fields.keys == setOf("aggregateId", "eventId", "occurredAt")) {
                    "Unexpected OrderAcceptedEvent payload fields: ${fields.keys}"
                }
            }
    }

    private fun String.escapeJson(): String =
        buildString(length) {
            this@escapeJson.forEach { char ->
                when (char) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    else -> append(char)
                }
            }
        }

    private fun String.unescapeJson(): String =
        replace("\\\"", "\"").replace("\\\\", "\\")
}
