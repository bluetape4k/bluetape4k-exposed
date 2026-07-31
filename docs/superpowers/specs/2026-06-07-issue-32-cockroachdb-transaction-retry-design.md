# Issue #32 CockroachDB Transaction Retry 설계

날짜: 2026-06-07
이슈: https://github.com/bluetape4k/bluetape4k-exposed/issues/32
상위 epic: https://github.com/bluetape4k/bluetape4k-exposed/issues/24
이전 작업 단위:
- https://github.com/bluetape4k/bluetape4k-exposed/issues/30
- https://github.com/bluetape4k/bluetape4k-exposed/issues/31

## 목표

모듈을 사용자 정의 CockroachDB dialect로 확장하지 않으면서 Exposed JDBC를
위한 제한된 CockroachDB transaction retry 지원을 추가한다.

헬퍼는 CockroachDB transaction retry 오류만 재시도해야 하며, retry 불가능한
SQL 오류, cancellation, interruption은 retry 경계 밖에 두어야 한다.

## 현재 근거

- #30에서 `exposed-cockroachdb` 모듈, `CockroachDatabase`, CockroachDB
  Testcontainers smoke coverage를 추가했다.
- #31에서 DDL 호환성 경계를 문서화하고 serializable transaction retry
  헬퍼는 #32가 담당하도록 명시적으로 남겨 두었다.
- CockroachDB stable docs는 transaction retry 오류를 SQLSTATE `40001`과
  `restart transaction`으로 시작하는 메시지로 정의한다.
- CockroachDB transaction은 기본적으로 `SERIALIZABLE`이며, 여러 statement로
  구성된 serializable transaction에서 client에 노출되는 retry 오류가 발생하면
  client 측 retry 처리가 필요하다.
- JetBrains Exposed 1.3.0에는 transaction 설정 `maxAttempts`,
  `minRetryDelay`, `maxRetryDelay`가 있지만 JDBC retry loop는
  `SQLException`을 광범위하게 포착한다. 이 loop를 직접 사용하면 retry
  불가능한 SQL 오류까지 재시도하므로 #32 계약에 어긋난다.

## 공개 API 계약

`io.bluetape4k.exposed.cockroachdb` 아래에 공개 API를 추가한다.

- `CockroachTransactionRetryOptions`
  - serializable data class
  - 제한된 시도 횟수
  - millisecond 단위의 최소/최대 retry delay
  - Kotlin `Duration` 인자를 위한 companion `invoke` overload
  - 선택적인 초 단위 query timeout
  - 기본값이 `Connection.TRANSACTION_SERIALIZABLE`인 transaction isolation
- `Throwable.isCockroachRetryableTransactionError(): Boolean`
  - cause chain을 순회한다.
  - SQLSTATE가 `40001`이고 메시지가 `restart transaction`으로 시작할 때만
    SQL 예외를 분류한다.
- `withCockroachTransaction(...)`
  - Exposed JDBC `transaction`을 감싼다.
  - 일반 헬퍼 사용 시 transaction block을 감싸는 공개 API 호출을 추가로
    할당하지 않도록 inline으로 정의한다.
  - 헬퍼가 retry 분류를 소유하도록 Exposed transaction 내부의
    `maxAttempts = 1`로 설정한다.
  - CockroachDB transaction retry 오류로 분류된 경우에만 재시도한다.
  - 발생한 SQL 예외를 보존하며, 시도를 모두 소진하면 이전 retry 실패를
    suppressed exception으로 첨부한다.

공개 KDoc는 영어로 작성하고 제한된 헬퍼 전용 계약을 포함해야 한다.

## 목표가 아닌 것

- 사용자 정의 CockroachDB Exposed dialect
- R2DBC retry 지원
- savepoint 기반 고급 retry protocol
- 모든 `SQLException` 재시도
- `CockroachDatabase.connect` 동작이나 기본 transaction retry 설정의 전역 변경

## 테스트 계약

- 원시 Testcontainers container를 직접 만들지 말고
  `CockroachServer.Launcher.cockroach`를 사용한다.
- bluetape4k assertion 헬퍼와 JUnit 5를 사용한다.
- 다음 항목을 가짜 SQLException 회귀 테스트로 다룬다.
  - 정확한 retry 가능 CockroachDB signature
  - cause chain에 포함된 retry 가능 SQL 예외
  - 잘못된 SQLSTATE
  - 잘못된 메시지 prefix
  - 소진 전에 retry 성공
  - suppressed 시도 근거를 포함한 retry 소진
  - retry 불가능한 SQL 예외를 재시도하지 않음
  - cancellation과 interruption을 재시도하지 않음
- 다음 항목을 CockroachDB Testcontainers smoke coverage에 추가한다.
  - 일반 헬퍼 commit
  - 실패 시 rollback
  - wrapping된 Exposed transaction이 내부 Exposed 시도를 한 번만 사용함

## 문서 계약

`exposed/exposed-cockroachdb/README.md`와
`exposed/exposed-cockroachdb/README.ko.md`를 모두 갱신한다.

- 범위/호환성 경계에 transaction retry 지원을 추가한다.
- `withCockroachTransaction(db) { ... }` 사용법을 보여 준다.
- Exposed의 일반 `maxAttempts`가 존재하지만 `SQLException`을 광범위하게
  재시도하는 반면, 이 헬퍼는 CockroachDB transaction retry 오류로 retry
  분류를 제한한다고 설명한다.
- 범위 밖 목록을 정확하게 유지한다.

`CHANGELOG.md`의 `[Unreleased]` 아래를 갱신한다.

## 인수 기준

- 현재 근거를 반영해 #32 issue 본문을 갱신한다.
- spec review를 `P0 = 0`, `P1 = 0`으로 마친다.
- plan review를 `P0 = 0`, `P1 = 0`으로 마친다.
- `./gradlew :bluetape4k-exposed-cockroachdb:compileKotlin :bluetape4k-exposed-cockroachdb:compileTestKotlin --no-configuration-cache --no-daemon`
  명령이 통과한다.
- `./gradlew :bluetape4k-exposed-cockroachdb:test --rerun-tasks --no-configuration-cache --no-daemon`
  명령이 통과한다.
- `./gradlew :bluetape4k-exposed-cockroachdb:koverXmlReport --no-configuration-cache --no-daemon`
  명령이 통과한다.
- `git diff --check`가 통과한다.
- `docs/lessons/` 아래에 간결한 lesson을 추가한다.
- PR 본문의 마지막 `##` section은 `## DoD Status`이다.
