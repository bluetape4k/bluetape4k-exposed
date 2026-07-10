# R2DBC `@Query` result semantics

## Context

R2DBC declared queries used raw SQL only to collect entity IDs, then reloaded entities with an
`IN` query. The reload discarded the SQL result order, and queries without an ID column produced
driver-dependent failures.

## Decision

- Reload entities once, index them by ID, and rebuild the result in the raw SQL ID order.
- Validate the SELECT list before execution so empty projection/grouping results cannot bypass the
  mapped entity ID requirement. Locate top-level SELECT branches by tracking quotes, comments, and
  parenthesis depth instead of matching nested SQL with a regular expression. Treat PostgreSQL
  dollar-quoted strings and MySQL hash comments as lexical regions, including projection commas.
- Decode raw IDs with the Exposed ID column type, reload distinct IDs, and reconstruct from the
  original ID sequence so duplicate join rows retain their cardinality.
- Unwrap the internal unsupported-shape exception so H2, PostgreSQL, and MySQL expose the same
  `IllegalArgumentException` contract.
- Keep scalar projection and aggregation mapping outside the entity repository query path.

## Outcome

`ORDER BY`, `LIMIT`, and join ordering are deterministic. Projection and grouping shapes without
an entity ID fail explicitly instead of relying on the first selected column as an ID.

## Verification

The declared-query regression suite covers ordering, limits, joins, scalar projection, and grouping
against H2, PostgreSQL, and MySQL.
