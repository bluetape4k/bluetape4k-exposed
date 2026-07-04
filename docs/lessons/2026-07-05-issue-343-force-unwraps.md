# Lesson — Issue #343 force unwrap cleanup

## Decision

Do not use force unwrap in production or shared helper code. Capture validated values into non-null locals with either:

- `requireNotNull(...)` when the value is caller input or builder configuration, preserving `IllegalArgumentException` semantics.
- `checkNotNull(...)` when the value is internal lifecycle state or a framework invariant, preserving `IllegalStateException` semantics.

## Patterns applied

- After a guard, avoid re-reading nullable state with force unwrap; bind a local non-null value at the use site.
- Replace `filter { value != null }.map { value!! }` with `mapNotNull { value?.let { ... } }`.
- Keep broad test assertion unwrap cleanup separate from production/helper cleanup to avoid noisy mechanical diffs.

## Future guardrail

If a repo-wide `rg '!!'` finds a new `src/main` hit, treat it as a production-quality issue unless it is only historical documentation text. Prefer documenting why a value is non-null in the exception message rather than relying on an NPE.
