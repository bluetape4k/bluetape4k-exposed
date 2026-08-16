package io.bluetape4k.spring.data.exposed.r2dbc.repository.support

import org.springframework.data.domain.ExampleMatcher
import kotlin.reflect.KClass

/** Resolver가 한 번 읽은 QBE property와 detached bind 값을 보관하는 immutable snapshot입니다. */
internal data class R2dbcExamplePropertySnapshot(
    val property: String,
    val value: Any?,
    val stringMatcher: ExampleMatcher.StringMatcher,
    val ignoreCase: Boolean,
    val includeNull: Boolean,
)

/** 원본 [org.springframework.data.domain.Example]이나 mutable probe를 참조하지 않는 snapshot입니다. */
internal data class R2dbcExampleSnapshot(
    val expectedDomainType: KClass<*>,
    val matchingAll: Boolean,
    val nullHandler: ExampleMatcher.NullHandler,
    val ignoredPaths: Set<String>,
    val properties: List<R2dbcExamplePropertySnapshot>,
) {
    init {
        require(ignoredPaths.none { it.isBlank() }) { "QBE ignored property must not be blank." }
        require(properties.map { it.property }.distinct().size == properties.size) {
            "QBE snapshot properties must be unique."
        }
    }
}
