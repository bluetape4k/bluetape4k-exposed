# Lessons Learned - JDBC Lettuce Coverage (2026-07-05)

**Related issue**: #350
**Affected module**: `:bluetape4k-exposed-jdbc-lettuce`

## L1: Coverage gaps should follow the Kover XML sourcefile counters

### Problem

The module-level gap was too large for small repository facade tests. Kover showed
the largest missed instruction count in `ExposedLettuceSuspendedLoadedMap.kt`.

### Lesson

For coverage issues, parse the XML report before adding tests. Sourcefile-level
missed instruction counters identify the fastest path to meaningful coverage gains.

## L2: Direct contract tests can avoid database noise

### Problem

The target class is a Redis loaded-map implementation, but repository scenario tests
pull in database setup and many unrelated branches.

### Lesson

When the uncovered contract is below the repository layer, add focused module-local
contract tests against the lower-level component. For this module, Redis-backed direct
tests covered read-through, write-through, write-behind, close, and failure handling
without adding more DB fixture cost.

