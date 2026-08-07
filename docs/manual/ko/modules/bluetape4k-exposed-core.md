---
manualId: "bluetape4k-exposed-core"
id: "bluetape4k-exposed-core"
title: "Exposed 핵심 라이브러리"
locale: "ko"
kind: "library"
gradlePath: ":bluetape4k-exposed-core"
sourceDir: "exposed/core"
releaseRef: "1.12.1"
artifact: io.github.bluetape4k.exposed:bluetape4k-exposed-core
---

# Exposed 핵심 라이브러리

> JDBC와 R2DBC가 함께 쓰는 테이블, ID, 행 매핑, 감사 필드, 페이징, 컬럼 타입 기반을 제공합니다.

## 제공하는 기능 {#problem}

Exposed가 DSL과 테이블 모델을 제공하더라도 실제 프로젝트에서는 생성형 ID 선언, `ResultRow` 변환, 페이지 값, 감사 컬럼, 논리 삭제 플래그, 특수 컬럼 타입을 반복해서 작성하게 됩니다. core는 이런 공통 규칙을 모으되 데이터베이스 연결이나 트랜잭션은 열지 않습니다.

## 사용하기 좋은 경우 {#when-to-use}

`bluetape4k-exposed-jdbc`나 `bluetape4k-exposed-r2dbc`의 공통 기반으로 사용합니다. 최종 드라이버 경로와 무관하게 스키마 모듈을 따로 컴파일해야 할 때도 알맞습니다.

## 의존성 좌표 {#coordinates}

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-core")
}
```

## 핵심 개념 {#concepts}

- ID 테이블 계열은 `clientDefault`로 애플리케이션에서 ID를 만듭니다.
- `ExposedPage`는 페이지 메타데이터와 데이터를 담습니다. 저장소의 `findPage`는 전체 개수와 데이터를 각각 조회합니다.
- `AuditableIdTable`은 INSERT 때 `createdBy`를 채웁니다. `updatedAt`과 `updatedBy`는 감사 전용 저장소 메서드로 수정할 때만 보장됩니다.
- `UserContext`는 `ScopedValue`, thread-local, `system` 순서로 현재 사용자를 찾습니다.
- 논리 삭제는 테이블 규칙입니다. 일반 저장소 조회가 삭제 행을 자동으로 숨기지는 않습니다.

## 빠르게 시작하기 {#quick-start}

```kotlin
object Events : UlidTable("events") {
    val payload = varchar("payload", 400)
}
```

ID는 클라이언트 기본값이므로 데이터베이스 시퀀스에 맡기지 않고 INSERT를 구성할 수 있습니다.

## 작업별 API {#api-by-task}

| 작업 | 1.11 API |
|---|---|
| 생성형 ID | `KsuidTable`, `KsuidMillisTable`, `UlidTable`, `SnowflakeIdTable`, `TimebasedUUIDTable` |
| 논리 삭제 스키마 | `SoftDeletedIdTable` |
| 감사 스키마와 사용자 | `AuditableIdTable`, `AuditableIntIdTable`, `AuditableLongIdTable`, `AuditableUUIDTable`, `UserContext` |
| 페이징 값 | `ExposedPage` |
| 행과 컬럼 변환 | `ResultRowExtensions`, `ColumnExtensions`, `ExposedColumnSupports` |
| 특수 데이터 | 압축, 직렬화, INET, 전화번호, BLOB 컬럼 도우미 |

## 권장 패턴 {#patterns}

테이블 선언과 순수한 행 매퍼는 공통 영속성 모델에 둡니다. JDBC와 R2DBC 선택 및 트랜잭션 경계는 서비스나 프레임워크가 소유해야 합니다. aggregate마다 ID 전략을 하나로 정하고 정렬 특성과 저장 형식을 스키마 결정에 남기세요.

## 연동 {#integrations}

JDBC, R2DBC, DAO, 직렬화, 측정, 캐시, 데이터베이스 어댑터가 core를 사용합니다. 선택형 컬럼 타입은 그 타입을 구현하는 런타임 라이브러리가 별도로 필요합니다.

## 설정 {#configuration}

core에는 연결 설정이 없습니다. ID 생성기와 선택형 codec은 애플리케이션에서 설정하고 `UserContext`는 요청이나 작업 진입점에서 바인딩합니다.

## 실패 유형과 해결 방법 {#failures}

- 생성형 ID를 DB 생성 값으로 이해하면 마이그레이션 설계가 달라질 수 있습니다.
- 감사 테이블을 일반 update로 수정하면 감사용 수정 필드가 자동으로 채워지지 않습니다.
- 코루틴 dispatcher가 바뀌는 경로에서 thread-local만 쓰면 사용자 문맥이 끊깁니다. `withCoroutineUser`나 `asContextElement`를 사용하세요.
- `findPage`의 전체 개수와 데이터가 같은 snapshot이라고 가정하면 안 됩니다. 필요하다면 바깥 트랜잭션과 격리 수준으로 보장해야 합니다.

## 운영 {#operations}

감사 시각은 UTC로 저장하고 쓰기량이 많은 테이블은 ID의 인덱스 지역성을 확인합니다. 논리 삭제 데이터의 보관 기간과 물리 삭제 작업도 운영 정책으로 따로 정해야 합니다.

## 테스트 {#testing}

테이블 기본값과 컬럼 타입은 실제 배포할 dialect에서 검증하세요. 저장소의 core 통합 테스트는 JDBC 테스트 지원을 사용해 H2, MariaDB, MySQL, PostgreSQL, pgjdbc-ng 등을 필요한 범위에서 확인합니다.

## 학습 경로와 예제 {#workshops}

[엔티티와 ID 모델](bluetape4k-exposed-core/entity-id-model.md), [매핑 규칙](bluetape4k-exposed-core/mapping-conventions.md)을 읽고 [트랜잭션 경계](../guides/transaction-boundaries.md)로 이어가세요.

## 제약 사항 {#limitations}

core는 트랜잭션, 연결, Spring bean, 드라이버 선택, 저장소 생명주기를 소유하지 않습니다. 1.11 매뉴얼에서는 이후 develop 브랜치에 추가된 DDD API를 사용할 수 없습니다.

<!-- release-readme-diagrams:start -->
## 배포본 다이어그램 {#release-diagrams}

아래 그림은 `1.12.1` 배포본의 README 자산을 해당 배포 커밋에서 직접 불러옵니다. 이후 SNAPSHOT이 아니라 이 매뉴얼 버전의 구조와 실행 흐름을 보여 줍니다. 미리보기를 누르면 같은 배포 커밋의 SVG 원본이 열립니다.

### Auditable UML 클래스 다이어그램

[![Auditable UML 클래스 다이어그램](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-core-diagram-01.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-core-diagram-01.svg)

_배포본 README: [`exposed/core/README.ko.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/core/README.ko.md)_

### Column Type 파이프라인 지도

[![Column Type 파이프라인 지도](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-core-diagram-02.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-core-diagram-02.svg)

_배포본 README: [`exposed/core/README.ko.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/core/README.ko.md)_

### IdTable 선택 기준표

[![IdTable 선택 기준표](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-core-diagram-03.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-core-diagram-03.svg)

_배포본 README: [`exposed/core/README.ko.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/core/README.ko.md)_

### ExposedPage Data Model

[![ExposedPage Data Model](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-core-diagram-04.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-core-diagram-04.svg)

_배포본 README: [`exposed/core/README.ko.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/core/README.ko.md)_

### UserContext — 다이어그램

[![UserContext — 다이어그램](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-core-sequence-01.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-core-sequence-01.svg)

_배포본 README: [`exposed/core/README.ko.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/core/README.ko.md)_

<!-- release-readme-diagrams:end -->

## 근거 자료 {#sources}

- [core 빌드](../../../../exposed/core/build.gradle.kts)
- [컬럼 확장](../../../../exposed/core/src/main/kotlin/io/bluetape4k/exposed/core/ColumnExtensions.kt)
- [사용자 문맥](../../../../exposed/core/src/main/kotlin/io/bluetape4k/exposed/core/auditable/UserContext.kt)
- [감사 테이블](../../../../exposed/core/src/main/kotlin/io/bluetape4k/exposed/core/auditable/AuditableIdTable.kt)
