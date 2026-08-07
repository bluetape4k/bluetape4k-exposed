---
title: R2DBC 저장소 패턴
description: 트랜잭션 소유권을 숨기지 않고 매핑, Flow 조회, 배치, 감사 필드, soft delete를 구현합니다.
manualId: bluetape4k-exposed-r2dbc
chapterId: repository-patterns
---

# R2DBC 저장소 패턴

`R2dbcRepository<ID, E>`는 자주 쓰는 CRUD 작업을 제공한다. 반면 table 선택, ID 추출, 행 매핑, insert binding은 애플리케이션 코드에 남긴다. 이 인터페이스는 매핑과 쿼리 중복을 줄이는 도구이지, 업무 트랜잭션을 대신 관리하는 계층은 아니다.

## 가장 작은 저장소

```kotlin
object ActorTable : LongIdTable("actors") {
    val name = varchar("name", 100)
}

data class ActorRecord(val id: Long = 0, val name: String)

class ActorRepository : LongR2dbcRepository<ActorRecord> {
    override val table = ActorTable
    override fun extractId(entity: ActorRecord) = entity.id

    override suspend fun ResultRow.toEntity() = ActorRecord(
        id = this[ActorTable.id].value,
        name = this[ActorTable.name],
    )

    override fun BatchInsertStatement.bindSave(entity: ActorRecord) {
        this[ActorTable.name] = entity.name
    }
}
```

기본 `saveAll`을 쓰려면 `bindSave`를 구현해야 한다. 기본 구현은 `UnsupportedOperationException`을 던진다. 애플리케이션 컬럼을 빼먹은 배치 저장이 성공한 것처럼 보이는 상황을 막기 위한 계약이다.

## 결과 형태를 의도적으로 고른다

| 요구 | API | 동작 |
| --- | --- | --- |
| 반드시 있어야 하는 한 행 | `findById` | 없으면 실패 |
| 선택적인 한 행 | `findByIdOrNull` | 없으면 `null` |
| 조건 조회와 순차 처리 | `findAll`, `findBy`, `findByField` | `Flow<E>` 반환, 트랜잭션 안에서 수집 |
| 전체 건수를 포함한 페이지 | `findPage` | page number와 size를 검증하고 `ExposedPage` 반환 |
| 대량 insert | `saveAll`, `batchInsert` | table binding과 생성 ID 동작을 driver별로 검증 |
| 대량 upsert | `batchUpsert` | 충돌 처리가 DB마다 다르므로 통합 테스트 필요 |

모든 조회를 습관적으로 `List`로 바꾸지 않는다. 전체 결과가 메모리에 있어야 할 때는 목록이 맞고, 한 행씩 처리할 수 있다면 Flow가 맞다. 어느 쪽이든 terminal operation은 `suspendTransaction` 안에서 끝낸다.

## 감사 필드와 soft delete

`AuditableR2dbcRepository`의 audited update helper는 `updatedAt`과 `updatedBy`를 다룬다. 일반 `updateById`와 `updateAll`은 감사 컬럼을 자동으로 갱신하지 않는다. 감사 기록이 필요한 경로에서는 audited helper를 명시적으로 선택한다.

`SoftDeletedR2dbcRepository`는 활성·삭제 행 조회, soft delete, restore를 제공한다. 일반 `findAll`이 활성 행만 자동 필터링한다고 가정하지 않는다. 서비스 API에서 어떤 상태를 보여 줄지 분명히 정한다.

## 매핑은 좁고 엄격하게 둔다

필수 컬럼은 `ResultRow`에서 직접 읽고 스키마가 어긋나면 DB 경계 가까이에서 실패하게 만든다. 모든 행을 `Map<String, Any?>`로 바꾸면 nullability와 ID 변환 오류를 늦게 발견한다. 매핑 함수에서 외부 I/O를 호출하면 그 시간만큼 transaction과 connection 점유 시간이 늘어난다는 점도 함께 고려한다.

## 배치와 페이지 주의점

- 비어 있는 `saveAll` 입력은 아무 작업도 하지 않는다.
- 생성 ID 순서는 Exposed `batchInsert`가 보고한 순서를 따른다. 선택한 driver로 확인한다.
- `findPage`의 count와 page 조회 사이에 동시 쓰기가 발생할 수 있다. 일관된 snapshot이 필요하면 격리 수준을 검토한다.
- offset이 큰 페이지는 DB 비용이 커질 수 있다. 깊은 탐색에는 keyset pagination을 별도 쿼리로 검토한다.

## 학습 경로

H2에서 저장소 하나를 완성한 뒤 운영 DB driver로 같은 동작을 다시 검증한다. [`exposed-r2dbc-workshop`](https://github.com/bluetape4k/exposed-r2dbc-workshop)에는 DDL/DML, coroutine repository, Spring WebFlux, Ktor, cache, multi-tenancy, routing 실습이 단계별로 있다. 워크숍은 학습 경로이며, 이 매뉴얼의 API 기준은 아래 1.12.1 소스다.

## 근거 자료

- [`R2dbcRepository.kt`](../../../../../exposed/r2dbc/src/main/kotlin/io/bluetape4k/exposed/r2dbc/repository/R2dbcRepository.kt)
- [`AuditableR2dbcRepository.kt`](../../../../../exposed/r2dbc/src/main/kotlin/io/bluetape4k/exposed/r2dbc/repository/AuditableR2dbcRepository.kt)
- [`SoftDeletedR2dbcRepository.kt`](../../../../../exposed/r2dbc/src/main/kotlin/io/bluetape4k/exposed/r2dbc/repository/SoftDeletedR2dbcRepository.kt)
- [`exposed/r2dbc/build.gradle.kts`](../../../../../exposed/r2dbc/build.gradle.kts)
