---
title: "Testing and Operations"
locale: "en"
releaseRef: "1.12.1"
---

# Testing and Operations

Test the boundary that can fail in production: driver behavior, transaction ownership, replay, cancellation, and external service configuration. An application-context test or an H2 test alone does not prove those properties.

## Evidence ladder

| Level | What it proves | What it does not prove |
| --- | --- | --- |
| Unit test | Mapping, branching, retry classification, pure SQL generation | Driver, server, transaction, or lifecycle behavior |
| H2 integration test | Fast repository contract and basic JDBC/R2DBC wiring | Production dialect, locking, extension, or network behavior |
| Testcontainers | Real engine and driver path used by the test | Production topology, credentials, TLS, capacity, or managed-service policy |
| Environment-gated smoke test | Selected external service can be reached with current configuration | Repeatable performance or broad failure recovery |
| Load/benchmark run | One declared workload under one declared environment | A universal library or database ranking |

## Run the smallest relevant task

```bash
./gradlew :bluetape4k-exposed-ktor:test
./gradlew :bluetape4k-exposed-spring-boot-jdbc:test
./gradlew :bluetape4k-exposed-spring-boot-r2dbc:test
./gradlew :bluetape4k-exposed-spring-boot-batch:test
./gradlew :bluetape4k-exposed-spring-modulith:test
./gradlew :bluetape4k-exposed-batch:test
```

For runnable examples, use their exact paths:

```bash
./gradlew :examples-ktor-exposed-demo:test
./gradlew :exposed-spring-boot-jdbc-demo:test
./gradlew :exposed-spring-boot-r2dbc-demo:test
./gradlew :examples-exposed-bigquery-dry-run:test
./gradlew :examples-exposed-clickhouse-oltp-olap:test
```

The ClickHouse example requires Docker. The BigQuery dry-run example uses a mock REST service, so it requires no cloud credentials and does not prove real project permission, location, quota, or execution results.

## Transaction and restart tests

For JDBC and Spring JDBC, verify that two writes in one business operation roll back together. For R2DBC, verify the explicit `suspendTransaction` boundary and cancellation propagation. For the lightweight batch runner, fail after a writer call and confirm the documented replay behavior: the checkpoint advances only after a successful write, so writers should be idempotent. Use a persistent checkpoint repository when restart must survive a process loss.

Spring Batch has a different runtime. Its reader persists `lastKey` in `ExecutionContext`, the writer joins the chunk transaction, and the chunk commit advances restart state. Use a stable, monotonically ordered key and test a crash between chunks.

![Batch runtime and restart boundaries](../../assets/batch/runtime.png)

## Readiness, shutdown, and observability

- Report readiness only after the required database registry or pool can serve work.
- Keep liveness independent from a transient database outage unless restart is the intended recovery.
- On shutdown, stop admitting work, cancel or drain in-flight operations, persist batch state, and close pools.
- Record transaction failures, retries, skipped batch items, checkpoint position, pool saturation, and query latency.
- Do not log credentials, unredacted SQL parameters, or serialized secrets.

Spring Modulith's publication table tracks event delivery. It is operational state, not an audit history. Monitor incomplete publications and choose update, delete, or archive completion semantics deliberately.

## Benchmark interpretation

The benchmark module uses short JMH runs by default. Its H2 PostgreSQL-mode JDBC/R2DBC measurements are useful for detecting local regressions, not for predicting production database performance. Redis benchmarks require a separately running Redis service. Record CPU, JVM, operating system, forks, warmup, iterations, database/service version, and data size whenever publishing a result.

Continue with the [benchmark module](../modules/benchmark-exposed-benchmark.md) for exact tasks and limitations.

## Sources

- [Module registry](../../../../settings.gradle.kts)
- [Lightweight batch tests](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/utils/batch/src/test/kotlin/io/bluetape4k/batch/core/BatchStepRunnerTest.kt)
- [Spring Batch tests](../../../../spring-boot/batch-exposed/src/test/kotlin/io/bluetape4k/spring/batch/exposed/integration/RestartIntegrationTest.kt)
- [Benchmark build](../../../../benchmark/exposed-benchmark/build.gradle.kts)
