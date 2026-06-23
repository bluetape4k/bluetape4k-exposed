# exposed-spring-modulith

English | [한국어](./README.ko.md)

Spring Boot auto-configuration for a JDBC-only Spring Modulith `EventPublicationRepository` backed by Exposed DSL.
It stores Spring Modulith event publications in the application's JDBC database while reusing the Exposed
`springTransactionManager`.

The module intentionally uses the `exposed-spring-modulith` artifact shape
instead of `spring-modulith-exposed` so it does not look like an official Spring
Modulith store module.

## Dependency

```kotlin
dependencies {
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-spring-boot-jdbc:1.10.0")
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-spring-modulith:1.10.0")
}
```

## Runtime Wiring

![Spring Modulith Exposed JDBC wiring diagram](../../docs/images/readme-diagrams/spring-boot-exposed-spring-modulith-diagram-01.png)

The repository uses the same `DataSource` and Exposed
`springTransactionManager` as the application. It does not provide an R2DBC or
`suspend` implementation because Spring Modulith 2.x exposes a synchronous
`EventPublicationRepository` SPI.

## Publication Lifecycle

![Spring Modulith publication lifecycle sequence diagram](../../docs/images/readme-diagrams/spring-boot-exposed-spring-modulith-sequence-01.png)

`create(publication)` inserts an active publication row. Listener execution can move the row through
`PROCESSING`, `FAILED`, and `RESUBMITTED` states, and `markCompleted(...)` applies the configured completion
mode:

- `UPDATE`: keep the active row and set `COMPLETED` plus `COMPLETION_DATE`.
- `DELETE`: remove the active row after successful completion.
- `ARCHIVE`: copy the row to `EVENT_PUBLICATION_ARCHIVE` with a savepoint, then delete the active row.

## Unloadable Event Types

Rows whose `EVENT_TYPE` can no longer be loaded remain visible through incomplete, failed, and status queries.
This keeps undelivered publications available to operators after package renames, dependency drift, or classpath
mistakes. Accessing `publication.event` for those rows throws `UnloadableEventPublicationException` with the
publication id, listener id, and event type.

Restore the event class on the classpath, migrate the event type and payload, or explicitly delete/resubmit the row
after correcting the stored publication. Do not treat an unloadable event type as a delivered publication.

## Configuration

```yaml
bluetape4k:
  spring:
    modulith:
      exposed:
        table-name: EVENT_PUBLICATION
        archive-table-name: EVENT_PUBLICATION_ARCHIVE
        completion-mode: update
        initialize-schema: false
```

`completion-mode` supports Spring Modulith `UPDATE`, `DELETE`, and `ARCHIVE`.
Use Flyway or Liquibase for production schema management. `initialize-schema`
uses Exposed `SchemaUtils` and is intended for tests or small local
applications.

The default table follows the Spring Modulith JDBC schema shape: event id, listener id, event type, serialized
payload, publication date, completion date, status, completion attempts, and last resubmission date.

## Verification

The integration test uses `TestDB.enabledDialects()` from
`exposed-jdbc-tests`, so the default coverage is H2, PostgreSQL, and
MySQL 8. CI can narrow the matrix with `EXPOSED_TEST_DB=POSTGRESQL` or
`EXPOSED_TEST_DB=MYSQL_V8`.
