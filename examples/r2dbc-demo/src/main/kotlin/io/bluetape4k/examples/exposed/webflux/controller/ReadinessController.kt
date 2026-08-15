package io.bluetape4k.examples.exposed.webflux.controller

import io.bluetape4k.examples.exposed.webflux.config.DataInitializer
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.io.Serializable

/**
 * R2DBC 데모의 초기화 readiness를 외부에서 확인하는 endpoint이다.
 */
@RestController
class ReadinessController(
    private val dataInitializer: DataInitializer,
) {

    /** 초기화 완료 여부에 따라 HTTP 200 또는 503을 반환한다. */
    @GetMapping("/readyz")
    fun readiness(): ResponseEntity<ReadinessResponse> {
        return if (dataInitializer.isReady) {
            ResponseEntity.ok(ReadinessResponse(status = "UP"))
        } else {
            ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ReadinessResponse(status = "DOWN"))
        }
    }
}

/** readiness endpoint가 반환하는 최소 상태 표현이다. */
data class ReadinessResponse(
    val status: String,
): Serializable {

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
