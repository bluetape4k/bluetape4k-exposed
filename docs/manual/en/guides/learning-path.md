---
manualId: "learning-path"
title: "Exposed Learning Path"
locale: "en"
releaseRef: "1.12.1"
---

# Exposed Learning Path

It is faster to complete one data-access path than to read modules alphabetically. This route moves from shared concepts to a small repository, then adds framework and operational concerns. Each stage links to detailed explanations and runnable examples, so you can enter at the first unresolved decision.

## 1. Establish the shared model

Start with [`core`](../modules/bluetape4k-exposed-core.md) and [`dao`](../modules/bluetape4k-exposed-dao.md) for entities, IDs, column mapping, and repository concepts. This foundation lets you compare JDBC and R2DBC by transaction and resource ownership rather than by surface-level API shape.

## 2. Complete the primary data-access path

- **JDBC path** — choose [`jdbc`](../modules/bluetape4k-exposed-jdbc.md) when a blocking driver and transaction manager own the boundary. It is the default for conventional Spring MVC services and batch work. Continue with the staged exercises in [`exposed-workshop`](https://github.com/bluetape4k/exposed-workshop).
- **R2DBC path** — choose [`r2dbc`](../modules/bluetape4k-exposed-r2dbc.md) when the driver, framework, and call chain all remain non-blocking. Learn coroutine transactions and cancellation together in [`exposed-r2dbc-workshop`](https://github.com/bluetape4k/exposed-r2dbc-workshop).

The repository's [`Spring Boot JDBC demo`](../modules/exposed-spring-boot-jdbc-demo.md), [`Spring Boot R2DBC demo`](../modules/exposed-spring-boot-r2dbc-demo.md), and [`Ktor demo`](../modules/examples-ktor-exposed-demo.md) are the next step for seeing configuration, application code, and tests together.

## 3. Add caches and database adapters

Choose caching only after repository and transaction semantics are understood. Caffeine fits process-local state; Lettuce and Redisson are candidates when Redis must share state across instances. If invalidation and shutdown ownership are still undecided, the cache boundary is not ready.

PostgreSQL, MySQL 8, DuckDB, ClickHouse, Trino, BigQuery, StarRocks, and CockroachDB adapters add dialect or backend-specific behavior. They refine the JDBC/R2DBC foundation rather than replace it, so select the primary path first and narrow adapters from actual database requirements.

## 4. Expand into application recipes

Use [`bluetape4k-workshop`](https://github.com/bluetape4k/bluetape4k-workshop) for recipes that combine multiple ecosystem libraries. The in-repository ClickHouse OLTP/OLAP and BigQuery dry-run examples are concrete starting points for analytical data paths.

When the goal becomes audit history, object diffs, or commit metadata, move to [`bluetape4k-javers`](https://github.com/bluetape4k/bluetape4k-javers). Exposed repositories own current-state reads and writes; JaVers records how that state changed. Keeping those responsibilities separate prevents persistence APIs from absorbing the audit model.
