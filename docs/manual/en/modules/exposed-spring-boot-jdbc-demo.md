---
manualId: "exposed-spring-boot-jdbc-demo"
id: "exposed-spring-boot-jdbc-demo"
title: "Spring Boot JDBC Demo"
locale: "en"
kind: "example"
gradlePath: ":exposed-spring-boot-jdbc-demo"
sourceDir: "examples/jdbc-demo"
releaseRef: "1.12.1"
artifact: null
---

# Spring Boot JDBC Demo

> Run an Exposed DAO repository behind a Spring MVC API, with transaction ownership kept visible.

## What you learn {#problem}

This demo connects `@ExposedEntity`, `ExposedJdbcRepository`, Spring MVC, H2, and Spring Boot 4. It is especially useful for seeing where Exposed DAO entities must be converted to DTOs: every controller operation enters an Exposed `transaction {}` block and calls `toRecord()` before the entity leaves that block.

The demo is not a pure “zero-configuration” example. `ExposedConfig` explicitly creates `SpringTransactionManager`; the integration module supplies repository discovery and implementation.

## Prerequisites {#prerequisites}

- JDK 21+
- the repository Gradle wrapper
- no Docker for the default path; both tests and `bootRun` use in-memory H2

## Run {#run}

Run the tests first:

```bash
./gradlew :exposed-spring-boot-jdbc-demo:test
```

Then start the HTTP application:

```bash
./gradlew :exposed-spring-boot-jdbc-demo:bootRun
```

The application listens on port `8080` by default.

## Expected result {#expected-result}

At startup, `DataInitializer` asks `MigrationUtils` for the statements required by the `Products` table and inserts three products when the table is empty. These calls should return JSON or the stated status:

```bash
curl http://localhost:8080/products
curl 'http://localhost:8080/products/search?name=Kotlin Programming Book'
curl -i http://localhost:8080/products/999999
```

The list initially contains three rows, the exact-name search returns the matching product, and the missing ID returns `404`. Controller tests also cover create, update, and delete. Repository tests cover CRUD, paging, sorting, Exposed DSL predicates, and the derived methods `findByName` and `findByPriceLessThan`.

## Failure diagnosis {#failures}

- Context fails to create `springTransactionManager`: check that one `DataSource` is available and that an application override does not conflict with `ExposedConfig`.
- `No transaction in context`: keep DAO access and `toRecord()` inside `transaction {}`; do not serialize a live DAO entity after the block closes.
- Search returns nothing: `findByName` is an exact derived query, not a substring search.
- Data disappears after restart: the default URL is `jdbc:h2:mem:mvcdb`; persistence across processes is not part of this demo.
- Schema startup fails: compare the `Products` table with `MigrationUtils` output and the checked-in migration before moving to a production database.

## Next route {#next}

Read [Spring Boot JDBC integration](bluetape4k-exposed-spring-boot-jdbc.md) for repository factory and transaction-manager ownership, then [JDBC repository patterns](bluetape4k-exposed-jdbc/repository-patterns.md) for paging, audit, and soft-delete boundaries. The [Exposed workshop](https://github.com/bluetape4k/exposed-workshop) expands the same JDBC path into schema, transaction, and repository exercises.

## When to use it {#when-to-use}

Use this example before adopting the Spring integration when your application is blocking Spring MVC and uses Exposed DAO entities behind repository interfaces. It is also a compact regression fixture for repository method parsing. Do not copy it as-is when the application needs R2DBC, durable migration policy, or a service-layer transaction spanning several repository calls.

## Coordinates {#coordinates}

The demo publishes no artifact. A consumer application imports the central BOM and the JDBC integration without an individual version:

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-spring-boot-jdbc")
}
```

## Core concepts {#concepts}

`ProductEntity` is transaction-bound; `ProductRecord` is safe to return over HTTP. `ProductJdbcRepository` extends `ExposedJdbcRepository<ProductEntity, Long>` and adds bounded PartTree methods. The controller currently owns each Exposed transaction, while Spring owns the `DataSource` and the explicitly declared `SpringTransactionManager` bean.

This separation is intentional learning material. In a larger application, move a multi-operation transaction to a service instead of nesting or scattering controller transactions.

## Quick start {#quick-start}

1. Run the test task and inspect `ProductControllerTest` plus `ProductJdbcRepositoryTest`.
2. Start `bootRun` and call `GET /products`.
3. Put a breakpoint in `ProductController.findById` and verify that `toRecord()` executes inside `transaction {}`.
4. Add one derived query to `ProductJdbcRepository` and a matching repository test before using it from the controller.

## API by task {#api-by-task}

| Task | Released source to follow |
| --- | --- |
| Repository contract | `ProductJdbcRepository` |
| DAO/table mapping | `ProductEntity` and `Products` |
| DTO conversion and HTTP status | `ProductController` |
| DataSource transaction manager | `ExposedConfig` |
| Schema initialization and seed data | `DataInitializer` |
| MVC behavior | `ProductControllerTest` |
| Repository behavior | `ProductJdbcRepositoryTest` |

## Recommended patterns {#patterns}

- Return DTOs, not Exposed DAO entities, from the HTTP boundary.
- Put business transactions in a Spring-managed service when several operations form one unit of work.
- Treat derived query names as a supported, tested subset rather than assuming every Spring Data keyword works.
- Replace startup-generated schema changes with reviewed migrations in production.
- Keep blocking JDBC work off reactive or event-loop threads.

## Integrations {#integrations}

The demo uses Spring Boot Web and JDBC starters, the bluetape4k Exposed Spring Boot JDBC module, Exposed DAO/JDBC/migration modules, Jackson 3, and H2. It uses neither R2DBC nor an external container.

## Configuration {#configuration}

`application.yml` configures `jdbc:h2:mem:mvcdb`, user `sa`, and debug logging for bluetape4k and Exposed. `spring.exposed.generate-ddl: true` appears in the demo configuration, while startup schema work is performed explicitly by `DataInitializer` with `MigrationUtils`. For production, choose one reviewed migration authority and remove ambiguous ownership.

## Operations {#operations}

Separate context-start failures, schema initialization, transaction failures, derived-query parsing, and HTTP serialization in logs. H2 success proves the application wiring, not PostgreSQL/MySQL compatibility. When changing databases, rerun repository behavior and migration validation against the selected engine.

## Testing {#testing}

`ProductControllerTest` starts a real random-port Spring application and calls it with `RestClient`. `ProductJdbcRepositoryTest` checks the repository against H2 and clears the table between tests. Add a service-level rollback test before moving a multi-step business transaction out of the controller.

## Workshops and learning path {#workshops}

Follow the code in this order: `ExposedConfig` → `ProductEntity` → `ProductJdbcRepository` → `ProductController` → the two test classes. The module manual explains what the integration creates; the workshop provides broader practice. This demo remains the runnable reference that ties those two layers together.

## Limitations {#limitations}

The demo uses one in-memory database, startup schema adjustment, controller-owned Exposed transactions, and no authentication. It is not a production migration strategy, connection-pool sizing guide, or proof that every Spring Data derived-query keyword is supported.

<!-- release-readme-diagrams:start -->
## Release diagrams {#release-diagrams}

These diagrams are loaded directly from README assets published with the `1.12.1` release and pinned to its immutable commit. They describe this manual's released structure and runtime flows, not later Snapshot changes. Select a preview to open the SVG at the same release commit.

### Spring Boot JDBC demo structure diagram

[![Spring Boot JDBC demo structure diagram](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/spring-boot-exposed-jdbc-demo-diagram-01.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/spring-boot-exposed-jdbc-demo-diagram-01.svg)

_Release README: [`examples/jdbc-demo/README.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/examples/jdbc-demo/README.md)_

### Spring Boot JDBC demo request transaction flow diagram

[![Spring Boot JDBC demo request transaction flow diagram](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/spring-boot-exposed-jdbc-demo-diagram-02.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/spring-boot-exposed-jdbc-demo-diagram-02.svg)

_Release README: [`examples/jdbc-demo/README.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/examples/jdbc-demo/README.md)_

<!-- release-readme-diagrams:end -->

## Sources {#sources}

- [Demo overview](../../../../examples/jdbc-demo/README.md)
- [Transaction configuration](../../../../examples/jdbc-demo/src/main/kotlin/io/bluetape4k/examples/exposed/mvc/config/ExposedConfig.kt)
- [Controller transaction and DTO boundary](../../../../examples/jdbc-demo/src/main/kotlin/io/bluetape4k/examples/exposed/mvc/controller/ProductController.kt)
- [Repository contract](../../../../examples/jdbc-demo/src/main/kotlin/io/bluetape4k/examples/exposed/mvc/repository/ProductJdbcRepository.kt)
- [Controller tests](../../../../examples/jdbc-demo/src/test/kotlin/io/bluetape4k/examples/exposed/mvc/ProductControllerTest.kt)
