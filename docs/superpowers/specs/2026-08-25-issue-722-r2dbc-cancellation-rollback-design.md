# 이슈 #722 R2DBC assertion helper의 cancellation·rollback 계약 설계

## 문서 상태와 기준

- 대상 이슈: [#722](https://github.com/bluetape4k/bluetape4k-exposed/issues/722)
- 분류: **Type A — published test-support API와 coroutine lifecycle 계약 변경**
- 기준 base: `origin/develop` `1242e5eb990a1f362233dba9542aa6e4d7192730`
- 구현 branch: `fix/r2dbc-cancellation-rollback`
- 구현 worktree: `.worktrees/fix/r2dbc-cancellation-rollback`
- workflow run: `20260825T074540Z-66328bff`
- 대상 모듈: `exposed/r2dbc-tests` (`:bluetape4k-exposed-r2dbc-tests`)
- 안정 manual, production R2DBC runtime, 중앙 catalog/BOM, workflow YAML은 범위 밖이다.

## 문제 정의

`exposed/r2dbc-tests`의 published main source가
`io.bluetape4k.assertions`를 호출하지만 build script에
`bluetape4k-assertions` 직접 API dependency가 없다. 또한
`assertFailAndRollback`과 `expectExceptionSuspending`이 모든 `Throwable`을
같은 기대 실패로 처리한다. coroutine cancellation을 assertion 대상이 아닌
일반 실패로 삼키면 부모 coroutine의 structured concurrency가 깨지고,
rollback이 취소된 context에서 실행되지 않을 수 있다. migration drift fixture도
raw JUnit assertion API를 직접 사용한다.

현재 기준선은 `AssertionsTest`와 `R2dbcMigrationDriftTest`가 통과하지만,
cancelled child의 예외가 helper 밖으로 재전파되는지와 cleanup failure가 원인
예외에 보존되는지는 고정하지 않는다. 테스트 source와 `Assertions.kt`의 현재
구현을 직접 읽은 결과이며, 별도 GNO 결과는 이 환경에 노출된 collection/tool이
없어 사용하지 않고 live issue와 source를 기준 정보로 삼는다.

## 목표와 불변 경계

### 목표

1. `bluetape4k-assertions`를 published test-support의 직접 `api` dependency로
   선언한다.
2. `R2dbcMigrationDriftTest`의 equality, identity, boolean, exception 검증을
   `shouldBeEqualTo`, `shouldBeSameInstanceAs`, `shouldBeTrue/False`,
   `assertFailsWith`로 표현한다.
3. assertion helper가 다음 conformance를 보장한다.
   - `CancellationException`은 명시적으로 기대한 타입이 아니면 삼키지 않고
     원래 instance를 재전파한다.
   - `assertFailAndRollback`은 block의 기대된 일반 실패는 소비하되, 자체
     assertion failure와 cleanup failure는 보존하면서 savepoint rollback을
     항상 시도한다.
   - savepoint rollback/release는 cancellation 중에도 `NonCancellable` context에서
     실행한다. R2DBC `commit()`이 auto-commit을 켜는 드라이버 계약 때문에
     JDBC helper의 시작 시 commit 패턴을 복사하지 않는다.
   - rollback failure는 primary failure의 `suppressed`에 추가하고, primary가
     없으면 rollback failure를 그대로 던진다.
   - `expectExceptionSuspending<CancellationException>`처럼 명시적으로
     cancellation을 기대하는 경우에만 해당 예외를 assertion 결과로 반환한다.
4. cancellation, rollback cleanup, assertion failure, expected cancellation을
   실제 회귀 테스트로 고정한다.

### 불변 경계

- Exposed `R2dbcTransaction` commit/rollback 구현과 `withDb`/`withTables`의
  database lifecycle은 변경하지 않는다.
- migration SQL 생성·실행, dialect matrix, Testcontainers launcher는 변경하지
  않는다.
- 새로운 assertion abstraction이나 dependency version 축을 만들지 않는다.
  중앙 catalog의 `bt4k.bluetape4k.assertions` alias만 재사용한다.
- 운영 logging이나 side effect는 추가하지 않는다. 이 파일의 helper는
  deterministic test assertion이며 `println`, `System.out`, `System.err`는
  사용하지 않는다.
- README의 helper 목록과 API 이름은 그대로이므로 이번 의미 변경은 public
  KDoc와 regression test로 추적한다. README에 없는 동작을 새로 주장하지 않는다.

## 선택지와 결정

### A. 명시적 cancellation 분기와 cleanup wrapper — 채택

bluetape4k assertion의 coroutine-native `coInvoking`은 명시적 cancellation
검증 테스트에서 사용한다. 다만 `CancellationException`이 JVM에서
`IllegalStateException`의 하위 타입이므로 `coInvoking.shouldThrow`에
`IllegalStateException`을 넘기면 cancellation이 정상 예외로 오인될 수 있다.
공개 `expectExceptionSuspending`은 이 계층 예외를 먼저 분기해 예상하지 않은
cancellation을 즉시 재전파하고, 나머지 타입 검증을 수행한다. 별도 내부
`preserveFailure` wrapper는 primary failure를 먼저 기록하고
`withContext(NonCancellable)`에서 savepoint rollback/release를 수행한 뒤 cleanup
failure를 `suppressed`로 연결한다.

### B. `catch (Throwable)` 후 타입 검사 — 거부

현재 문제를 반복하고 cancellation을 삼킬 위험이 있다. assertion library가 이미
제공하는 coroutine contract와도 중복된다.

### C. `runCatching` 또는 `finally { rollback() }`만 사용 — 거부

`runCatching`은 cancellation을 일반 실패로 감쌀 수 있고, cancelled context의
suspend rollback은 실행되지 않을 수 있다. cleanup failure가 primary assertion
failure를 덮어쓰는 문제도 해결하지 못한다.

## 실행 계약

### `preserveFailure`

```kotlin
suspend fun preserveFailure(
    block: suspend () -> Unit,
    cleanup: suspend () -> Unit,
) {
    var primary: Throwable? = null
    try {
        block()
    } catch (failure: Throwable) {
        primary = failure
    }
    try {
        withContext(NonCancellable) { cleanup() }
    } catch (cleanupFailure: Throwable) {
        primary?.addSuppressed(cleanupFailure) ?: run { primary = cleanupFailure }
    }
    primary?.let { throw it }
}
```

실제 구현은 `CancellationException`을 별도 domain failure로 변환하지 않는다.
cleanup이 끝난 직후 원래 instance를 재전파하며, cleanup failure만
`suppressed`로 남긴다. `assertFailAndRollback`은 savepoint를 만든 뒤 block의
일반 예외를 기대된 실패로 소비하고, block이 성공하면 `AssertionError`를 primary로
만들어 savepoint rollback을 실행한다. savepoint를 사용해 R2DBC의 auto-commit
전환으로 인해 block DML이 이미 커밋되는 문제를 피한다.

### migration fixture matcher

기존 SQL 문장·예외 identity·suppressed 목록과 테스트 DB 선택은 유지한다.
`assertThrows`를 `assertFailsWith`, `assertSame`을
`shouldBeSameInstanceAs`로 바꾸며, JUnit lifecycle/parameterized annotation
import는 유지한다.

## 실패 모드와 대응

| 실패 | 검출 방법 | 대응 |
| --- | --- | --- |
| cancellation이 AssertionError로 변환되거나 삼켜짐 | 실제 child `cancel` + `yield` 후 예외 identity 검사 | `coInvoking`/wrapper의 cancellation 경계 수정 |
| rollback이 cancellation 중 실행되지 않음 | cleanup marker와 `NonCancellable` suspend cleanup 테스트 | cleanup context를 보강하고 원래 예외 재전파 |
| rollback failure가 primary를 덮음 | synthetic cleanup failure의 `suppressed` identity 검사 | primary 우선 연결 규칙 수정 |
| block 성공 assertion이 rollback failure에 가려짐 | assertion failure + cleanup failure 동시 테스트 | assertion을 primary로 먼저 기록 |
| expected cancellation이 일반 cancellation처럼 재전파됨 | `expectExceptionSuspending<CancellationException>` 테스트 | 명시적 기대 타입만 허용 |
| published consumer가 assertion API를 해석하지 못함 | `apiElements`/POM/module metadata와 consumer compile | direct `api` dependency 복구 |
| migration fixture에 raw assertion이 재도입됨 | module-local import scan과 `check` | fixture를 matcher로 교체 |

## 수용 기준과 DoD

- direct `api(bt4k.bluetape4k.assertions)`와 generated API metadata가 존재한다.
- `Assertions.kt`가 cancellation을 삼키지 않고 rollback/primary/suppressed
  관계를 보존한다.
- `AssertionsTest`가 cancellation, rollback cleanup, assertion failure,
  expected cancellation을 각각 실패 가능한 형태로 검증한다.
- `R2dbcMigrationDriftTest`에 raw JUnit/kotlin.test assertion import와 호출이
  없다.
- `:bluetape4k-exposed-r2dbc-tests:compileTestKotlin`, H2 migration targeted
  test, affected module test, detekt와 `git diff --check`가 fresh PASS다.
- Kotlin coroutine/testing checklist와 7-Tier review에서 P0/P1이 0건이다.
- commit은 Lore trailer를 포함하고, exact head를 push한 뒤 Issue #722 metadata와
  같은 label/milestone/assignee의 한국어 PR을 생성한다. merge는 별도 approval이다.

## SPW writer gate

- **SPW-01**: 대상 독자는 R2DBC test-support 소비자와 유지보수자이며, source·issue·base
  SHA·API identifiers를 위에 고정했다. GNO는 미노출로 기록했다.
- **SPW-02**: 문제, 경계, 대안, 실행 계약, 실패 모드, 호환성, 수용 기준과 rollback
  경계를 포함한다.
- **SPW-03**: Korean technical register로 작성하고 `CancellationException`,
  `NonCancellable`, API names, commands와 URLs은 보존했다.
- **SPW-04**: 현재 `Assertions.kt`, fixture, build script, live #722와 대조했다.
- **SPW-05**: 최종 Markdown read-back과 `git diff --check`를 구현 후 다시 수행한다.
