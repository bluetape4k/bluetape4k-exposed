---
manualId: "bluetape4k-exposed-r2dbc"
id: "bluetape4k-exposed-r2dbc"
title: "Exposed R2DBC 라이브러리"
locale: "ko"
kind: "library"
gradlePath: ":bluetape4k-exposed-r2dbc"
sourceDir: "exposed/r2dbc"
releaseRef: "1.12.1"
artifact: io.github.bluetape4k.exposed:bluetape4k-exposed-r2dbc
---

# Exposed R2DBC 라이브러리

> 처음부터 끝까지 R2DBC로 이어지는 경로에 suspend 함수와 `Flow` 기반 영속성 도우미를 제공합니다. `suspendTransaction`과 연결 문맥은 호출자나 프레임워크가 소유합니다.

![트랜잭션 소유권](../../assets/persistence/transaction-ownership.png)

## 제공하는 기능 {#problem}

R2DBC는 메서드에 `suspend`만 붙이는 작업이 아닙니다. 드라이버 접근, 연결 소유권, 트랜잭션 전파, 취소, 결과 수집, Spring 연동, 테스트까지 non-blocking 계약으로 맞춰야 합니다. 이 모듈은 트랜잭션 경계를 숨기지 않으면서 저장소와 DSL 도우미를 제공합니다.

## 사용하기 좋은 경우 {#when-to-use}

드라이버, 프레임워크, 트랜잭션 관리자, 요청 전체 경로가 non-blocking이고 데이터베이스 응답을 기다리는 동안 platform thread 하나를 계속 점유하지 않는 동시성 모델이 필요할 때 선택합니다. R2DBC가 자동으로 더 빠르다는 이유로 고르면 안 됩니다. 지연 시간과 처리량은 드라이버, 데이터베이스, pool, 쿼리, 부하 형태에 따라 달라집니다.

## 의존성 좌표 {#coordinates}

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-r2dbc")
    runtimeOnly("org.postgresql:r2dbc-postgresql") // 배포 DB에 맞는 드라이버
}
```

## 핵심 개념 {#concepts}

- `R2dbcRepository`는 현재 `suspendTransaction` 안에서 실행하며 트랜잭션을 직접 열지 않습니다.
- 단건 작업은 suspend 함수이고 여러 행 조회는 cold `Flow`를 반환합니다.
- 저장소가 반환한 `Flow`는 연결을 소유한 트랜잭션 안에서 collect해야 합니다.
- 취소는 수집을 중단할 수 있지만 서버 작업이 얼마나 빨리 멈추고 자원이 정리되는지는 드라이버와 데이터베이스에 달려 있습니다.
- `findPage`는 호출자 트랜잭션 안에서 전체 개수와 데이터를 각각 조회합니다.

## 빠르게 시작하기 {#quick-start}

```kotlin
suspendTransaction(db = database) {
    val actors = repository.findAll(limit = 20).toList()
    val page = repository.findPage(pageNumber = 0, pageSize = 20)
}
```

데이터베이스를 읽는 Flow는 생성과 최종 collect를 같은 트랜잭션 경계 안에서 끝냅니다.

## 작업별 API {#api-by-task}

| 작업 | 1.11 API |
|---|---|
| 단건 읽기/쓰기 | suspend `findById`, `saveAll`, `updateById`, `deleteById` |
| 여러 행 조회 | `Flow`를 반환하는 `findAll`, `findBy`, `findAllByIds` |
| 페이징 | suspend `findPage` |
| 감사 | `auditedUpdateById`, `auditedUpdateAll` |
| 논리 삭제 | suspend 쓰기와 `findActive`/`findDeleted` Flow |
| 쿼리 도우미 | `CteQuery`, `QueryExtensions`, `ReadableExtensions`, 테이블 확장 |
| 충돌 처리 | R2DBC batch insert-on-conflict 도우미 |

## 권장 패턴 {#patterns}

업무 한 건을 coroutine 트랜잭션 하나로 묶습니다. 저장소가 별도 트랜잭션을 열면 여러 쓰기를 함께 rollback할 수 없으므로 저장소는 현재 문맥만 사용해야 합니다. 경계가 닫히기 전에 Flow를 collect하고 행을 detached 값으로 바꾸며 blocking 라이브러리는 R2DBC 호출 경로에서 빼세요.

## 연동 {#integrations}

Spring R2DBC 모듈은 프레임워크가 트랜잭션 문맥을 제공하도록 연결합니다. R2DBC 캐시 모듈은 suspend 경계를 유지하지만 캐시 클라이언트의 생명주기와 장애 의미가 추가됩니다. 데이터베이스 어댑터도 선택한 R2DBC 동작을 명시적으로 지원해야 합니다.

## 설정 {#configuration}

R2DBC `ConnectionFactory`, pool, 드라이버 옵션, timeout, Exposed `R2dbcDatabase`는 애플리케이션이나 프레임워크 계층에서 설정합니다. pool 크기는 coroutine 수가 아니라 실제 데이터베이스 처리 용량을 측정해서 정하세요.

## 실패 유형과 해결 방법 {#failures}

- `suspendTransaction`이 끝난 뒤 cold `Flow`를 collect하면 트랜잭션과 연결 문맥을 잃습니다.
- blocking codec, 캐시 클라이언트, JDBC 호출을 섞으면 coroutine thread를 막습니다.
- 취소가 항상 서버 쿼리까지 즉시 멈춘다고 보장할 수 없습니다.
- Spring 문맥과 수동 트랜잭션을 섞으면 업무 한 건이 여러 연결로 나뉠 수 있습니다.
- 문법만 바꾸고 드라이버와 테스트를 그대로 두면 절반만 non-blocking인 시스템이 됩니다.

## 운영 {#operations}

pool 획득 시간, 활성 연결 수, 트랜잭션과 쿼리 시간, 취소, timeout, 오류 신호를 관찰합니다. 결과 스트림의 크기를 제한하고 일부만 읽거나 취소했을 때 드라이버가 자원을 정리하는지 확인하세요.

## 테스트 {#testing}

R2DBC 드라이버와 Testcontainers, `bluetape4k-exposed-r2dbc-tests`를 사용합니다. 취소, rollback, 경계 안 collect, pool 고갈, dialect 동작, 실패 뒤 정리를 검증하세요. JDBC 테스트만 통과했다고 R2DBC 경로가 검증된 것은 아닙니다.

## 학습 경로와 예제 {#workshops}

[코루틴 트랜잭션](bluetape4k-exposed-r2dbc/coroutine-transactions.md), [저장소 패턴](bluetape4k-exposed-r2dbc/repository-patterns.md), [취소와 테스트](bluetape4k-exposed-r2dbc/cancellation-and-testing.md) 순서로 읽으세요. [JDBC/R2DBC 선택 가이드](../guides/jdbc-vs-r2dbc.md)에서 마이그레이션 비용과 운영 차이를 함께 비교합니다.

## 제약 사항 {#limitations}

R2DBC는 자동 성능 개선 수단이 아니며 blocking 의존성을 non-blocking으로 바꾸지도 않습니다. 드라이버, 보편적인 취소 보장, 암묵적인 트랜잭션은 이 라이브러리가 제공하지 않습니다.

<!-- release-readme-diagrams:start -->
## 배포본 다이어그램 {#release-diagrams}

아래 그림은 `1.12.1` 배포본의 README 자산을 해당 배포 커밋에서 직접 불러옵니다. 이후 SNAPSHOT이 아니라 이 매뉴얼 버전의 구조와 실행 흐름을 보여 줍니다. 미리보기를 누르면 같은 배포 커밋의 SVG 원본이 열립니다.

### Core R2DBC repository 구조도

[![Core R2DBC repository 구조도](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-r2dbc-diagram-01.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-r2dbc-diagram-01.svg)

_배포본 README: [`exposed/r2dbc/README.ko.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/r2dbc/README.ko.md)_

### R2DBC repository capability 지도

[![R2DBC repository capability 지도](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-r2dbc-diagram-02.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-r2dbc-diagram-02.svg)

_배포본 README: [`exposed/r2dbc/README.ko.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/r2dbc/README.ko.md)_

### R2DBC suspend transaction 시퀀스 다이어그램

[![R2DBC suspend transaction 시퀀스 다이어그램](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-r2dbc-sequence-01.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-r2dbc-sequence-01.svg)

_배포본 README: [`exposed/r2dbc/README.ko.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/r2dbc/README.ko.md)_

### R2DBC soft-delete visibility 처리 흐름

[![R2DBC soft-delete visibility 처리 흐름](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-r2dbc-sequence-02.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-r2dbc-sequence-02.svg)

_배포본 README: [`exposed/r2dbc/README.ko.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/r2dbc/README.ko.md)_

<!-- release-readme-diagrams:end -->

## 근거 자료 {#sources}

- [R2DBC 빌드](../../../../exposed/r2dbc/build.gradle.kts)
- [R2DBC 저장소](../../../../exposed/r2dbc/src/main/kotlin/io/bluetape4k/exposed/r2dbc/repository/R2dbcRepository.kt)
- [R2DBC 감사 저장소](../../../../exposed/r2dbc/src/main/kotlin/io/bluetape4k/exposed/r2dbc/repository/AuditableR2dbcRepository.kt)
- [저장소 테스트](../../../../exposed/r2dbc/src/test/kotlin/io/bluetape4k/exposed/r2dbc/repository/ActorR2dbcRepositoryTest.kt)
