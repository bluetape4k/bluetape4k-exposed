# Design — Issue #256 Druid JDBC query-only experiment

## Scope

Add `bluetape4k-exposed-druid` as a query-only Apache Druid JDBC experiment.
The module must use Apache Calcite Avatica JDBC and must not imply full Exposed
ORM dialect parity.

## In scope

- Avatica JDBC connection option builder for Druid Router/Broker endpoints.
- Query execution helpers over `java.sql.Connection`.
- Metadata discovery helpers for Druid datasources using `INFORMATION_SCHEMA`.
- Bilingual module README documenting query-only positioning, Router/Broker
  stickiness, Avatica properties, and smoke-test commands.
- CI/Nightly registration as a serial module test job.

## Out of scope

- Exposed `Database`/dialect registration.
- DDL, DML, DAO, repository, migration, schema generation, or batch-write APIs.
- A broad Testcontainers launcher unless a stable Druid fixture datasource recipe
  is proven separately.

## Acceptance mapping

| Issue acceptance | Design answer |
|---|---|
| JDBC connection smoke | `DruidJdbcSmokeTest` runs when `EXPOSED_DRUID_SMOKE=true` against a local/container Druid endpoint. |
| Metadata discovery | `DruidJdbc.listColumns()` queries `INFORMATION_SCHEMA.COLUMNS`. |
| SELECT query | `DruidJdbc.query()` and smoke test execute `SELECT`. |
| Query-only docs | README files state unsupported DDL/DML/DAO/repository/migration explicitly. |
| CI/Nightly placement | Dedicated serial module test jobs compile/run normal tests; smoke is environment-gated. |

## Risks

- Official Druid Docker quickstart is multi-container and memory-heavy; default CI
  should not start it implicitly. The smoke test is executable for a prepared
  local/container Druid with a loaded datasource.
- Avatica driver versions newer than the Druid docs' minimum may change
  transitive dependencies; targeted compile/test must verify classpath.
