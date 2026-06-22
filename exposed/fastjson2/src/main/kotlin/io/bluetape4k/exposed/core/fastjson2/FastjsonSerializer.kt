package io.bluetape4k.exposed.core.fastjson2

import io.bluetape4k.fastjson2.FastjsonSerializer

/**
 * Exposed Fastjson2 확장에서 기본으로 사용하는 [FastjsonSerializer] 인스턴스입니다.
 *
 * ## 동작/계약
 * - `bluetape4k-fastjson2`의 [FastjsonSerializer.Default]를 재노출합니다.
 * - 생성된 인스턴스는 프로세스 내에서 재사용됩니다.
 * - Jackson 계열 Exposed 모듈의 `DefaultJacksonSerializer`와 같은 기본 진입점 역할을 합니다.
 *
 * ```kotlin
 * val serializer = DefaultFastjsonSerializer
 * val same = serializer === DefaultFastjsonSerializer
 * // same == true
 * ```
 */
val DefaultFastjsonSerializer: FastjsonSerializer
    get() = FastjsonSerializer.Default
