---
title: 엔티티와 ID 모델
description: 1.11에서 제공하는 클라이언트 생성 ID를 고르고 Exposed EntityID를 영속성 경계 안에 유지하는 방법을 설명합니다.
manualId: bluetape4k-exposed-core
chapterId: entity-id-model
---

# 엔티티와 ID 모델

`bluetape4k-exposed-core`는 INSERT를 보내기 전에 애플리케이션에서 ID를 만들 수 있도록 Exposed의 `IdTable`을 확장합니다. 이벤트나 캐시 키, 연관 행을 먼저 구성해야 할 때 유용합니다. 데이터베이스 시퀀스와는 동작 방식이 다릅니다. 컬럼에 Exposed의 `clientDefault`를 사용하므로 Kotlin 프로세스가 값을 만듭니다.

## ID의 성질을 보고 선택하기

| 타입 | 저장 형식 | 생성 방식과 정렬 특성 | 알맞은 경우 |
|---|---|---|---|
| `KsuidTable` | `varchar(27)` | 클라이언트에서 KSUID 생성 | 정렬 가능한 문자열 ID와 다른 시스템과의 연동이 필요할 때 |
| `KsuidMillisTable` | `varchar(27)` | 밀리초 단위 KSUID 생성 | 이미 이 저장소의 millisecond KSUID 규칙을 쓰고 있을 때 |
| `UlidTable` | `varchar(26)` | 클라이언트에서 단조 증가 ULID 생성 | 같은 밀리초 안에서도 사전식 정렬 순서를 유지해야 할 때 |
| `SnowflakeIdTable` | `long` | 전역 Snowflake 생성기 사용 | 짧은 숫자 ID가 필요할 때 |
| `TimebasedUUIDTable` | UUID | 클라이언트에서 시간 기반 UUID 생성 | 스키마와 드라이버가 UUID 컬럼을 사용할 때 |
| `SoftDeletedIdTable` | 직접 정의 | `is_deleted = false`만 추가 | ID와 별개로 논리 삭제 상태가 필요할 때 |

각 기반 타입은 ID 컬럼과 기본 키를 고정합니다. `KsuidTable`의 ID는 27자 `varchar`이고, `UlidTable`은 26자 컬럼과 공유 단조 증가 생성기를 사용합니다.

```kotlin
object Events : UlidTable("events") {
    val eventType = varchar("event_type", 80)
    val payload = text("payload")
}

transaction {
    val id = Events.insertAndGetId {
        it[eventType] = "order.accepted"
        it[payload] = "{}"
    }.value
}
```

## EntityID와 도메인 ID의 경계

Exposed는 `IdTable`의 키를 `EntityID<ID>`로 감쌉니다. 이 래퍼는 영속성 모델 안에 두는 편이 좋습니다. 저장소가 반환하는 레코드에는 보통 원시 `ID`를 담고, DAO 엔티티는 트랜잭션 안에서 `idValue`로 원시 값을 꺼냅니다.

`HasIdentifier<ID>`의 ID는 nullable입니다. 아직 저장하지 않은 객체와 저장된 객체를 같은 레코드 형태로 표현할 수 있기 때문입니다. 다만 저장소 구현이 실제로 DB 생성 ID를 사용하지 않는다면 `null`을 DB 생성 계약으로 해석해서는 안 됩니다.

```kotlin
data class EventRecord(
    override val id: String? = null,
    val eventType: String,
) : HasIdentifier<String>
```

## 감사 필드와 논리 삭제는 별개의 규칙이다

`AuditableIdTable`은 `created_by`, `created_at`, `updated_by`, `updated_at`을 추가합니다. `createdBy`는 `UserContext`에서 가져오고, `createdAt`은 DB의 `CURRENT_TIMESTAMP`에 맡깁니다. 일반 update로는 수정 감사 필드가 채워지지 않습니다. JDBC 또는 R2DBC의 감사 전용 저장소 메서드를 사용해야 합니다.

`SoftDeletedIdTable`은 `isDeleted`만 추가합니다. 일반 저장소 조회가 삭제된 행을 자동으로 가리지 않습니다. 논리 삭제 저장소 API를 사용하거나 조건을 명시하세요.

## 자주 생기는 문제

- DB 시퀀스와 Kotlin의 클라이언트 ID를 함께 선언하면 ID를 정하는 주체가 둘이 됩니다.
- 데이터가 쌓인 뒤 ID 계열을 바꾸면 컬럼 길이, 정렬 방식, 외래 키 타입까지 달라질 수 있습니다. 단순 리팩터링이 아니라 스키마 마이그레이션으로 다뤄야 합니다.
- 시간 기반 ID는 생성 시각의 대략적인 순서를 드러낼 수 있습니다.
- `UserContext`는 값이 없으면 `system`을 사용합니다. 감사 사용자 정보가 필요하면 요청이나 작업 진입점에서 사용자를 바인딩하세요.

## 테스트와 운영

생성 ID의 중복 여부, 인코딩 길이, 실제로 의존하는 정렬 특성을 테스트하세요. 쓰기량이 많은 테이블은 운영 DB에서 인덱스 지역성과 페이지 분할을 관찰해야 합니다. 시간 기반 형식을 쓴다는 이유만으로 모든 인덱스 문제가 해결되지는 않습니다.

[매핑 규칙](mapping-conventions.md)을 읽은 뒤 [JDBC](../bluetape4k-exposed-jdbc.md) 또는 [R2DBC](../bluetape4k-exposed-r2dbc.md) 실행 경로를 선택하세요.

## 근거 자료

- [컬럼 생성 확장](../../../../../exposed/core/src/main/kotlin/io/bluetape4k/exposed/core/ColumnExtensions.kt)
- [KSUID 테이블](../../../../../exposed/core/src/main/kotlin/io/bluetape4k/exposed/core/dao/id/KsuidTable.kt)
- [ULID 테이블](../../../../../exposed/core/src/main/kotlin/io/bluetape4k/exposed/core/dao/id/UlidTable.kt)
- [논리 삭제 테이블](../../../../../exposed/core/src/main/kotlin/io/bluetape4k/exposed/core/dao/id/SoftDeletedIdTable.kt)
- [ID 계약](../../../../../exposed/core/src/main/kotlin/io/bluetape4k/exposed/core/HasIdentifier.kt)
