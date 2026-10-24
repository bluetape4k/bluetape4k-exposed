package io.bluetape4k.spring.data.exposed.common.repository.support

import org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery


inline fun <reified R: Any> FetchableFluentQuery<*>.cast(): FetchableFluentQuery<R> =
    `as`(R::class.java)
