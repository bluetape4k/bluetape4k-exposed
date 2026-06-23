# Issue 285 ClickHouse DDL Hardening Review

Date: 2026-06-23
Scope: `:bluetape4k-exposed-clickhouse`, ClickHouse engine DDL DSL hardening
Issue: #285

## Verdict

P0 findings: 0
P1 findings: 0

The final diff replaces implicit string-based engine DSL calls with typed Exposed expressions and explicit `unsafeRaw...` escape hatches. Raw fragments are validated for statement delimiters, comments, quotes, newlines, and clause-boundary tokens before they can be rendered into DDL.

## Review Notes

- Typed engine expressions now render unqualified column names for table-local ClickHouse engine clauses, avoiding invalid `table.column` DDL in `ORDER BY`, `PARTITION BY`, `PRIMARY KEY`, and engine arguments.
- `ClickHouseTable` keeps constructor compatibility while allowing `override val engine` declarations after columns are initialized, so ordinary table definitions can use typed `orderBy(id)` instead of constructor-time raw strings.
- Safe settings are restricted to allowlisted MergeTree setting names. Arbitrary setting names require `unsafeRawSetting`, and unsafe raw setting values use the same delimiter/comment/clause validation as raw expressions.
- Known ClickHouse helper functions are rendered recursively without requiring an Exposed transaction. Unknown expressions fall back to `QueryBuilder`, preserving a compatibility path for modeled expressions outside the current renderer.

## Validation

- `./gradlew :bluetape4k-exposed-clickhouse:test --tests 'io.bluetape4k.exposed.clickhouse.engine.MergeTreeDslTest' --tests 'io.bluetape4k.exposed.clickhouse.SchemaUtilsTest' --no-build-cache --console=plain`
  - Result: success, 25 tests executed.
  - Covered typed expression rendering, raw fragment rejection, allowlisted settings, typed `ClickHouseTable` engine override, and real schema create/drop for a typed engine table.

## Residual Risk

- Raw escape hatches remain intentionally available for ClickHouse grammar fragments not yet modeled by Exposed, such as `assumeNotNull(created_at)`. They are explicit and validator-gated.
- The fallback `QueryBuilder` path may render dialect-specific expressions differently than ClickHouse engine DDL expects. Extend the dedicated renderer when adding new ClickHouse expression helpers used in engine clauses.
