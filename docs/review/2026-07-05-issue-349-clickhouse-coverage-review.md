# Issue #349 clickhouse Coverage Review

## Scope

- Issue: <https://github.com/bluetape4k/bluetape4k-exposed/issues/349>
- Module: `:bluetape4k-exposed-clickhouse`
- Changed test surface:
  - `exposed/clickhouse/src/test/kotlin/io/bluetape4k/exposed/clickhouse/engine/MergeTreeDslTest.kt`
  - `exposed/clickhouse/src/test/kotlin/io/bluetape4k/exposed/clickhouse/types/UnsignedTypesTest.kt`

## Review Result

- P0/P1 findings: 0
- Tier 4 correctness: PASS
- Tier 5 test adequacy: PASS
- Tier 7 evidence integrity: PASS

## Evidence

- Baseline Kover XML instruction coverage: `60.54%` (`covered=2686`, `missed=1751`, `total=4437`).
- Baseline issue target: raise above repository module average `80.81%`.
- Added focused unit coverage for ClickHouse contracts:
  - `MergeTree`, `ReplacingMergeTree`, `SummingMergeTree`, and `AggregatingMergeTree` raw/typed DSL overloads.
  - ClickHouse engine setting overloads for `String` and `ClickHouseSettingName` names across numeric, boolean, and string values.
  - unsafe raw fragment/name validation branches.
  - basic signed, unsigned, floating, fixed string, nullable, and table-extension column type conversions.
- Focused command:
  - `./gradlew --no-parallel :bluetape4k-exposed-clickhouse:compileTestKotlin :bluetape4k-exposed-clickhouse:test --tests 'io.bluetape4k.exposed.clickhouse.engine.MergeTreeDslTest' --tests 'io.bluetape4k.exposed.clickhouse.types.UnsignedTypesTest'`
  - Result: `62 passing`, `BUILD SUCCESSFUL`.
- Full module command:
  - `./gradlew --no-parallel :bluetape4k-exposed-clickhouse:test :bluetape4k-exposed-clickhouse:koverXmlReport :bluetape4k-exposed-clickhouse:koverLog`
  - Result: `138 passing`, `BUILD SUCCESSFUL`.
- Final Kover:
  - Line coverage: `88.0065%`.
  - XML instruction coverage: `85.73%` (`covered=3804`, `missed=633`, `total=4437`).

## Notes

- No production behavior changed.
- The coverage lift avoids additional ClickHouse container round-trip tests and focuses on deterministic DSL/type contracts.
- Testcontainers-backed verification was run with `--no-parallel`.
