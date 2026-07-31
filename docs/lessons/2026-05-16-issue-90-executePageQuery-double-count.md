# PartTreeExposedQuery.executePageQuery: count를 위한 entityClass.find() 이중 호출

**Date**: 2026-05-16
**Issue**: #90
**Module**: `exposed-spring-boot-jdbc`
**File**: `PartTreeExposedQuery.kt`

## 근본 원인

`executePageQuery`는 count와 data에 각각 `entityClass.find { op }`를 두 번
호출했습니다.

```kotlin
val total = entityClass.find { op }.count()   // first find() call — goes through entity layer
val query = entityClass.find { op }           // second find() call — unnecessary duplication
```

두 호출은 같은 predicate를 독립적으로 만듭니다. entity-based count는 scalar COUNT
result에 불필요한 DAO entity infrastructure도 거칩니다.

## 수정

entity-based count를 직접 table DSL count로 교체합니다.

```kotlin
// COUNT via table DSL — avoids going through entity infrastructure for a scalar
val total = entityInformation.table.selectAll().where { op }.count()
val query = entityClass.find { op }
```

`table.selectAll().where { op }.count()`는 entity instantiation overhead 없이
`SELECT COUNT(*) FROM table WHERE ...`를 직접 생성합니다. data용
`entityClass.find { op }`는 그대로 둡니다.

## 향후 지침

- DAO-based repository의 scalar aggregate query(COUNT, SUM 등)는
  `entityClass.find { ... }.count()`보다 table DSL path
  (`table.selectAll().where { ... }.count()`)를 우선합니다. DAO path는 scalar에
  불필요한 entity infrastructure를 만듭니다.
- pagination은 count + data라는 두 DB query가 항상 필요합니다. 이는 피할 수
  없으며 둘 다 같은 `@Transactional` context 안에서 실행되어 configured isolation
  level이 허용하는 만큼 일관성을 제공합니다.
