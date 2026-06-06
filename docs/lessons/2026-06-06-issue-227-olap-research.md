# Issue #227 OLAP Research

## Context

Issue #227 asked for a local-testability gate before opening additional OLAP
database implementation work in `bluetape4k-exposed`.

## Decision

Open follow-up work only for candidates with a narrow local proof:

- #255 for StarRocks as the strongest local-first JDBC candidate.
- #256 for Apache Druid as a query-only Avatica JDBC experiment.

Keep Pinot, Redshift, Snowflake, and Databricks out of implementation scope until
their local or approved external verification story is stronger.

## Outcome

The research artifact now records the candidate matrix, narrow contracts, and
defer reasons. README files were not changed because no new module is accepted
as user-facing yet.

## Verification

- Official vendor docs were checked on 2026-06-06.
- `git diff --check`
- Targeted text checks for #227, #255, #256, and OLAP candidates.

## Future Guidance

Do not infer full Exposed dialect support from JDBC connectivity alone. For OLAP
engines, record local startup, metadata behavior, generated SQL shape, DDL/DML
limits, and CI placement before creating module work.
