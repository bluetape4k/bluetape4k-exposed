---
title: JDBC 운영과 테스트
description: 1.11의 트랜잭션, 스키마, dialect, rollback, 정리 도우미로 JDBC 저장소를 검증하고 연결 경계를 안전하게 운영합니다.
manualId: bluetape4k-exposed-jdbc
chapterId: operations-testing
---

# JDBC 운영과 테스트

저장소 테스트는 운영 코드와 같은 경계를 검증할 때 가치가 큽니다. 실제 Exposed 트랜잭션, 선택한 dialect, 스키마 생성과 정리, 완성된 결과까지 확인해야 합니다. `bluetape4k-exposed-jdbc-tests`는 모듈마다 컨테이너와 트랜잭션 생명주기를 다시 만들지 않도록 이 기반을 제공합니다.

## 테스트 fixture 계층

| 도우미 | 소유하는 범위 |
|---|---|
| `withDb` | 연결 준비, 트랜잭션 하나, DB별 세마포어, 임시 설정 복원 |
| `withTables` | 테스트 전 drop/create와 종료 후 drop 재시도 |
| `withSchemas` | 스키마 생성과 정리 |
| `withAutoCommit` | 연결의 auto-commit 임시 변경과 복원 |
| `assertFailAndRollback` | fixture 상태 commit, 실패 연산 검증, rollback |
| `withDbSuspending` / `withTablesSuspending` | 블로킹 JDBC 테스트를 위한 코루틴 래퍼 |

`withDb`는 `maxAttempts = 1`로 실행합니다. 실패한 assertion이나 SQL이 트랜잭션 재시도에 가려지지 않습니다. 같은 `TestDB`를 쓰는 테스트는 공정한 세마포어로 직렬화하므로 한 테스트의 스키마 정리가 다른 테스트를 깨뜨리지 않습니다.

```kotlin
@ParameterizedTest
@MethodSource(ENABLE_DIALECTS_METHOD)
fun `save and find an actor`(testDB: TestDB) {
    withTables(testDB, ActorTable) {
        val saved = repository.save(newActor())
        repository.findById(saved.id) shouldBeEqualTo saved
    }
}
```

## 실패 경로 테스트

제약 조건 위반을 검증할 때 `assertFailAndRollback`을 사용하세요. 먼저 준비한 상태를 commit하고, 실패할 블록을 실행해 예외를 확인한 뒤 rollback합니다. 일반 예외를 숨기는 도우미로 사용하면 안 됩니다.

다음 항목도 함께 검증하는 편이 좋습니다.

- 행이 없을 때와 중복 키 동작
- nullable 컬럼과 기본값
- 빈 입력과 여러 행에서 `bindSave` 동작
- 감사 update와 일반 update 뒤의 감사 필드 차이
- 논리 삭제 행의 노출과 복원
- 페이지 입력 검증과 허용한 일관성 수준
- 애플리케이션이 부분 rollback에 의존한다면 savepoint 동작

## 코루틴 래퍼도 JDBC는 블로킹이다

1.11의 JDBC 테스트 도우미는 Exposed의 experimental `newSuspendedTransaction`과 선택형 dispatcher를 사용합니다. suspend 테스트에서 블로킹 JDBC를 호출하기 위한 래퍼이지 R2DBC 트랜잭션이 아닙니다. 블로킹 작업은 알맞은 dispatcher나 가상 스레드에서 실행하고 event loop 스레드에서는 실행하지 마세요.

## 데이터베이스 테스트 범위

빠르고 결정적인 피드백에는 H2를 사용하고, 그다음 실제 운영 dialect를 실행하세요. SQL 문법, 생성 키, upsert, 격리 수준, DDL, 제약 조건 오류가 서로 다를 수 있습니다. PostgreSQL, MySQL, MariaDB 통합에는 Testcontainers가 유용합니다. 다만 여러 테스트가 같은 변경 가능한 스키마를 공유하면 거짓 실패가 생길 수 있어서 테스트 도우미가 `TestDB`별로 실행을 직렬화합니다.

## 운영 점검 항목

- 측정한 동시 요청 수와 쿼리 지연 시간을 바탕으로 연결 풀을 정하세요. 가상 스레드는 호출자를 늘릴 수 있지만 DB 연결 수를 늘려 주지는 않습니다.
- statement와 transaction timeout은 트랜잭션을 소유한 계층에서 설정하세요.
- 자격 증명이나 민감한 파라미터를 남기지 않으면서 느린 쿼리의 문맥을 기록하세요.
- 풀 대기 시간과 쿼리 실행 시간을 따로 관찰하세요.
- 요청 처리 중 저장소 초기화에서 migration을 실행하지 마세요.
- 재시도를 Exposed, Spring, 서비스 중 어느 계층이 맡을지 하나로 정하세요. 재시도 계층이 겹치면 실행 횟수가 불어납니다.

## 흔한 실패 진단

| 증상 | 먼저 확인할 내용 |
|---|---|
| `No transaction in context` | 서비스가 `transaction {}`을 열었는지, 잘못된 스레드 경계를 넘었는지 |
| 직렬화 중 DAO 프로퍼티 오류 | 트랜잭션이 닫힌 뒤 레코드로 변환했는지 |
| H2에서는 통과하고 운영 DB에서는 실패 | 운영 dialect의 SQL, upsert, 생성 키 동작 |
| 연결 풀 고갈 | 긴 트랜잭션, 트랜잭션 안의 외부 호출, 가상 스레드 fan-out |
| 테스트 뒤 테이블 정리 실패 | 앞선 트랜잭션 미종료 또는 다른 테스트와 스키마 공유 여부 |
| 페이지 전체 개수와 데이터 불일치 | 동시 쓰기 중 count와 content가 별도 쿼리라는 점 |

[`exposed-workshop`](https://github.com/bluetape4k/exposed-workshop)에서는 이 기반을 트랜잭션, Spring 연동, 저장소 경계, 멀티테넌시, 운영 예제로 확장합니다.

## 근거 자료

- [JDBC `withDb`](../../../../../exposed/jdbc-tests/src/main/kotlin/io/bluetape4k/exposed/tests/WithDB.kt)
- [JDBC `withTables`](../../../../../exposed/jdbc-tests/src/main/kotlin/io/bluetape4k/exposed/tests/WithTables.kt)
- [suspend JDBC 테스트 도우미](../../../../../exposed/jdbc-tests/src/main/kotlin/io/bluetape4k/exposed/tests/WithDBSuspending.kt)
- [JDBC assertion](../../../../../exposed/jdbc-tests/src/main/kotlin/io/bluetape4k/exposed/tests/Assertions.kt)
- [저장소 통합 테스트](../../../../../exposed/jdbc/src/test/kotlin/io/bluetape4k/exposed/jdbc/repository/MovieJdbcRepositoryTest.kt)
