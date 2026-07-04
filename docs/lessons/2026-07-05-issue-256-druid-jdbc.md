# Lessons Learned — Issue #256 Druid JDBC query-only experiment (2026-07-05)

**Related issue**: #256
**Affected modules**: `exposed/druid`, root README locale set, CI/Nightly workflows

## L1: Treat Druid as Avatica query infrastructure, not Exposed dialect parity

### Problem

Druid JDBC exposes SQL query and metadata paths through Avatica, but the official
quickstart and JDBC docs do not make it a safe default target for Exposed DDL,
DML, DAO, repository, or migration abstractions.

### Lesson

Start with query and metadata helpers only. Reject non-query statements in the
helper surface, document the unsupported areas in both README locales, and keep
any full dialect/repository expansion behind a later design with real Druid
behavior proof.

## L2: Druid fixture smoke must be explicit and serial

### Problem

The Druid Docker/local quickstart is memory-heavy and requires a loaded fixture
datasource such as `wikipedia`. This workstation did not have Druid reachable on
`localhost:8888`, so claiming live fixture smoke would be false.

### Lesson

Keep default CI on compile/unit/module tests and expose an env-gated smoke test
for prepared local/container Druid:
`EXPOSED_DRUID_SMOKE=true ./gradlew --no-parallel :bluetape4k-exposed-druid:test --tests '*DruidJdbcSmokeTest'`.
Record unreachable local Druid as an evidence gap instead of hiding it.
