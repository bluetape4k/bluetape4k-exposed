package io.bluetape4k.exposed.examples.modulith.shipping

import io.bluetape4k.exposed.examples.modulith.orders.events.OrderAcceptedEvent
import io.bluetape4k.exposed.examples.modulith.shipping.internal.ShippingReservationRepository
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component

@Component
class ShippingReservationHandler(
    private val shippingReservationRepository: ShippingReservationRepository,
) {

    @ApplicationModuleListener(id = "shipping.reserve-order")
    fun on(event: OrderAcceptedEvent) {
        shippingReservationRepository.reserve(event)
    }
}
