---
title: "Choosing an OLTP or OLAP Path"
locale: "en"
releaseRef: "1.12.1"
---

# Choosing an OLTP or OLAP Path

OLTP and OLAP are workload shapes, not permanent labels for products. Choose where a request must commit, then choose where its history should be scanned or aggregated.

## Compare the work

| Concern | Transaction-oriented path | Analytical path |
| --- | --- | --- |
| Typical adapters | PostgreSQL, MySQL, CockroachDB | DuckDB, ClickHouse, Trino, BigQuery, StarRocks |
| Latency target | short request/response and lock duration | scan/aggregation throughput and queue time |
| Write model | small atomic units, constraints, rollback/retry | append/batch/job-oriented; atomicity varies |
| Schema | migration-backed relational contract | engine/connector-specific DDL and partition/order choices |
| Paging | stable keyset/order inside an indexed model | reduce/aggregate first; stream or page only the required result |
| Local test | PostgreSQL/MySQL/Cockroach containers | embedded DuckDB or engine/emulator containers |

No row in this table declares a universal winner. A PostgreSQL aggregate can be the simplest correct solution, while a ClickHouse write may be wrong if the request must roll back.

## Keep the commit owner explicit

For transactional changes, commit in PostgreSQL/MySQL or retry a whole CockroachDB transaction. Publish data to an analytical store after commit through an outbox, CDC, or idempotent job. ClickHouse, Trino, and StarRocks wrappers do not turn an Exposed block into an atomic unit. BigQuery calls are separate REST jobs.

## Choose the analytical execution boundary

- DuckDB: local files and embedded analysis with no service; mind per-connection in-memory state.
- ClickHouse: engine-aware analytical tables and batch ingestion; design for autocommit and deduplication.
- Trino: cross-catalog query coordination; verify connector pushdown, DDL, and DML.
- BigQuery: direct Query Jobs with page tokens, dry run, labels, and billed-byte limits.
- StarRocks: release 1.11 offers a narrow local smoke path; extend only with new evidence.

## Schema, testing, and dry run

Manage production schemas with migrations or the engine's schema tooling. Run transactional tests against the actual database container. Run analytical adapter tests against the matching engine or emulator, then retain a small production-like smoke test. BigQuery dry run validates parsing, authorization, and cost before execution; it does not prove result correctness.

## A safe progression

1. Prove the request and transaction boundary on the OLTP source.
2. Define the event/export contract and an idempotency key.
3. Load a small analytical fixture and compare results with the source.
4. Measure latency, scan volume, batch size, and retry behavior.
5. Add paging or streaming based on the adapter's real behavior: materialized Flow is not a cursor.

Run the [ClickHouse OLTP/OLAP example](../../../../examples/exposed-clickhouse-oltp-olap/README.md) for a concrete split and the [BigQuery dry-run example](../../../../examples/exposed-bigquery-dry-run/README.md) for cost-aware validation. Return to the [adapter matrix](database-adapter-matrix.md) for per-module limits.
