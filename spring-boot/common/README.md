# exposed-spring-boot-common

English | [한국어](./README.ko.md)

Spring Data Commons SPI for the bluetape4k Exposed JDBC and R2DBC adapters.
This module contains backend-neutral annotations, mapping metadata, derived-query planning,
and `Sort` conversion. It does not open database connections, start transactions, or depend
on a JDBC/R2DBC adapter.

## Installation

```kotlin
dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:<spring-boot-version>"))
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<bluetape4k-version>"))
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-spring-boot-common")
}
```

## Public packages

- `io.bluetape4k.spring.data.exposed.common.annotation` — `@ExposedEntity` and `@Query`.
- `io.bluetape4k.spring.data.exposed.common.mapping` — Spring Data mapping metadata for Exposed entities.
- `io.bluetape4k.spring.data.exposed.common.repository.query` — PartTree query planning and parameter metadata.
- `io.bluetape4k.spring.data.exposed.common.repository.support` — `Sort.toExposedOrderBy` conversion.

JDBC execution, transaction management, and `ExposedEntityInformation` remain in
`bluetape4k-exposed-spring-boot-jdbc`. Suspend execution and coroutine lifecycle remain in
`bluetape4k-exposed-spring-boot-r2dbc`.

## Dependency boundary

Applications that use only R2DBC can depend on the common module and the R2DBC adapter without
bringing the JDBC adapter or `spring-jdbc` into the runtime graph. Existing JDBC package symbols
are retained by the JDBC artifact as deprecated compatibility facades; new code should import
the common packages above.
