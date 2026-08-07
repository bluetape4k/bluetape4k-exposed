---
manualId: transaction-boundaries
title: 트랜잭션 경계
locale: ko
releaseRef: 1.12.1
---

# 트랜잭션 경계

저장소는 statement를 실행할 수 있지만 어떤 statement가 하나의 업무 작업인지는 알지 못한다. 업무 규칙을 소유한 서비스가 transaction 경계를 정하고 connection, coroutine, framework의 소유권도 드러내야 한다.

> [JDBC와 R2DBC 트랜잭션 경계 시각화 자료](https://bluetape4k.github.io/ko/visual-companions/bluetape4k-exposed/jdbc-r2dbc-transaction-boundaries/)에서 실행 경로를 직접 비교할 수 있습니다.

## 업무 작업 하나에 명확한 소유자 한 명

```kotlin
suspend fun transfer(command: TransferCommand) =
    suspendTransaction(db = database) {
        accountRepository.debit(command.from, command.amount)
        accountRepository.credit(command.to, command.amount)
        transferRepository.record(command)
    }
```

repository 메서드마다 transaction을 열면 출금, 입금, 이체 기록이 따로 commit될 수 있다. 세 작업이 함께 성공해야 한다는 규칙은 서비스가 소유하므로 transaction도 서비스에서 연다.

## 계층별 책임

| 계층 | 책임 |
| --- | --- |
| 애플리케이션과 framework | 요청·job 취소, timeout, shutdown 정책 |
| 서비스와 use case | 업무 transaction, 재시도, idempotency 규칙 |
| Exposed transaction | repository statement가 사용할 DB와 격리 문맥 |
| Repository | 도메인 값을 Exposed table과 statement로 매핑 |
| Driver와 pool | 각 구현 계약에 따라 connection 공급·검증·회수 |
| Database | SQL 격리 수준, constraint, lock, commit 결과 |

하위 계층이 보장하지 않는 동작을 문서에서 덧붙이면 안 된다. 예를 들어 coroutine 취소만 확인하고 DB rollback 완료나 pool connection 회수까지 끝났다고 단정할 수 없다.

## JDBC 경계

애플리케이션이 명령형 경계를 소유한다면 Exposed `transaction`을 사용한다. Spring에서는 설정한 JDBC transaction manager와 repository 작업을 맞추고, 서로 관계없는 Exposed transaction을 중첩하지 않는다. JDBC 호출은 blocking이므로 blocking 작업에 맞는 thread에서 실행한다. 단순히 `suspend` 함수로 감싼다고 JDBC가 논블로킹으로 바뀌지는 않는다.

## R2DBC 경계

함께 처리할 statement와 Flow terminal operation을 `suspendTransaction` 안에 둔다. child 작업은 caller의 structured scope를 벗어나지 않는다. Framework repository가 내부 transaction을 연다면 propagation과 connection 사용 방식을 이해하고 테스트하기 전에는 transaction을 한 겹 더 추가하지 않는다.

## DB 밖의 side effect

HTTP 호출, message 발행, 파일 쓰기는 DB transaction이 rollback해 주지 않는다. 느린 외부 I/O를 기다리면서 connection을 오래 잡지 않는다. DB commit 뒤 message 발행이 반드시 이어져야 한다면 outbox 같은 명시적인 전달 protocol을 사용한다. Transaction block 재시도는 안에 있는 모든 side effect가 재시도 가능하거나 idempotent일 때만 안전하다.

## Timeout과 취소

전체 시간 예산은 애플리케이션 경계에서 정하고 pool acquisition, statement, framework timeout을 그 안에서 진단할 수 있게 구성한다. Coroutine 코드의 broad catch에서는 `CancellationException`을 재전파한다. 취소와 DB 오류를 같은 failure metric으로 합치지 않는다.

`취소 → rollback → close`처럼 모든 조합에 적용되는 순서를 단정하지 않는다. Exposed, driver, pool, DB에 따라 관찰 순서가 달라진다. 최종 업무 상태, connection 재사용 가능 여부, shutdown deadline을 선택한 stack으로 직접 검증한다.

## 매퍼보다 경계를 테스트한다

- DB 없이 검증할 수 있는 매핑과 업무 규칙은 unit test로 빠르게 확인한다.
- 기본 저장소 동작은 H2를 사용한다.
- 격리 수준, lock, SQL, 생성 key, 취소는 운영 DB와 Testcontainers로 검증한다.
- 같은 DB와 pool을 공유하는 테스트는 순차 실행한다.
- statement 사이에서 실패를 강제로 만들고 전체 업무 상태를 확인한다.
- 제한된 timeout으로 pool 고갈과 애플리케이션 종료를 테스트한다.

## 근거 자료

- [`JdbcRepository.kt`](../../../../exposed/jdbc/src/main/kotlin/io/bluetape4k/exposed/jdbc/repository/JdbcRepository.kt)
- [`R2dbcRepository.kt`](../../../../exposed/r2dbc/src/main/kotlin/io/bluetape4k/exposed/r2dbc/repository/R2dbcRepository.kt)
- [`withDb.kt`](../../../../exposed/r2dbc-tests/src/main/kotlin/io/bluetape4k/exposed/r2dbc/tests/withDb.kt)
- [JDBC와 R2DBC 선택](./jdbc-vs-r2dbc.md)
