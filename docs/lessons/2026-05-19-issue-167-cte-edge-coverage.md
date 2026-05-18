# CTE Edge Coverage

## Context

Issue #167 followed up on the CTE query DSL from #157. The initial tests proved
basic `WITH` and recursive CTE behavior, but left untested branches in multi-CTE
rendering, `UNION` rendering without `ALL`, missing field lookup errors, and
expression alias mapping.

## Decision

Add the same four focused regression cases to both JDBC and R2DBC `CteQueryTest`:

- `withCtes(cte1, cte2)` renders a comma-separated `WITH` list and remains executable.
- `unionAll = false` renders `UNION` and does not render `UNION ALL`.
- Accessing a field outside the CTE query set throws the existing error message.
- `IExpressionAlias` fields can be mapped back through `CteTable`.

## Outcome

The CTE test suite now covers the primary edge cases without changing runtime
code or public API. JDBC and R2DBC stay symmetric.

## Verification

- `./gradlew :bluetape4k-exposed-jdbc:compileTestKotlin :bluetape4k-exposed-r2dbc:compileTestKotlin --console=plain --no-daemon`
- `./gradlew :bluetape4k-exposed-jdbc:test --tests "io.bluetape4k.exposed.jdbc.CteQueryTest" :bluetape4k-exposed-r2dbc:test --tests "io.bluetape4k.exposed.r2dbc.CteQueryTest" --console=plain --no-daemon`

## Future Guidance

- Keep CTE edge tests mirrored between JDBC and R2DBC.
- Use explicit `SortOrder` pairs when ordering by multiple Exposed expressions.
- Keep alias variables concrete for `select(...)`, and cast only the CTE lookup
  call to `IExpressionAlias<T>` when testing that branch.
