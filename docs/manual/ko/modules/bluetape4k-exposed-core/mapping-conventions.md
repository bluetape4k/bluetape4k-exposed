---
title: 매핑 규칙
description: 테이블, 조회 행, DAO 엔티티, 애플리케이션 레코드를 트랜잭션에 안전한 영속성 경계로 나눕니다.
manualId: bluetape4k-exposed-core
chapterId: mapping-conventions
---

# 매핑 규칙

Exposed 경계에는 세 가지 형태가 있습니다. `Table`은 SQL 컬럼을 설명하고, `ResultRow`는 조회 결과 한 행을 나타냅니다. 애플리케이션 레코드는 트랜잭션 밖으로 안전하게 전달할 값입니다. 이 역할을 나누면 JDBC와 R2DBC가 같은 스키마를 공유하면서도 드라이버 상태를 도메인 코드에 흘리지 않을 수 있습니다.

## 행을 명시적으로 변환하기

저장소의 `ResultRow.toEntity`를 작고 결정적인 변환 함수로 구현하세요. 필수 컬럼은 non-null 타입으로 읽고, nullable 컬럼은 nullable 접근자나 Exposed의 일반 인덱싱을 명시적으로 사용합니다.

```kotlin
data class ActorRecord(
    val id: Long,
    val firstName: String,
    val lastName: String,
)

override fun ResultRow.toEntity() = ActorRecord(
    id = this[ActorTable.id].value,
    firstName = this[ActorTable.firstName],
    lastName = this[ActorTable.lastName],
)
```

core의 `ResultRow` 확장은 기본 타입, 문자열, 날짜와 시각, UUID, 바이트 배열, 숫자 타입마다 필수/nullable 접근자 쌍을 제공합니다. 필수 접근자는 선택하지 않은 식이거나 값이 null이면 구체적인 오류를 냅니다. 도메인 기본값을 조용히 만들어 주지는 않습니다.

## DAO 엔티티는 트랜잭션 안에서 끝내기

Exposed DAO 엔티티는 트랜잭션에 묶인 객체입니다. 위임 프로퍼티를 읽고 레코드로 바꾸는 작업까지 트랜잭션이 닫히기 전에 마치세요. DAO 엔티티를 HTTP 직렬화기로 넘기거나 나중에 실행할 작업 큐에 넣으면 안 됩니다.

```kotlin
val record = transaction {
    ProductEntity.findById(id)?.let {
        ProductRecord(it.id.value, it.name, it.price, it.stock)
    }
}
```

이 모듈의 엔티티 동등성 도우미는 `EntityID` 래퍼가 아니라 원시 ID를 비교합니다. 영속성 식별자를 비교할 때는 알맞지만 값 객체의 도메인 동등성을 대신하지는 않습니다.

## 쓰기 매핑을 빠짐없이 선언하기

`JdbcRepository`와 `R2dbcRepository`의 기본 `saveAll`을 쓰려면 `BatchInsertStatement.bindSave`를 구현해야 합니다. ID를 제외한 필수 컬럼을 모두 나열하세요. 기본 구현을 그대로 두면 `UnsupportedOperationException`이 발생합니다. 일부 값만 저장하는 것보다 초기에 실패하는 편이 안전합니다.

```kotlin
override fun BatchInsertStatement.bindSave(actor: ActorRecord) {
    this[ActorTable.firstName] = actor.firstName
    this[ActorTable.lastName] = actor.lastName
}
```

감사 테이블에서는 행 매핑과 감사 필드 변경을 구분해야 합니다. `AuditableEntity.flush()`는 `createdBy`나 `updatedBy`를 채우지만 `updatedAt`은 `auditedUpdateById` 또는 `auditedUpdateAll`이 보장합니다. 저장소 코드에서 이 차이를 숨기지 마세요.

## 페이지 결과는 스냅샷 보장이 아니다

`ExposedPage`는 데이터, 전체 개수, 페이지 번호, 페이지 크기를 담고 이동 가능 여부를 계산합니다. 저장소의 `findPage`는 먼저 개수를 세고 데이터를 다시 조회합니다. 그 사이에 다른 트랜잭션이 행을 넣거나 지우면 두 값이 달라질 수 있습니다. 정확한 일관성이 필요하면 격리 수준으로 보장하고, 그렇지 않다면 운영 화면의 근사값이라는 계약을 명확히 하세요.

## 자주 생기는 문제

- DAO 엔티티를 트랜잭션 밖으로 반환하면 나중에 프로퍼티를 읽을 때 실패합니다.
- nullable DB 컬럼을 아무 규칙 없이 non-null 프로퍼티에 매핑하면 데이터 불일치가 런타임 오류로 나타납니다.
- SELECT 컬럼을 바꾼 뒤 기존 행 매퍼를 재사용하면 필수 식이 결과에 없을 수 있습니다.
- 직렬화나 압축 컬럼을 평범한 바이트 배열처럼 다루면 codec과 스키마 진화 계약을 잃습니다.
- 모든 값에 `?: 기본값`을 붙이면 잘못 저장된 데이터를 숨기게 됩니다.

## 경계 테스트하기

대표 행으로 매퍼를 검증하고 실제 지원 dialect에서 저장소 테스트를 실행하세요. null 처리, 생성 ID, batch binding, 페이지 입력 조건(`pageNumber >= 0`, `pageSize > 0`), 동시 쓰기 중 페이지 결과를 포함해야 합니다. 직렬화나 압축 데이터를 저장한다면 현재 codec이 쓴 값만 읽지 말고 이전 형식 fixture도 준비하는 편이 좋습니다.

다음으로 [JDBC 저장소 패턴](../bluetape4k-exposed-jdbc/repository-patterns.md)이나 [R2DBC 저장소 가이드](../bluetape4k-exposed-r2dbc/repository-patterns.md)를 읽으세요.

## 근거 자료

- [ResultRow 확장](../../../../../exposed/core/src/main/kotlin/io/bluetape4k/exposed/core/ResultRowExtensions.kt)
- [엔티티 확장](../../../../../exposed/dao/src/main/kotlin/io/bluetape4k/exposed/dao/EntityExtensions.kt)
- [감사 엔티티](../../../../../exposed/dao/src/main/kotlin/io/bluetape4k/exposed/dao/auditable/AuditableEntity.kt)
- [페이지 값](../../../../../exposed/core/src/main/kotlin/io/bluetape4k/exposed/core/ExposedPage.kt)
- [JDBC 저장소 계약](../../../../../exposed/jdbc/src/main/kotlin/io/bluetape4k/exposed/jdbc/repository/JdbcRepository.kt)
