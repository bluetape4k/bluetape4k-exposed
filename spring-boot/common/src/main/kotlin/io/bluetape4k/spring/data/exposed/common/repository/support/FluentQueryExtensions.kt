package io.bluetape4k.spring.data.exposed.common.repository.support

import org.springframework.data.repository.query.FluentQuery

inline fun <reified R: Any> FluentQuery<*>.cast(): FluentQuery<R> =
    `as`(R::class.java)
