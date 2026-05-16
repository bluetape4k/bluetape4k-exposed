# PartTreeExposedQuery.executePageQuery: Double entityClass.find() for Count

**Date**: 2026-05-16  
**Issue**: #90  
**Module**: `exposed-spring-boot-jdbc`  
**File**: `PartTreeExposedQuery.kt`

## Root Cause

`executePageQuery` called `entityClass.find { op }` twice — once for counting, once for data:

```kotlin
val total = entityClass.find { op }.count()   // first find() call — goes through entity layer
val query = entityClass.find { op }           // second find() call — unnecessary duplication
```

Both calls build the same predicate independently. The entity-based count also goes through
the DAO entity infrastructure unnecessarily for a scalar COUNT result.

## Fix

Replace the entity-based count with a direct table DSL count:

```kotlin
// COUNT via table DSL — avoids going through entity infrastructure for a scalar
val total = entityInformation.table.selectAll().where { op }.count()
val query = entityClass.find { op }
```

`table.selectAll().where { op }.count()` generates `SELECT COUNT(*) FROM table WHERE ...`
directly, without entity instantiation overhead. The `entityClass.find { op }` for data
remains unchanged.

## Future Guidance

- For scalar aggregate queries (COUNT, SUM, etc.) in DAO-based repositories, prefer the
  table DSL path (`table.selectAll().where { ... }.count()`) over `entityClass.find { ... }.count()`.
  The DAO path creates entity infrastructure that is unnecessary for scalars.
- Pagination always requires two DB queries (count + data) — this is unavoidable. Both
  run within the same `@Transactional` context, providing as much consistency as the
  configured isolation level allows.
