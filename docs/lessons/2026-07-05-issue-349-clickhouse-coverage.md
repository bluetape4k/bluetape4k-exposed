# Lessons & Learns: Issue #349 clickhouse Coverage

## Context

`clickhouse` was far below the current repository module-average instruction
coverage. Kover XML showed the largest missing surfaces were deterministic DSL
and column-type conversion contracts rather than database-only behavior.

## What Worked

- Parsing Kover XML by source file identified `EngineDsl.kt`, `BasicColumnTypes.kt`,
  and `UnsignedColumnTypes.kt` as the highest-value targets.
- Calling every ClickHouse engine builder setting overload raised `EngineDsl.kt`
  instruction coverage without changing production code.
- Direct column-type conversion tests covered signed, unsigned, floating,
  nullable, fixed string, and table-extension builder paths without adding
  more ClickHouse container runtime.
- Keeping the new coverage mostly unit-level preserved the module's existing
  container-backed round-trip coverage while making the issue fast to verify.

## Evidence

- Baseline XML instruction coverage: `60.54%`.
- Final XML instruction coverage: `85.73%`.
- Final module test/Kover command:
  - `./gradlew --no-parallel :bluetape4k-exposed-clickhouse:test :bluetape4k-exposed-clickhouse:koverXmlReport :bluetape4k-exposed-clickhouse:koverLog`
  - Result: `138 passing`, `BUILD SUCCESSFUL`.

## Future Guard

For ClickHouse coverage work, prefer XML-guided DSL and `ColumnType` contract
tests first. Add new container-backed tests only when uncovered behavior depends
on actual ClickHouse execution.
