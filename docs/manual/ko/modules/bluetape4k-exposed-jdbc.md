---
manualId: "bluetape4k-exposed-jdbc"
id: "bluetape4k-exposed-jdbc"
title: "Exposed JDBC 라이브러리"
locale: "ko"
kind: "library"
gradlePath: ":bluetape4k-exposed-jdbc"
sourceDir: "exposed/jdbc"
releaseRef: "1.12.1"
artifact: io.github.bluetape4k.exposed:bluetape4k-exposed-jdbc
---

# Exposed JDBC 라이브러리

> blocking JDBC 영속성 도우미와 저장소 계약을 제공합니다. 트랜잭션과 연결 경계는 호출자나 프레임워크가 소유합니다.

![JDBC와 R2DBC 선택](../../assets/persistence/path-decision.png)

## 제공하는 기능 {#problem}

Exposed JDBC를 사용해도 record 매핑, CRUD, 페이징, batch, 감사 업데이트, 논리 삭제, 스키마 도우미, CTE, 코루틴에서 blocking 작업을 격리하는 규칙은 애플리케이션이 정해야 합니다. 이 모듈은 Exposed의 JDBC 트랜잭션 모델을 숨기지 않으면서 그 규칙을 제공합니다.

## 사용하기 좋은 경우 {#when-to-use}

데이터베이스 드라이버, 커넥션 풀, 프레임워크 트랜잭션 관리자, 호출 경로가 blocking이라면 JDBC를 선택합니다. Spring JDBC 트랜잭션과 기존 JDBC 관측 도구를 그대로 쓸 때도 가장 단순합니다. 코루틴 서비스에서도 blocking 작업을 적절한 dispatcher나 virtual thread로 격리한다면 JDBC를 사용할 수 있습니다.

## 의존성 좌표 {#coordinates}

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-jdbc")
    runtimeOnly("org.postgresql:postgresql") // 배포 DB에 맞는 드라이버
}
```

## 핵심 개념 {#concepts}

- `JdbcRepository`는 현재 `transaction {}` 안에서 실행하며 트랜잭션을 직접 열지 않습니다.
- `ResultRow.toEntity()`는 트랜잭션을 벗어나도 쓸 수 있는 record를 만들어야 합니다.
- `findPage`는 전체 개수와 데이터를 따로 조회합니다. 같은 snapshot인지 여부는 바깥 트랜잭션과 격리 수준에 달려 있습니다.
- `AuditableJdbcRepository`와 `SoftDeletedJdbcRepository`가 업데이트 의미를 분명히 나눕니다.
- 1.11의 `newSuspendedTransaction` 경로는 experimental이며 여전히 blocking JDBC입니다.

## 빠르게 시작하기 {#quick-start}

```kotlin
transaction(database) {
    val actor = repository.findByIdOrNull(42L)
    val page = repository.findPage(pageNumber = 0, pageSize = 20)
}
```

서비스나 프레임워크 경계에서 트랜잭션을 열고 저장소 작업을 모두 그 안에서 끝낸 뒤 detached 값만 반환합니다.

## 작업별 API {#api-by-task}

| 작업 | 1.11 API |
|---|---|
| 조회/존재 확인 | `findById`, `findByIdOrNull`, `findAll`, `findFirstOrNull`, `existsById` |
| 페이징 | `findPage` |
| 쓰기 | `saveAll`, `updateById`, `updateAll`, `deleteById`, batch insert/upsert |
| 감사 | `auditedUpdateById`, `auditedUpdateAll` |
| 논리 삭제 | `softDeleteById`, `restoreById`, `findActive`, `findDeleted` |
| SQL/스키마 | `CteQuery`, `SchemaUtilsExtensions`, `TableExtensions` |
| 코루틴 연결 | `newSuspendedTransaction`/virtual-thread 도우미와 명시적인 dispatcher 소유권 |

## 권장 패턴 {#patterns}

업무 한 건을 트랜잭션 하나에 넣습니다. 저장소 메서드가 트랜잭션을 열지 않게 두면 여러 호출을 한꺼번에 commit하거나 rollback할 수 있습니다. DAO 엔티티와 행은 경계 안에서 record로 바꾸세요. 페이지의 개수와 데이터가 반드시 일치해야 한다면 이를 보장하는 격리 수준을 선택하거나 쿼리 구조를 바꿔야 합니다.

## 연동 {#integrations}

Spring JDBC 모듈은 Spring 트랜잭션 관리자가 경계를 소유하도록 연결합니다. 캐시 모듈은 저장소 결과를 감싸지만 트랜잭션 규칙을 대신하지 않습니다. 데이터베이스 어댑터는 dialect 동작을 보완하고 JDBC 테스트 모듈은 fixture를 제공합니다.

## 설정 {#configuration}

`DataSource`, pool 크기와 timeout, 드라이버 속성, 격리 수준, Exposed `Database`는 애플리케이션이나 프레임워크 모듈에서 설정합니다. 코루틴 수만 보고 blocking pool 크기를 정하면 안 됩니다.

## 실패 유형과 해결 방법 {#failures}

- `transaction {}` 밖에서 저장소를 부르면 JDBC 트랜잭션 문맥이 없어 실패합니다.
- DAO 엔티티를 반환하고 나중에 지연 속성을 읽으면 닫힌 경계를 넘습니다.
- JDBC를 제한된 coroutine event-loop thread에서 실행하면 다른 작업까지 막힙니다.
- `findPage`를 쿼리 하나로 생각하면 전체 개수와 데이터가 어긋날 수 있습니다.
- 감사 테이블을 일반 update로 수정하면 감사용 수정 필드가 빠집니다.

## 운영 {#operations}

pool 대기 시간, 쿼리와 트랜잭션 시간, timeout/rollback 횟수, 느린 SQL을 관찰합니다. 감사 쓰기 전에는 요청이나 작업의 사용자 문맥을 설정하세요. batch 크기는 드라이버와 데이터베이스의 실제 제한을 측정해서 정합니다.

## 테스트 {#testing}

배포할 dialect를 Testcontainers와 `bluetape4k-exposed-jdbc-tests`로 검증합니다. commit/rollback, 격리 수준에 민감한 동작, batch 경계, 감사 필드, 논리 삭제, mapper를 확인하고 데이터베이스 정리는 항상 같은 방식으로 끝나게 만드세요.

## 학습 경로와 예제 {#workshops}

[트랜잭션 소유권](bluetape4k-exposed-jdbc/transaction-ownership.md), [저장소 패턴](bluetape4k-exposed-jdbc/repository-patterns.md), [운영과 테스트](bluetape4k-exposed-jdbc/operations-testing.md) 순서로 읽으세요. [JDBC/R2DBC 선택 가이드](../guides/jdbc-vs-r2dbc.md)에서 이 경로가 맞는 조건을 비교할 수 있습니다.

## 제약 사항 {#limitations}

JDBC 호출을 suspend 함수 안에 넣어도 blocking이라는 사실은 바뀌지 않습니다. 이 라이브러리는 드라이버, pool, 자동 트랜잭션, 보편적인 재시도 정책을 제공하지 않습니다.

<!-- release-readme-diagrams:start -->
## 배포본 다이어그램 {#release-diagrams}

아래 그림은 `1.12.1` 배포본의 README 자산을 해당 배포 커밋에서 직접 불러옵니다. 이후 SNAPSHOT이 아니라 이 매뉴얼 버전의 구조와 실행 흐름을 보여 줍니다. 미리보기를 누르면 같은 배포 커밋의 SVG 원본이 열립니다.

### JDBC 아키텍처 개요

[![JDBC 아키텍처 개요](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-jdbc-diagram-01.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-jdbc-diagram-01.svg)

_배포본 README: [`exposed/jdbc/README.ko.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/jdbc/README.ko.md)_

### Repository Contract 지도 다이어그램

[![Repository Contract 지도 다이어그램](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-jdbc-diagram-02.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-jdbc-diagram-02.svg)

_배포본 README: [`exposed/jdbc/README.ko.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/jdbc/README.ko.md)_

### VirtualThread transaction helper 다이어그램

[![VirtualThread transaction helper 다이어그램](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-jdbc-sequence-01.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-jdbc-sequence-01.svg)

_배포본 README: [`exposed/jdbc/README.ko.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/jdbc/README.ko.md)_

### findById — 다이어그램

[![findById — 다이어그램](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-jdbc-sequence-02.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-jdbc-sequence-02.svg)

_배포본 README: [`exposed/jdbc/README.ko.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/jdbc/README.ko.md)_

### save + findPage — 다이어그램

[![save + findPage — 다이어그램](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-jdbc-sequence-03.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-jdbc-sequence-03.svg)

_배포본 README: [`exposed/jdbc/README.ko.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/jdbc/README.ko.md)_

### softDeleteById / restoreById — 다이어그램

[![softDeleteById / restoreById — 다이어그램](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-jdbc-sequence-04.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-jdbc-sequence-04.svg)

_배포본 README: [`exposed/jdbc/README.ko.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/jdbc/README.ko.md)_

<!-- release-readme-diagrams:end -->

## 근거 자료 {#sources}

- [JDBC 빌드](../../../../exposed/jdbc/build.gradle.kts)
- [JDBC 저장소](../../../../exposed/jdbc/src/main/kotlin/io/bluetape4k/exposed/jdbc/repository/JdbcRepository.kt)
- [감사 저장소](../../../../exposed/jdbc/src/main/kotlin/io/bluetape4k/exposed/jdbc/repository/AuditableJdbcRepository.kt)
- [저장소 테스트](../../../../exposed/jdbc/src/test/kotlin/io/bluetape4k/exposed/jdbc/repository/ActorJdbcRepositoryTest.kt)
