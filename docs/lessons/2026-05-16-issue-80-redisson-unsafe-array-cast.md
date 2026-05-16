# Redisson invalidateAll: Unsafe Array Cast → ClassCastException

**Date**: 2026-05-16  
**Issue**: #80  
**Modules**: `exposed-jdbc-redisson`, `exposed-r2dbc-redisson`  
**Files**: `AbstractJdbcRedissonRepository`, `AbstractR2dbcRedissonRepository`

## Root Cause

`invalidateAll()` and `invalidateByPattern()` used `*ids.toTypedArray<Any>() as Array<ID>`
to spread a collection into a vararg `fastRemove(vararg keys: K)` call.

`toTypedArray<Any>()` always creates `Object[]` at the JVM level. The `as Array<ID>` cast
is unchecked — the JVM does not verify generic array types at runtime. When the JVM later
tries to use the array elements as the expected key type (e.g., passing `Object[]` where
`Long[]` is expected), it throws `ClassCastException` or `ArrayStoreException` depending
on the runtime type of `ID`.

## Decision

Replace the vararg spread with type-safe individual calls:

- `invalidateAll()`: `ids.forEach { cacheOnlyMap.fastRemove(it) }` — `it` is `ID`, no cast
- `invalidateByPattern()`: `keys.sumOf { cacheOnlyMap.fastRemove(it) }` (JDBC) or
  `keys.forEach { key -> removed += ...fastRemoveAsync(key).await() }` (R2DBC)

**Trade-off**: Changes one batched Redis HDEL command to N individual commands.
For invalidation of a small list of IDs this is acceptable; correctness trumps the
minor throughput difference.

## R2DBC Consistency Fix

The R2DBC `invalidateByPattern()` originally had asymmetric accumulator logic:
- `deleteFromDBOnInvalidate=true`: manual `var countRemoved + forEach`
- `deleteFromDBOnInvalidate=false` (after initial fix): `sumOf { suspend-call }`

Both branches now use the explicit `var removed + forEach` pattern for clarity and
to avoid any potential concern about inline suspend-lambda resolution.

## Verification

- `ReadThroughScenario.invalidateAll(getExistingIds())` already exercises multi-ID invalidation
- `ReadThroughScenario.캐시 키 패턴으로 캐시 무효화하기` exercises `invalidateByPattern`
- All tests pass across H2, PostgreSQL, MySQL after the fix

## Future Guidance

- Never spread `Collection<T>.toTypedArray<Any>() as Array<K>` into a typed vararg.
  The `as Array<K>` cast is always unchecked when `K` is a generic type parameter.
- Prefer explicit `forEach` / `sumOf` over vararg spread for generic key types.
