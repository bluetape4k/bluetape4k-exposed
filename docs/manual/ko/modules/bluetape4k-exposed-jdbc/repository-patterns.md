---
title: JDBC 저장소 패턴
description: 트랜잭션 소유권을 숨기지 않으면서 매핑, CRUD, 일괄 처리, 감사 변경, 논리 삭제, cursor 조회를 구현합니다.
manualId: bluetape4k-exposed-jdbc
chapterId: repository-patterns
---

# JDBC 저장소 패턴

`JdbcRepository<ID, E>`는 테이블 중심 저장소 계약입니다. `ResultRow`를 레코드로 바꾸고 공통 CRUD, batch, 조회, 페이징 연산을 제공합니다. 도메인 서비스보다 의도적으로 작은 범위만 맡습니다. 검증, 트랜잭션 소유권, 권한 확인, 여러 저장소를 묶는 업무 흐름은 바깥 계층의 책임입니다.

## 최소 저장소 구현

```kotlin
class ActorRepository : LongJdbcRepository<ActorRecord> {
    override val table = ActorTable

    override fun extractId(entity: ActorRecord): Long = entity.id

    override fun ResultRow.toEntity() = ActorRecord(
        id = this[ActorTable.id].value,
        firstName = this[ActorTable.firstName],
        lastName = this[ActorTable.lastName],
    )

    override fun BatchInsertStatement.bindSave(entity: ActorRecord) {
        this[ActorTable.firstName] = entity.firstName
        this[ActorTable.lastName] = entity.lastName
    }
}
```

기본 `bindSave`는 예외를 던집니다. `saveAll`을 쓰려면 저장소 작성자가 INSERT에 참여할 컬럼을 직접 선언해야 합니다.

## 작업별 API

| 필요한 작업 | API | 기억할 계약 |
|---|---|---|
| 단건 조회 | `findById`, `findByIdOrNull`, `findFirstOrNull` | `findById`는 결과가 정확히 하나라고 가정 |
| 목록 조회 | `findAll`, `findBy`, `findWithFilters` | 현재 트랜잭션에서 결과를 완성 |
| 존재/개수 | `existsById`, `existsBy`, `count`, `countBy` | 조건을 테이블 정의 가까이에 유지 |
| 변경 | `updateById`, `updateAll`, `deleteById`, `deleteAll` | 감사 필드를 추가하지 않음 |
| 일괄 처리 | `saveAll`, `batchInsert`, `batchUpsert` | 생성 값과 dialect별 동작을 테스트해야 함 |
| 페이지 | `findPage` | 개수와 데이터를 별도 쿼리로 조회 |

행이 없을 수 있는 정상 흐름에서는 `findByIdOrNull`을 사용하세요. 행이 없다는 사실이 불변식 위반이라면 `findById`로 계약을 드러내는 편이 낫습니다.

## 감사 필드가 있는 update

`AuditableIdTable`에는 `AuditableJdbcRepository`를 구현하고 `auditedUpdateById` 또는 `auditedUpdateAll`을 사용하세요. 두 메서드는 호출자가 지정한 컬럼을 바꾸기 전에 `updatedAt = CURRENT_TIMESTAMP`, `updatedBy = UserContext.getCurrentUser()`를 설정합니다.

```kotlin
UserContext.withUser("admin") {
    transaction {
        repository.auditedUpdateById(id) {
            it[Users.name] = "Alice"
        }
    }
}
```

일반 `updateById`도 SQL은 정상적으로 실행되지만 감사용 수정 필드를 채우는 계약을 건너뜁니다.

## 논리 삭제

`SoftDeletedJdbcRepository`는 활성/삭제 행 개수, 상태별 조회와 페이지, `softDeleteById`, 복원 연산을 제공합니다. 상속한 모든 일반 메서드의 의미까지 바꾸지는 않습니다. 기본 `findAll`을 호출하는 코드는 삭제 행을 포함할지 따로 결정해야 합니다.

## 사용자 정의 조회

반복해서 쓰는 영속성 조건과 projection은 저장소에 둡니다. 업무 분기는 서비스에 남겨 두세요. 사용자 정의 검색에서 `andWhere`로 쿼리를 조립할 수 있지만 입력 검증과 권한 확인은 먼저 끝내야 합니다.

정렬된 대량 데이터를 나눠 읽을 때 `fetchBatchedResultFlow`로 JDBC 결과를 coroutine `Flow` 형태의 cursor batch로 받을 수 있습니다. Int/Long 또는 이에 대응하는 `EntityID` 컬럼만 cursor로 지원하며, 미리 지정한 limit/order는 거부합니다. flow는 수집이 끝나거나 취소되면 `finally`에서 임시 쿼리 변경을 되돌립니다. 다만 실제 I/O는 여전히 블로킹 JDBC입니다.

## 자주 생기는 문제

- 저장소가 자체 트랜잭션을 열면 서비스가 여러 작업을 원자적으로 묶기 어렵습니다.
- `bindSave`를 구현하지 않으면 `saveAll`이 즉시 실패합니다.
- 감사 테이블에 일반 update를 사용하면 수정 감사 필드가 빠집니다.
- 논리 삭제 테이블에서 기본 조회를 쓰면 삭제 행이 포함될 수 있습니다.
- batch upsert와 생성 값 처리는 dialect마다 다를 수 있습니다. 실제 배포 DB에서 검증하세요.
- cursor scan에는 안정적인 non-null 숫자 cursor가 필요합니다. 값이 바뀌는 정렬 컬럼은 행을 건너뛰거나 중복해서 읽을 수 있습니다.

## 학습 경로

저장소의 `ActorJdbcRepository`와 테스트부터 읽고, [`exposed-workshop`](https://github.com/bluetape4k/exposed-workshop)의 SQL DSL, 트랜잭션, 저장소, 운영 통합 예제를 순서대로 실행해 보세요. 서비스에 적용하기 전 [운영과 테스트](operations-testing.md)도 확인하세요.

## 근거 자료

- [JDBC 저장소](../../../../../exposed/jdbc/src/main/kotlin/io/bluetape4k/exposed/jdbc/repository/JdbcRepository.kt)
- [감사 JDBC 저장소](../../../../../exposed/jdbc/src/main/kotlin/io/bluetape4k/exposed/jdbc/repository/AuditableJdbcRepository.kt)
- [논리 삭제 JDBC 저장소](../../../../../exposed/jdbc/src/main/kotlin/io/bluetape4k/exposed/jdbc/repository/SoftDeletedJdbcRepository.kt)
- [JDBC batch 조회 Flow](../../../../../exposed/jdbc/src/main/kotlin/io/bluetape4k/exposed/jdbc/SuspendedQuery.kt)
- [Actor 저장소 예제](../../../../../exposed/jdbc/src/test/kotlin/io/bluetape4k/exposed/jdbc/repository/ActorJdbcRepository.kt)
