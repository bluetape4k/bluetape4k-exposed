---
manualId: "bluetape4k-exposed-jdbc-tests"
id: "bluetape4k-exposed-jdbc-tests"
title: "Exposed JDBC 테스트 지원"
locale: "ko"
kind: "library"
gradlePath: ":bluetape4k-exposed-jdbc-tests"
sourceDir: "exposed/jdbc-tests"
releaseRef: "1.12.1"
artifact: io.github.bluetape4k.exposed:bluetape4k-exposed-jdbc-tests
---

# Exposed JDBC 테스트 지원

> JDBC 데이터베이스, 트랜잭션, 스키마, 테이블, assertion, Testcontainers fixture를 재사용할 수 있게 묶었습니다.

## 제공하는 기능 {#problem}

데이터베이스 테스트에는 연결 재사용, DB별 직렬화, 트랜잭션 준비, 스키마와 테이블 정리, 실제 dialect 검증이 필요합니다. 모듈마다 이 틀을 다시 만들면 실패를 비교하고 재현하기 어려워집니다.

## 사용하기 좋은 경우 {#when-to-use}

Exposed JDBC 코드와 공통 테이블/매핑 계약을 테스트할 때 사용합니다. 운영 runtime 의존성에는 넣지 않습니다.

## 의존성 좌표 {#coordinates}

중앙 BOM과 함께 `testImplementation("io.github.bluetape4k.exposed:bluetape4k-exposed-jdbc-tests")`를 선언합니다.

## 핵심 개념 {#concepts}

`withDb`는 `TestDB`별 접근을 직렬화하고 연결을 재사용하며 `maxAttempts = 1` 트랜잭션을 엽니다. 임시 설정은 호출 뒤 원래 값으로 돌려놓습니다. `withTables`는 테이블을 만들고 top-level fallback까지 사용해 정리하며 `withSchemas`는 cascade 삭제 전에 commit합니다.

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
| 트랜잭션 fixture | `withDb` |
| 테이블 생명주기 | `withTables` |
| 스키마 생명주기 | `withSchemas` |
| auto-commit 확인 | `withAutoCommit` |
| DB 행렬 | `TestDB`, `TestDBConfig`, container 도우미 |
| assertion/공통 스키마 | assertion과 `shared` 패키지 |

## 권장 패턴 {#patterns}

dialect 차이가 없는 DSL 검증만 H2에서 빠르게 실행합니다. SQL, 타입, 격리 수준, 마이그레이션은 배포할 데이터베이스를 Testcontainers로 띄워 확인하세요. 테스트끼리 변경 가능한 테이블을 공유하지 말고 fixture에 정리를 맡깁니다.

## 연동 {#integrations}

JUnit 5, bluetape4k Testcontainers, MariaDB/MySQL/PostgreSQL container 지원을 테스트 API로 제공합니다. 드라이버는 테스트 컴파일 의존성입니다.

## 설정 {#configuration}

호출마다 `TestDB`와 선택적인 `DatabaseConfig`를 정합니다. 임시 설정은 fixture가 끝날 때 복원됩니다.

## 실패 유형과 해결 방법 {#failures}

같은 DB에서 fixture 없이 병렬 DDL을 실행하면 충돌합니다. 정리가 항상 성공한다고 넘기면 dialect나 연결 문제를 놓칩니다. container 시작 정책에 제한이 없으면 테스트가 불안정해집니다.

## 운영 {#operations}

테스트 전용 모듈입니다. 실패하면 container 로그와 DB 식별 정보를 남기되 fixture 자격 증명을 테스트 프로세스 밖으로 노출하지 마세요.

## 테스트 {#testing}

모듈 자체 테스트가 직렬화, 트랜잭션, 임시 설정 복원, 스키마/테이블 정리, assertion, DDL 동작을 검증합니다.

## 학습 경로와 예제 {#workshops}

[트랜잭션 소유권](bluetape4k-exposed-jdbc/transaction-ownership.md)을 먼저 이해하고 [JDBC 운영과 테스트](bluetape4k-exposed-jdbc/operations-testing.md)에서 fixture를 적용하세요.

## 제약 사항 {#limitations}

이 테스트 틀은 운영 pool 부하를 재현하지 않으며 마이그레이션이나 장애 주입 테스트를 대신하지 않습니다. DB별 semaphore는 충돌하는 fixture 작업을 의도적으로 직렬화합니다.

<!-- release-readme-diagrams:start -->
## 배포본 다이어그램 {#release-diagrams}

아래 그림은 `1.12.1` 배포본의 README 자산을 해당 배포 커밋에서 직접 불러옵니다. 이후 SNAPSHOT이 아니라 이 매뉴얼 버전의 구조와 실행 흐름을 보여 줍니다. 미리보기를 누르면 같은 배포 커밋의 SVG 원본이 열립니다.

### JDBC test dialect coverage

[![JDBC test dialect coverage](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-jdbc-tests-diagram-01.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-jdbc-tests-diagram-01.svg)

_배포본 README: [`exposed/jdbc-tests/README.ko.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/jdbc-tests/README.ko.md)_

### JDBC test 수명 주기

[![JDBC test 수명 주기](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-jdbc-tests-sequence-01.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-jdbc-tests-sequence-01.svg)

_배포본 README: [`exposed/jdbc-tests/README.ko.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/jdbc-tests/README.ko.md)_

<!-- release-readme-diagrams:end -->

## 근거 자료 {#sources}

- [테스트 모듈 빌드](../../../../exposed/jdbc-tests/build.gradle.kts)
- [`withDb`](../../../../exposed/jdbc-tests/src/main/kotlin/io/bluetape4k/exposed/tests/WithDB.kt)
- [`withTables`](../../../../exposed/jdbc-tests/src/main/kotlin/io/bluetape4k/exposed/tests/WithTables.kt)
