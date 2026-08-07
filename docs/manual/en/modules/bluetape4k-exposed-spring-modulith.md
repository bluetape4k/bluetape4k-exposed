---
manualId: "bluetape4k-exposed-spring-modulith"
id: "bluetape4k-exposed-spring-modulith"
title: "Exposed Spring Modulith Integration"
locale: "en"
kind: "library"
gradlePath: ":bluetape4k-exposed-spring-modulith"
sourceDir: "spring-boot/spring-modulith"
releaseRef: "1.12.1"
artifact: io.github.bluetape4k.exposed:bluetape4k-exposed-spring-modulith
---

# Exposed Spring Modulith Integration

> A JDBC delivery ledger for Spring Modulith event publications, stored through Exposed in the application's business transaction.

## Problem {#problem}

`ExposedEventPublicationRepository` implements Spring Modulith's `EventPublicationRepository`. It records an event, target listener, serialized payload, publication time, delivery status, attempts, and completion data so incomplete deliveries can be found and retried. This is a delivery ledger, not a domain audit trail, entity history, or object diff store.

![Spring Modulith publication lifecycle](../../assets/spring/modulith-publication.png)

## When to use it {#when-to-use}

Use it when a Spring Modulith application stores business data with Exposed JDBC and wants event-publication rows to participate in the same datasource and named Spring transaction manager. Use [JaVers](https://github.com/bluetape4k/bluetape4k-javers) when the requirement is who changed an object, historical snapshots, or diffs; publication completion rows answer a different question.

## Coordinates {#coordinates}

Import the ecosystem BOM and omit the module version:

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-spring-modulith")
}
```

## Core concepts {#concepts}

The repository class is bound to `springTransactionManager`. Auto-configuration activates only when that named bean exists, Spring Modulith's repository API and Exposed JDBC are present, and an `EventSerializer` bean is available. The transaction manager must address the same `DataSource` used for business data if publication intent and the business change must commit atomically.

An active publication can be created, marked `PROCESSING`, `FAILED`, or `RESUBMITTED`, listed by status or failure criteria, and found by event plus target. `EventSerializer` converts the event to stored text and reconstructs it using the recorded event class.

## Quick start {#quick-start}

Configure Exposed JDBC and the named `springTransactionManager`, provide a Spring Modulith `EventSerializer`, apply the publication-table migration, and publish an application event from a Spring-managed business transaction. Leave `initialize-schema` disabled in normal environments and verify that the publication row is visible before listener completion.

```yaml
bluetape4k:
  spring:
    modulith:
      exposed:
        completion-mode: update
        initialize-schema: false
```

## API by task {#api-by-task}

- `create` records one `TargetEventPublication`.
- `findIncompletePublications*`, `findFailedPublications`, `findByStatus`, and `countByStatus` drive recovery and operations.
- `markProcessing`, `markFailed`, and `markResubmitted` update delivery state and attempts.
- `markCompleted` applies the configured `UPDATE`, `DELETE`, or `ARCHIVE` completion strategy.
- `deletePublications` and completed-publication cleanup methods implement retention decisions.

## Recommended patterns {#patterns}

Publish inside the same business transaction that changes domain state, then make listeners idempotent because delivery can be retried. Keep event class names and serialized shape compatible while incomplete rows exist. Monitor and remediate failed or old incomplete publications rather than deleting them silently. Treat completion cleanup as retention policy, not audit retention.

## Integrations {#integrations}

`ExposedModulithAutoConfiguration` creates active and archive table models, then conditionally creates the repository when no other `EventPublicationRepository` exists. The default table shape follows Spring Modulith JDBC schema v2. The archive strategy copies a completed row to the archive table before deleting it from the active table.

## Configuration {#configuration}

Configure `table-name`, `archive-table-name`, `completion-mode`, and `initialize-schema` under `bluetape4k.spring.modulith.exposed`. Completion defaults to `UPDATE`; schema initialization defaults to `false`. Production deployments should manage both active and archive tables with Flyway or Liquibase. The `SchemaUtils` initializer is an explicit opt-in convenience, not a migration system.

## Failure modes {#failures}

- Repository bean is missing: verify the named `springTransactionManager` and `EventSerializer` beans and ensure another publication repository has not taken precedence.
- Business data commits without a publication row: the repository and business operation are using different datasources or transaction managers.
- Stored publication cannot be reconstructed: its event class is no longer loadable. Restore a compatible class or explicitly migrate/delete the affected row after assessing delivery impact; the repository raises `UnloadableEventPublicationException` with identifier, event type, and listener.
- Archive completion encounters a duplicate: the repository rolls back to a savepoint and treats SQL-state class `23` integrity violations as an idempotent concurrent archive, preserving the outer transaction.
- Archive insert fails for another SQL reason: the exception is rethrown; do not delete the active row.

## Operations {#operations}

Track counts and age by publication status, completion attempts, last resubmission time, oldest incomplete publication, archive growth, and unloadable-event failures. Reconcile listener side effects before resubmission. Apply retention separately to completed rows; active failed rows are operational work, not historical decoration.

## Testing {#testing}

Run the exact module tests:

```bash
./gradlew :bluetape4k-exposed-spring-modulith:test
```

Verify atomic business-data/publication commit and rollback on the same datasource, serializer round-trip, active and completed queries, each completion mode, resubmission attempts, cleanup, archive savepoint behavior, duplicate archive idempotency, unloadable event type reporting, and schema initialization remaining off unless explicitly enabled.

## Workshops and learning path {#workshops}

Read [Audit with JaVers](../guides/audit-with-javers.md) to separate delivery reliability from audit/history requirements. Continue with the [bluetape4k JaVers repository](https://github.com/bluetape4k/bluetape4k-javers) when snapshots and diffs are required.

## Limitations {#limitations}

The repository is JDBC-only and fixed to the named `springTransactionManager`. It does not provide broker delivery, distributed transactions, exactly-once listener side effects, schema migration, or domain audit history. Stored event types remain a compatibility obligation until their publications are completed or explicitly remediated.

<!-- release-readme-diagrams:start -->
## Release diagrams {#release-diagrams}

These diagrams are loaded directly from README assets published with the `1.12.1` release and pinned to its immutable commit. They describe this manual's released structure and runtime flows, not later Snapshot changes. Select a preview to open the SVG at the same release commit.

### Spring Modulith Exposed JDBC wiring diagram

[![Spring Modulith Exposed JDBC wiring diagram](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/spring-boot-exposed-spring-modulith-diagram-01.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/spring-boot-exposed-spring-modulith-diagram-01.svg)

_Release README: [`spring-boot/spring-modulith/README.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/spring-boot/spring-modulith/README.md)_

### Spring Modulith publication lifecycle sequence diagram

[![Spring Modulith publication lifecycle sequence diagram](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/spring-boot-exposed-spring-modulith-sequence-01.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/spring-boot-exposed-spring-modulith-sequence-01.svg)

_Release README: [`spring-boot/spring-modulith/README.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/spring-boot/spring-modulith/README.md)_

<!-- release-readme-diagrams:end -->

## Sources {#sources}

- [`ExposedEventPublicationRepository.kt`](../../../../spring-boot/spring-modulith/src/main/kotlin/io/bluetape4k/spring/modulith/exposed/ExposedEventPublicationRepository.kt)
- [`ExposedEventPublicationTable.kt`](../../../../spring-boot/spring-modulith/src/main/kotlin/io/bluetape4k/spring/modulith/exposed/ExposedEventPublicationTable.kt)
- [`ExposedModulithAutoConfiguration.kt`](../../../../spring-boot/spring-modulith/src/main/kotlin/io/bluetape4k/spring/modulith/exposed/config/ExposedModulithAutoConfiguration.kt)
- [`ExposedModulithProperties.kt`](../../../../spring-boot/spring-modulith/src/main/kotlin/io/bluetape4k/spring/modulith/exposed/config/ExposedModulithProperties.kt)
