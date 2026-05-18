# CTE Query DSL

## Context

Issue #157 added PostgreSQL/MySQL Common Table Expression support to the
Exposed extension modules. Exposed does not currently expose a first-class CTE
DSL for repository code, but users still need typed field access and prepared
statement binding instead of raw SQL strings.

## Decision

Introduce `CteTable` in `exposed-core` and keep JDBC/R2DBC helpers as Query
wrappers:

- `CteTable` maps selected fields from an existing `Query` into a table-like
  facade so downstream SELECT clauses can keep typed column access.
- `withCte` and `withCtes` render the CTE body and final SELECT through the same
  Exposed `QueryBuilder` flow.
- Recursive CTE support is a flag on the CTE clause instead of a separate raw SQL
  path.

## Outcome

JDBC and R2DBC now share the same CTE table facade while each module keeps its
own query wrapper surface. The public API remains scoped to SELECT queries, so
DML CTE support can be designed separately if Exposed exposes a better internal
statement contract later.

## Verification

- JDBC CTE tests passed against H2, PostgreSQL, and MySQL 8.
- R2DBC CTE tests passed against H2, PostgreSQL, and MySQL 8.
- Root README, module README, WIP, and CHANGELOG were updated together.

## Future Guidance

- Keep JDBC and R2DBC CTE behavior symmetric unless a driver-specific limitation
  is documented in tests.
- Do not split CTE body rendering from final SELECT rendering; that risks
  prepared-parameter ordering bugs.
- When adding public Exposed DSL helpers, update both English and Korean module
  README files in the same PR.
