---
manualId: "bluetape4k-exposed-spring-boot-jdbc"
id: "bluetape4k-exposed-spring-boot-jdbc"
title: "Exposed Spring Boot JDBC Integration"
locale: "en"
kind: "library"
gradlePath: ":bluetape4k-exposed-spring-boot-jdbc"
sourceDir: "spring-boot/jdbc"
releaseRef: "1.12.1"
artifact: io.github.bluetape4k.exposed:bluetape4k-exposed-spring-boot-jdbc
---

# Exposed Spring Boot JDBC Integration

> Spring Data repositories for Exposed DAO entities, backed by an application `DataSource` and an explicit transaction manager choice.

## Problem {#problem}

This module maps classes marked with `@ExposedEntity` into Spring Data repository metadata and creates repository proxies for Exposed DAO entities. The auto-configuration is active when Exposed `EntityClass` is present. With a caller-provided `DataSource`, it calls `Database.connect(dataSource)` and creates a bean named `springTransactionManager` only when a bean with that name is missing.

![Spring Boot JDBC auto-configuration](../../assets/spring/jdbc-auto-configuration.png)

## When to use it {#when-to-use}

Use it when a Spring Boot JDBC application models persistence with Exposed DAO entities and wants Spring Data repository scanning, CRUD, paging/sorting, query-by-example, and the supported derived-query subset. Use the lower-level Exposed JDBC repositories when entities are not DAO `Entity` types or when repository proxy conventions do not fit the application.

## Coordinates {#coordinates}

Import the ecosystem BOM and omit the module version:

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-spring-boot-jdbc")
}
```

The shared Spring Data annotations, mapping metadata, query planning, and sort conversion live in
`bluetape4k-exposed-spring-boot-common`. New source may import `io.bluetape4k.spring.data.exposed.common.*`.
The historical `io.bluetape4k.spring.data.exposed.jdbc.annotation`, `mapping`, `repository.query`, and
`repository.support` symbols remain in this artifact as deprecated binary facades, so existing callers can
migrate incrementally without a descriptor break.

```kotlin
import io.bluetape4k.spring.data.exposed.common.annotation.ExposedEntity
import io.bluetape4k.spring.data.exposed.common.annotation.Query
```

## Core concepts {#concepts}

An `ExposedJdbcRepository<E, ID>` works with an Exposed DAO `Entity` and its `IdTable`. DAO identity, the entity cache, and change tracking live in the current Exposed transaction. Creating or mutating a DAO entity outside a transaction is therefore invalid. Spring's service transaction should own the business unit of work; repositories perform entity operations inside that boundary.

## Quick start {#quick-start}

Mark the DAO entity with `@ExposedEntity`, define a repository that supplies `table` and `extractId`, and enable scanning:

```kotlin
@EnableExposedJdbcRepositories(basePackageClasses = [MemberRepository::class])
class PersistenceConfiguration

interface MemberRepository : ExposedJdbcRepository<Member, Long> {
    override val table get() = Members
    override fun extractId(entity: Member): Long? = entity.id.value.takeIf { it != 0L }
}
```

Provide the application `DataSource`. If the application does not define `springTransactionManager`, auto-configuration connects Exposed to that datasource and supplies the named manager. Put the business operation on a Spring-managed service method.

## API by task {#api-by-task}

- Use `@EnableExposedJdbcRepositories` to set repository packages, query strategy, and `transactionManagerRef`.
- Extend `ExposedJdbcRepository` for list CRUD, paging/sorting, query-by-example, and `findAll`/`count`/`exists` with an Exposed `Op<Boolean>`.
- Use supported method-name queries through `PartTreeExposedQuery`.
- Create new DAO entities and change tracked properties only inside the service transaction.
- Use an explicit repository implementation or Exposed DSL when a query is outside the derived-query subset.

## Recommended patterns {#patterns}

Place the business transaction at the service layer so several repository calls and DAO mutations share one entity cache and commit decision. Return detached DTOs across asynchronous or remote boundaries rather than retaining DAO entities after the transaction. Keep repository scanning narrow and make the transaction manager name explicit in multi-datasource applications.

## Integrations {#integrations}

`ExposedSpringDataAutoConfiguration` supplies the mapping context and the conditional named transaction manager. `@EnableExposedJdbcRepositories` imports the registrar and uses `ExposedJdbcRepositoryFactoryBean`. Repository proxies use the configured `transactionManagerRef`; they do not discover the correct datasource from a DAO entity at runtime.

## Configuration {#configuration}

The caller configures and owns the `DataSource`, driver, pool, credentials, validation, and shutdown. The default repository manager name is `springTransactionManager`. With multiple transaction managers, set `transactionManagerRef` and use a service qualifier consistently. A bean with the default name suppresses the auto-configured manager, allowing the application or demo to replace it deliberately.

## Failure modes {#failures}

- Repository entity is not recognized: verify the DAO class has `@ExposedEntity` and exposes the required `EntityClass`/table mapping.
- `springTransactionManager` is absent: verify a `DataSource` bean exists or provide the named manager explicitly.
- Wrong database is updated: set `transactionManagerRef` and the service transaction qualifier to the same manager.
- DAO entity access fails after return: map it to a DTO before leaving the transaction.
- Derived method cannot be parsed: stay within the bounded `PartTreeExposedQuery` support or provide an explicit query/implementation.

## Operations {#operations}

Observe datasource acquisition time, active/idle connections, transaction duration, rollback count, query latency, and pool shutdown. Log the selected transaction manager and datasource identity at startup in multi-datasource services. Do not treat repository proxy creation as a database readiness check.

## Testing {#testing}

Use the production database family through Testcontainers. Verify repository scanning, DAO creation and dirty-property flush inside a transaction, service-level rollback across multiple repository calls, paging/sorting, each derived-query form actually used, and the selected manager in a multi-manager context. Include a test where a custom `springTransactionManager` overrides auto-configuration.

## Workshops and learning path {#workshops}

Run the [Spring Boot JDBC example](exposed-spring-boot-jdbc-demo.md), then read [transaction boundaries](../guides/transaction-boundaries.md) and [JDBC repository patterns](bluetape4k-exposed-jdbc/repository-patterns.md). The [Exposed workshop](https://github.com/bluetape4k/exposed-workshop) expands the service and repository design.

## Limitations {#limitations}

The module does not create a `DataSource`, choose among multiple transaction managers, or support every Spring Data derived-query operator. It is DAO-entity oriented: identity and change tracking require an active Exposed transaction. The demo's explicit transaction-manager override is a supported configuration, so applications must not assume the default manager is always auto-created.

<!-- release-readme-diagrams:start -->
## Release diagrams {#release-diagrams}

These diagrams are loaded directly from README assets published with the `1.12.1` release and pinned to its immutable commit. They describe this manual's released structure and runtime flows, not later Snapshot changes. Select a preview to open the SVG at the same release commit.

### Spring Boot Exposed JDBC repository wiring diagram

[![Spring Boot Exposed JDBC repository wiring diagram](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/spring-boot-exposed-jdbc-diagram-01.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/spring-boot-exposed-jdbc-diagram-01.svg)

_Release README: [`spring-boot/jdbc/README.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/spring-boot/jdbc/README.md)_

### Spring Boot Exposed JDBC query resolution flow diagram

[![Spring Boot Exposed JDBC query resolution flow diagram](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/spring-boot-exposed-jdbc-diagram-02.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/spring-boot-exposed-jdbc-diagram-02.svg)

_Release README: [`spring-boot/jdbc/README.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/spring-boot/jdbc/README.md)_

<!-- release-readme-diagrams:end -->

## Sources {#sources}

- [`ExposedSpringDataAutoConfiguration.kt`](../../../../spring-boot/jdbc/src/main/kotlin/io/bluetape4k/spring/data/exposed/jdbc/config/ExposedSpringDataAutoConfiguration.kt)
- [`ExposedEntity.kt`](../../../../spring-boot/jdbc/src/main/kotlin/io/bluetape4k/spring/data/exposed/jdbc/annotation/ExposedEntity.kt)
- [`EnableExposedJdbcRepositories.kt`](../../../../spring-boot/jdbc/src/main/kotlin/io/bluetape4k/spring/data/exposed/jdbc/repository/config/EnableExposedJdbcRepositories.kt)
- [`ExposedJdbcRepository.kt`](../../../../spring-boot/jdbc/src/main/kotlin/io/bluetape4k/spring/data/exposed/jdbc/repository/ExposedJdbcRepository.kt)
- [`SimpleExposedJdbcRepository.kt`](../../../../spring-boot/jdbc/src/main/kotlin/io/bluetape4k/spring/data/exposed/jdbc/repository/support/SimpleExposedJdbcRepository.kt)
- [`PartTreeExposedQuery.kt`](../../../../spring-boot/jdbc/src/main/kotlin/io/bluetape4k/spring/data/exposed/jdbc/repository/query/PartTreeExposedQuery.kt)
- [`ExposedConfig.kt` demo override](../../../../examples/jdbc-demo/src/main/kotlin/io/bluetape4k/examples/exposed/mvc/config/ExposedConfig.kt)
