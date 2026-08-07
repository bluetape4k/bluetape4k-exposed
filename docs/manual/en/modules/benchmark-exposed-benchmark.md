---
manualId: "benchmark-exposed-benchmark"
id: "benchmark-exposed-benchmark"
title: "Exposed Repository Benchmark"
locale: "en"
kind: "benchmark"
gradlePath: ":benchmark-exposed-benchmark"
sourceDir: "benchmark/exposed-benchmark"
releaseRef: "1.12.1"
artifact: null
---

# Exposed Repository Benchmark

> Reproduce a declared Exposed workload before using a performance number in a design decision.

## What it provides {#problem}

This kotlinx-benchmark module separates JDBC/R2DBC, custom identifier tables, local/near cache, and Redis client workloads. It is a regression and investigation tool. It does not publish a library artifact and does not establish a universal persistence ranking.

## When to use it {#when-to-use}

Use it to compare two commits on the same machine or to study one included workload. Keep the JDK, CPU allocation, database/service, data size, benchmark parameters, and background load fixed. Use a production-shaped load test when network topology, concurrency, or tail latency is the real question.

## Coordinates {#coordinates}

The benchmark is not a consumer dependency. Its libraries are aligned by `io.github.bluetape4k:bluetape4k-dependencies:<version>` inside the repository.

## Workloads and metric {#concepts}

All release configurations use throughput (`ops/s`), so a larger score is better for the declared operation. The default main configurations use one warmup, three one-second iterations, and JSON reports.

| Task | Included workload | Runtime dependency |
| --- | --- | --- |
| `jdbcR2dbcBenchmark` | JDBC platform thread, JDBC virtual-thread dispatch, R2DBC suspend transaction selects | H2 in PostgreSQL mode |
| `idTablesBenchmark` | insert/select for UUID, time-based UUID, ULID, Base62 UUIDv7, Snowflake, KSUID variants | H2 in PostgreSQL mode |
| `cacheBenchmark` | Caffeine hit, near-cache hit, read-through miss | in-process cache |
| `redisCacheBenchmark` | Lettuce and Redisson remote-cache get | reachable Redis, default example `127.0.0.1:6379` |
| `smokeBenchmark` | short compile-and-run path excluding Redis | local only |

## Quick start {#quick-start}

Start with the 100 ms smoke configuration:

```bash
./gradlew :benchmark-exposed-benchmark:smokeBenchmark
```

Then run only the workload that answers the question:

```bash
./gradlew :benchmark-exposed-benchmark:jdbcR2dbcBenchmark
./gradlew :benchmark-exposed-benchmark:idTablesBenchmark
./gradlew :benchmark-exposed-benchmark:cacheBenchmark
./gradlew :benchmark-exposed-benchmark:redisCacheBenchmark \
  -Pbenchmark.parameters.redisUri=redis://127.0.0.1:6379
```

## API by task {#api-by-task}

Choose the benchmark class and operation before reading the score. `JdbcThreadingBenchmark` and `R2dbcCoroutineBenchmark` compare execution shapes on H2; `CustomIdTableBenchmark` combines ID generation with table operations; `CacheStrategyBenchmark` measures local and near-cache paths; `RedisCacheBenchmark` crosses a Redis client boundary. These are different questions and should not be collapsed into one chart rank.

## Recommended patterns {#patterns}

- Record repository commit, clean/dirty state, JDK, OS, CPU, memory limit, fork count, warmup, iterations, and raw JSON.
- Compare distributions and error estimates, not the fastest sample.
- Run a separate correctness test before and after the benchmark.
- Change one variable at a time and repeat enough times to distinguish a signal from noise.

## Integrations {#integrations}

The JDBC and R2DBC workloads use H2 PostgreSQL mode, not a production PostgreSQL server. Redis is external and deliberately excluded from smoke. Generate the repository tables and charts after a complete run with:

```bash
./gradlew :benchmark-exposed-benchmark:generateBenchmarkDocs
```

## Configuration {#configuration}

The release defaults are intentionally short: one warmup and three one-second iterations. The smoke task uses one 100 ms iteration with `rowCount=100` and `cacheSize=1000`. Increase warmup, iterations, forks, and data size for a decision-grade run, and preserve the exact command.

## Failure modes {#failures}

- `redisCacheBenchmark` cannot connect: start Redis or supply the correct `redisUri`; do not substitute the smoke result.
- Scores vary widely: remove competing work, add forks and iterations, and inspect allocation/GC.
- A result is implausibly fast: confirm the operation is consumed and is not only reading a warmed cache.
- JDBC/R2DBC numbers are treated as database rankings: restate that both release workloads use local H2 PostgreSQL mode.

## Operations {#operations}

The release README contains a representative table generated on 2026-06-23. For example, that run reported roughly 30,179 ops/s for the JDBC platform-thread select and 2,474 ops/s for the R2DBC suspend-transaction select. The table does not record enough machine, JVM, fork, and service context to generalize those numbers. Treat it as a reproducible sample and regression seed only.

## Testing {#testing}

Run `smokeBenchmark` first to prove compilation and basic execution. A benchmark score is not a correctness assertion: retain repository tests for returned rows, transaction behavior, cache invalidation, and identifier uniqueness.

## Workshops and learning path {#workshops}

Read [Testing and operations](../guides/testing-and-operations.md), reproduce one task, and then move a production hypothesis into [bluetape4k-workshop](https://github.com/bluetape4k/bluetape4k-workshop) with a declared database and workload.

## Limitations {#limitations}

This suite does not prove production latency, scalability, availability, correctness, or that one persistence path wins for another workload. Local H2 results do not include network, server scheduling, production query plans, or managed-service limits.

## Sources {#sources}

- [Release benchmark description and sample results](../../../../benchmark/exposed-benchmark/README.md)
- [Benchmark configurations and tasks](../../../../benchmark/exposed-benchmark/build.gradle.kts)
