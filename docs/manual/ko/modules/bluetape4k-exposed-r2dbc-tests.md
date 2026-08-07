---
manualId: "bluetape4k-exposed-r2dbc-tests"
id: "bluetape4k-exposed-r2dbc-tests"
title: "Exposed R2DBC 테스트 지원"
locale: "ko"
kind: "library"
gradlePath: ":bluetape4k-exposed-r2dbc-tests"
sourceDir: "exposed/r2dbc-tests"
releaseRef: "1.12.1"
artifact: io.github.bluetape4k.exposed:bluetape4k-exposed-r2dbc-tests
---

# Exposed R2DBC 테스트 지원

> suspend 트랜잭션, 스키마/테이블 생명주기, R2DBC 드라이버, assertion, Testcontainers fixture를 제공합니다.

## 제공하는 기능 {#problem}

R2DBC 테스트는 coroutine 트랜잭션 전파, 드라이버 동작, suspend나 실패 뒤의 정리, 실제 dialect 차이를 검증해야 합니다. JDBC fixture만 재사용하면 R2DBC에서 가장 중요한 경계가 빠집니다.

## 사용하기 좋은 경우 {#when-to-use}

R2DBC 저장소, 쿼리, 드라이버, 취소, 프레임워크 연동을 테스트할 때 사용합니다. 애플리케이션 runtime 모듈이 아니라 테스트 의존성입니다.

## 의존성 좌표 {#coordinates}

중앙 BOM과 함께 `testImplementation("io.github.bluetape4k.exposed:bluetape4k-exposed-r2dbc-tests")`를 선언합니다.

## 핵심 개념 {#concepts}

`withDb`는 coroutine worker를 막지 않도록 DB별 semaphore를 획득하고 `maxAttempts = 1`인 `suspendTransaction`을 연 뒤 임시 설정을 복원합니다. `withTables`와 `withSchemas`는 fixture를 만들고 commit한 다음 정리합니다. 스키마 삭제까지 실패하면 처음 난 오류를 유지하고 삭제 오류는 suppressed 예외로 남깁니다.

## 빠르게 시작하기 {#quick-start}

```kotlin
withTables(TestDB.POSTGRESQL, Actors) {
    Actors.insert { it[name] = "Ada" }
    Actors.selectAll().count() shouldBeEqualTo 1L
}
```

## 작업별 API {#api-by-task}

| 작업 | API |
|---|---|
| suspend 트랜잭션 fixture | `withDb` |
| 테이블/스키마 생명주기 | `withTables`, `withSchemas` |
| auto-commit 확인 | `withAutoCommit` |
| R2DBC DB 행렬 | `TestDB`, 설정과 container 도우미 |
| 공통 assertion/스키마 | assertion과 `shared` 패키지 |

## 권장 패턴 {#patterns}

데이터베이스 Flow는 fixture 트랜잭션 안에서 collect합니다. 배포할 R2DBC 드라이버와 맞는 Testcontainer를 사용하세요. 취소 테스트에는 timeout을 두고 다음 테스트가 같은 DB를 쓰기 전에 자원이 정리됐는지 확인합니다.

## 연동 {#integrations}

테스트에서 R2DBC SPI/pool, H2/MariaDB/MySQL/PostgreSQL R2DBC 드라이버, JUnit 5, Testcontainers를 사용할 수 있게 구성했습니다.

## 설정 {#configuration}

fixture마다 `TestDB`, 드라이버 옵션, 선택적인 `DatabaseConfig`를 정합니다. 임시 데이터베이스 참조는 실행 뒤 원래 값으로 돌아갑니다.

## 실패 유형과 해결 방법 {#failures}

fixture가 닫힌 뒤 Flow를 collect하면 트랜잭션 문맥을 잃습니다. coroutine worker에서 semaphore 획득을 blocking으로 수행하면 테스트 동시성이 떨어지므로 fixture는 그 작업을 `Dispatchers.IO`로 옮깁니다. 정리 실패가 원래 assertion 오류를 덮어써서도 안 됩니다.

## 운영 {#operations}

실패 시 드라이버와 container 로그, coroutine timeout, pool 상태를 남깁니다. 드라이버 취소 결함 때문에 suite 전체가 멈추지 않도록 모든 테스트에 시간 제한을 둡니다.

## 테스트 {#testing}

모듈 자체 테스트가 트랜잭션 fixture, DDL 정리, assertion, 공통 스키마, SQL 동작을 검증합니다. 사용하는 쪽에서는 rollback, 취소, Flow 일부 수집, dialect별 사례를 추가하세요.

## 학습 경로와 예제 {#workshops}

[코루틴 트랜잭션](bluetape4k-exposed-r2dbc/coroutine-transactions.md)을 이해한 뒤 [취소와 테스트](bluetape4k-exposed-r2dbc/cancellation-and-testing.md)의 검증 흐름을 적용하세요.

## 제약 사항 {#limitations}

fixture가 특정 드라이버의 서버 작업 취소까지 보장하지는 않습니다. 운영 pool 부하를 재현하거나 장시간 복원력 테스트를 대신하지도 않습니다.

<!-- release-readme-diagrams:start -->
## 배포본 다이어그램 {#release-diagrams}

아래 그림은 `1.12.1` 배포본의 README 자산을 해당 배포 커밋에서 직접 불러옵니다. 이후 SNAPSHOT이 아니라 이 매뉴얼 버전의 구조와 실행 흐름을 보여 줍니다. 미리보기를 누르면 같은 배포 커밋의 SVG 원본이 열립니다.

### R2DBC test support 아키텍처

[![R2DBC test support 아키텍처](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-r2dbc-tests-diagram-01.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-r2dbc-tests-diagram-01.svg)

_배포본 README: [`exposed/r2dbc-tests/README.ko.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/r2dbc-tests/README.ko.md)_

### withTables R2DBC test 수명 주기 다이어그램

[![withTables R2DBC test 수명 주기 다이어그램](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-r2dbc-tests-diagram-02.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-r2dbc-tests-diagram-02.svg)

_배포본 README: [`exposed/r2dbc-tests/README.ko.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/r2dbc-tests/README.ko.md)_

<!-- release-readme-diagrams:end -->

## 근거 자료 {#sources}

- [테스트 모듈 빌드](../../../../exposed/r2dbc-tests/build.gradle.kts)
- [`withDb`](../../../../exposed/r2dbc-tests/src/main/kotlin/io/bluetape4k/exposed/r2dbc/tests/withDb.kt)
- [`withTables`](../../../../exposed/r2dbc-tests/src/main/kotlin/io/bluetape4k/exposed/r2dbc/tests/withTables.kt)
