# Issue #5 Spring Modulith Exposed Event Publication Feasibility

Date: 2026-05-16
Issue: https://github.com/bluetape4k/bluetape4k-exposed/issues/5
Original issue: https://github.com/bluetape4k/bluetape4k-projects/issues/25

## Verdict

JDBC support is technically feasible, but a full `spring-modulith-exposed`
module has weak product value unless the project specifically wants Exposed DSL
ownership of the event publication table and queries.

R2DBC support is not feasible as a first-class Spring Modulith event repository
with the current Spring Modulith SPI. The public `EventPublicationRepository`
contract is synchronous and imperative. A suspend/R2DBC implementation would
either block inside the SPI or require upstream Spring Modulith changes.

Recommended scope:

- Do not implement the original issue as written.
- Keep the runtime scope JDBC-only.
- Implement the runtime as `exposed-spring-modulith`, published as
  `exposed-spring-modulith`, so the artifact does not
  look like an official Spring Modulith store module.
- Use the same `DataSource` and Exposed `springTransactionManager` as the
  application.
- Defer R2DBC until Spring Modulith exposes a reactive/suspend event
  publication repository SPI.

## Accepted Runtime Scope

The accepted implementation scope is:

- Module path: `spring-boot/exposed-spring-modulith`
- Gradle module/artifact: `exposed-spring-modulith`
- Public integration: Spring Boot auto-configuration
- Runtime store: JDBC only
- Transaction boundary: Exposed `springTransactionManager`
- Schema model: Spring Modulith 2.x JDBC V2 column shape
- Required database coverage: H2, PostgreSQL, and MySQL 8
- Explicit non-goals: R2DBC repository, suspend repository, event-listener DSL,
  and a replacement for all official Spring Modulith persistence stores

The artifact name deliberately starts with `exposed-` instead of
`spring-modulith-`. That avoids implying the module is an official Spring
Modulith artifact while still making the integration target obvious.

The MySQL 8 integration test found that a portable schema cannot create a plain
composite index over `TEXT` columns such as `LISTENER_ID` and
`SERIALIZED_EVENT`. The implementation therefore keeps the completion-date
index but avoids the listener/serialized-event index in the Exposed-created
schema. Production applications that need dialect-specific indexing should add
it through Flyway or Liquibase.

The archive completion test also found that Exposed `insertIgnore` is not
portable to default H2 mode. Archive copy therefore uses an existence check plus
plain insert instead of dialect-specific ignore/upsert syntax.

Current verification command:

`./gradlew :exposed-spring-modulith:test --no-daemon --rerun-tasks`

Coverage/result:

- H2, PostgreSQL, MySQL 8
- `CompletionMode.UPDATE`, `DELETE`, `ARCHIVE`
- failed lookup, resubmission attempts, delete by identifiers
- 12 passing

## Evidence

Spring Modulith 2.0 is the relevant generation for Spring Boot 4. The 2.0 GA
announcement says the baseline moved to Spring Boot 4 and Spring Framework 7:
https://spring.io/blog/2025/11/21/spring-modulith-2-0-ga-1-4-5-and-1-3-11-released

The current reference documentation lists Spring Modulith 2.0.6 and its BOM:
https://docs.spring.io/spring-modulith/reference/index.html

The repo already targets Spring Boot 4.0.6 and Exposed 1.2.0:

- `gradle/libs.versions.toml`: `spring-boot = "4.0.6"`
- `gradle/libs.versions.toml`: `exposed = "1.2.0"`
- `gradle/libs.versions.toml`: R2DBC driver versions are already present.

Spring Modulith's event registry writes publication log entries into the
original business transaction and later marks them complete around listener
execution:
https://github.com/spring-projects/spring-modulith/blob/main/src/docs/antora/modules/ROOT/pages/events.adoc

Spring Modulith already provides official persistence starters:

- `spring-modulith-starter-jpa`
- `spring-modulith-starter-jdbc`
- `spring-modulith-starter-mongodb`
- `spring-modulith-starter-neo4j`

Reference:
https://github.com/spring-projects/spring-modulith/blob/main/src/docs/antora/modules/ROOT/pages/events.adoc

Spring Modulith official code search found no R2DBC event repository support.
Only JDBC/JPA/MongoDB/Neo4j store modules are present.

## SPI Surface

The public repository SPI is:
`org.springframework.modulith.events.core.EventPublicationRepository`.

Current main branch methods include:

- `TargetEventPublication create(TargetEventPublication publication)`
- `markProcessing(UUID identifier)`
- `markCompleted(Object event, PublicationTargetIdentifier identifier, Instant completionDate)`
- `markCompleted(UUID identifier, Instant completionDate)`
- `markFailed(UUID identifier)`
- `findIncompletePublications()`
- `findIncompletePublicationsPublishedBefore(Instant instant)`
- `findIncompletePublicationsByEventAndTargetIdentifier(...)`
- `findCompletedPublications()`
- `findFailedPublications(FailedCriteria criteria)`
- `findByStatus(Status status)`
- `deletePublications(List<UUID> identifiers)`
- `deleteCompletedPublications()`
- `deleteCompletedPublicationsBefore(Instant instant)`

Source:
https://github.com/spring-projects/spring-modulith/blob/main/spring-modulith-events/spring-modulith-events-core/src/main/java/org/springframework/modulith/events/core/EventPublicationRepository.java

This is a synchronous Java interface. It has no `Publisher`, `Mono`, `Flux`,
`CompletionStage`, or Kotlin `suspend` boundary. That makes native R2DBC
integration structurally mismatched.

## Official JDBC Implementation Shape

Spring Modulith's current JDBC V2 implementation is package-private and
annotated with `@Transactional`:

`JdbcEventPublicationRepositoryV2 implements EventPublicationRepository`

It stores these columns:

- `ID`
- `COMPLETION_DATE`
- `EVENT_TYPE`
- `LISTENER_ID`
- `PUBLICATION_DATE`
- `SERIALIZED_EVENT`
- `STATUS`
- `COMPLETION_ATTEMPTS`
- `LAST_RESUBMISSION_DATE`

Source:
https://github.com/spring-projects/spring-modulith/blob/main/spring-modulith-events/spring-modulith-events-jdbc/src/main/java/org/springframework/modulith/events/jdbc/JdbcEventPublicationRepositoryV2.java

The PostgreSQL v2 schema uses:

- `event_publication`
- `id UUID`
- `listener_id TEXT`
- `event_type TEXT`
- `serialized_event TEXT`
- `publication_date TIMESTAMP WITH TIME ZONE`
- `completion_date TIMESTAMP WITH TIME ZONE`
- `status TEXT`
- `completion_attempts INT`
- `last_resubmission_date TIMESTAMP WITH TIME ZONE`
- hash index on `serialized_event`
- index on `completion_date`

Source:
https://github.com/spring-projects/spring-modulith/blob/main/spring-modulith-events/spring-modulith-events-jdbc/src/main/resources/org/springframework/modulith/events/jdbc/schemas/v2/schema-postgresql.sql

Issue #5's proposed schema is stale for Spring Modulith 2.x because it omits
`status`, `completion_attempts`, and `last_resubmission_date`.

## Feasibility Matrix

| Scope | Feasibility | Reason |
| --- | --- | --- |
| Use official JDBC with Exposed app | High | Exposed and Spring Modulith can share the same `DataSource`; official JDBC already persists events without JPA. |
| Exposed JDBC `EventPublicationRepository` | Medium | SPI is public and implementable, but reproduces official JDBC behavior and must track upstream lifecycle changes. |
| Exposed Table DSL only | High | Easy to model, but low value because official SQL schema is already provided. |
| Auto-configuration | High | Repo already has Spring Boot 4 auto-config patterns under `spring-boot/exposed-jdbc` and `spring-boot/exposed-r2dbc`. |
| H2 + PostgreSQL tests | High for JDBC | Official schema supports both; repo has H2/PostgreSQL/Testcontainers dependencies. |
| Native R2DBC repository | Low | Spring Modulith repository SPI is synchronous; no official R2DBC store module exists. |
| Suspend API | Low as Modulith SPI | A suspend facade could exist outside Spring Modulith, but it would not satisfy `EventPublicationRepository`. |
| Module event DSL | Low value | Spring Modulith already provides `@ApplicationModuleListener`; extra DSL is likely abstraction noise. |

## Implementation Risks

JDBC implementation risks:

- Need to match Spring Modulith 2.x lifecycle exactly:
  `PUBLISHED`, `PROCESSING`, `COMPLETED`, `FAILED`, `RESUBMITTED`.
- Need to preserve completion attempts and last resubmission date semantics.
- Need to support completion archive/delete behavior if matching official JDBC
  behavior.
- Need to use Spring Modulith `EventSerializer` so serialization stays
  compatible with official modules.
- Need to avoid bean conflicts with `spring-modulith-starter-jdbc` and only
  register when no `EventPublicationRepository` bean exists.
- Need tests for transaction rollback: event publication insert must roll back
  when the business transaction rolls back.
- Need tests for listener failure: publication remains resumable/failed
  according to Spring Modulith 2.x lifecycle.

R2DBC risks:

- Current SPI forces blocking method signatures.
- `@TransactionalEventListener` and Spring Modulith event publication registry
  are built around imperative transaction semantics.
- A `runBlocking` bridge would defeat R2DBC and can break transaction context.
- Exposed R2DBC currently uses `suspendTransaction`, but
  `EventPublicationRepository` cannot call suspend functions.

## Recommended Issue Update

Replace the original issue with a narrower decision:

1. Do not implement R2DBC for Spring Modulith event publication until upstream
   adds a reactive/suspend SPI.
2. Implement a JDBC-only Exposed-backed repository because the project wants
   ownership of the publication table through Exposed and the same Exposed
   transaction manager.
3. Avoid official-looking artifact naming by using `exposed-spring-modulith`.
4. Keep tests focused on auto-registration, create/complete lifecycle,
   failed-publication lookup, and H2/PostgreSQL/MySQL 8 schema compatibility.

## Suggested Final Disposition

Keep Issue #5 open while the JDBC-only runtime implementation is reviewed.
Close only after the module, docs, and targeted tests land. Do not reopen R2DBC
under the same issue unless Spring Modulith adds a reactive/suspend SPI.
