# ExposedEventPublicationRepository.insertArchive: TOCTOU 존재 확인 후 삽입

**Date**: 2026-05-16
**Issue**: #88
**Module**: `exposed-spring-modulith`
**File**: `ExposedEventPublicationRepository.kt`

## 근본 원인

`insertArchive`는 SELECT + INSERT 두 단계 pattern을 사용했습니다.

```kotlin
val exists = archiveTable.selectAll()
    .where { archiveTable.id eq archiveId }
    .empty()
    .not()

if (exists) return  // TOCTOU window: another thread can INSERT between check and insert
archiveTable.insert { ... }
```

SELECT와 INSERT 사이에 다른 transaction이 같은 publication을 archive할 수 있어 두 번째
INSERT에서 unique constraint violation이 발생합니다. SELECT guard는 atomicity를
보장하지 않습니다.

## 수정

존재 확인을 제거합니다. INSERT를 직접 시도하고 SQL state `23xxx`(integrity constraint
violation — unique key already exists)는 idempotent condition으로 처리합니다.

```kotlin
try {
    archiveTable.insert { archive -> ... }
} catch (e: ExposedSQLException) {
    // SQL state 23xxx = integrity constraint violation (duplicate key)
    if (e.sqlState?.startsWith("23") == true) return
    throw e
}
```

SQL state prefix는 다음과 같습니다.

- `23505` — unique_violation (PostgreSQL, H2)
- `23000` — integrity constraint violation (MySQL)

둘 다 `23`으로 시작하므로 `startsWith("23")`는 지원하는 모든 database를 이식성 있게
처리합니다.

## 향후 지침

- 존재를 확인하려고 INSERT 앞에 SELECT를 두지 않습니다. 이는 항상 TOCTOU race입니다.
- idempotent archive/upsert operation에는 duplicate-key 처리(SQL state `23xxx`,
  `ON CONFLICT DO NOTHING`, DB-specific `INSERT IGNORE`)를 우선합니다.
- `ExposedSQLException`은 `java.sql.SQLException`을 확장하고 표준 JDBC interface로
  `getSQLState()`를 제공하므로 이식 가능한 constraint-violation 탐지에 사용합니다.
