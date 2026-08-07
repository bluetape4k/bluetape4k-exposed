---
title: "Database Adapter Matrix"
locale: "en"
releaseRef: "1.12.1"
---

# Database Adapter Matrix

Choose an adapter from the execution and ownership boundary, not from SQL syntax alone. Release 1.11 contains the eight adapters below. Druid is not part of this release.

![Release 1.11 database adapter map](../../assets/database/adapter-map.png)

## Release-backed matrix

| Adapter | Boundary | Query and DDL evidence | Transaction boundary | Test path | Main limit |
| --- | --- | --- | --- | --- | --- |
| PostgreSQL | JDBC extension types | pgvector, PostGIS, `tstzrange`; schema via JDBC/migrations | Ordinary JDBC commit/rollback | PostgreSQL Testcontainers | Optional drivers/extensions are caller-owned |
| MySQL 8 | JDBC GIS extension | JTS geometry and spatial functions | Ordinary JDBC commit/rollback | MySQL Testcontainers | GIS layer, not a general connection adapter |
| CockroachDB | PostgreSQL JDBC adapter | basic schema plus compatibility ledger | Serializable whole-block retry on `40001` | CockroachDB Testcontainers | PostgreSQL compatibility is partial |
| DuckDB | Embedded JDBC | local create/insert/select; PostgreSQL-derived dialect | Connection-scoped DuckDB transactions | Embedded, no container | In-memory DB is per connection; Flow materializes |
| ClickHouse | JDBC OLAP adapter | engine DDL, custom types/functions, batch insert | Autocommit; rollback is a no-op | ClickHouse Testcontainers + example | Relational constraints/upsert/returning unsupported |
| Trino | Coordinator JDBC adapter | SELECT-first and connector-dependent DDL/DML | Autocommit; statements are not atomic together | Trino Testcontainers memory connector | Production connector behavior must be retested |
| BigQuery | SQL generator + REST Query Job | SELECT, basic DML/DDL, dry run, page tokens | Each REST job is independent | Emulator/Testcontainers + dry-run example | No JDBC transaction or cross-job rollback |
| StarRocks | Narrow Connector/J smoke adapter | local AIO DDL/insert/select | Autocommit-only wrapper | StarRocks all-in-one container | Not full MySQL/production parity |

## Driver and service ownership

PostgreSQL and MySQL extension modules keep database drivers compile-only; the application supplies the driver and optional extension libraries. CockroachDB, DuckDB, ClickHouse, Trino, BigQuery, and StarRocks expose their execution clients or drivers through the module dependency. A test container proves a release path, not that production provisioning, credentials, TLS, migrations, or topology are owned by the library.

## Query, paging, and batch

For transactional JDBC adapters, use the JDBC module's batch and paging patterns. DuckDB, ClickHouse, and Trino `queryFlow` APIs materialize the complete `Iterable` inside the transaction; they improve coroutine composition but are not server cursors. BigQuery `toFlow` is different: it follows REST page tokens and emits page by page. StarRocks 1.11 proves a single-row smoke path, so batch and paging require additional evidence.

## Schema and unsupported operations

Keep production schema changes in migrations. ClickHouse, Trino, BigQuery, and StarRocks include deliberately narrow DDL adapters; they do not promise relational feature parity. CockroachDB maintains a compatibility ledger. PostgreSQL and MySQL extension modules add types and expressions but do not install server extensions or indexes.

## Decision route

1. If the request changes transactional state, begin with PostgreSQL, MySQL, or CockroachDB.
2. If the workload is local analytical SQL, begin with DuckDB.
3. If the target is an OLAP engine, choose its explicit adapter and accept its write/DDL boundary.
4. If queries span catalogs, evaluate Trino and verify the production connector.
5. If BigQuery cost validation matters, use the direct REST adapter and dry run.

Continue with [OLTP vs OLAP](oltp-vs-olap.md) before mixing a transactional source and analytical sink.

## Sources

- [Release manifest](../../manifest.yaml)
- [Release project registry](../../../../settings.gradle.kts)
- [ClickHouse example](../../../../examples/exposed-clickhouse-oltp-olap/README.md)
- [BigQuery dry-run example](../../../../examples/exposed-bigquery-dry-run/README.md)
