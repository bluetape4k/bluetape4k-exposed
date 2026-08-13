# R2DBC `withTables` 취소와 정리 예외의 우선순위

## 배경

Issue #625에서 `withTables`가 테스트 statement의 코루틴 취소와 테이블 정리 실패를 같은 일반 예외 경로로 처리할 수 있음을 확인했다. R2DBC DDL은 suspend 호출이므로 `finally`에서 정리를 수행하는 동안 부모 coroutine이 이미 취소된 상태일 수 있다.

## 원인

기존 구현은 실행 전 drop, `finally`의 drop/commit, 복구 transaction을 `runCatching` 또는 넓은 예외 경계로 감싸면서 `CancellationException`을 구별하지 않았다. 그 결과 구조적 동시성의 취소 신호가 정리 성공처럼 소실될 수 있었고, statement 실패가 정리 실패에 의해 가려질 수 있었다.

## 놓친 점과 결과

초기 회귀 테스트는 직접 `CancellationException`을 던지는 경로만 확인해 실제 job 취소와 정리 실패의 상호작용을 충분히 고정하지 못했다. `async` job을 실제로 취소하고 cleanup 실패를 주입하는 테스트로 보완한 뒤, 호출자는 취소를 받고 일반 실패의 원인 예외에는 두 정리 실패가 남는다는 결과를 확인했다.

## 결정

- `CancellationException`은 cleanup/recovery의 일반 실패보다 우선하며 호출자까지 재전파한다.
- 취소된 coroutine에서도 DDL cleanup을 시도해야 하므로 suspend 정리 구간만 `withContext(NonCancellable)`로 감싼다.
- 일반 statement 실패가 이미 있으면 cleanup/recovery 실패를 원래 statement 예외의 `suppressed` 목록에 보존한다.
- statement 취소의 `suppressed` 목록에는 cleanup/recovery 예외를 추가하지 않는다.
- `withTables`의 공개 시그니처와 `dropTables`/`configure` 정상 경로는 변경하지 않는다.

## 검증

- 근거: [Issue #625](https://github.com/bluetape4k/bluetape4k-exposed/issues/625), `exposed/r2dbc-tests/src/main/kotlin/io/bluetape4k/exposed/r2dbc/tests/withTables.kt`, `exposed/r2dbc-tests/src/test/kotlin/io/bluetape4k/exposed/r2dbc/tests/TestSupportsTest.kt`.
- 실제 `async` job 취소 중 cleanup 실패가 발생해도 `CancellationException`이 호출자에게 도달하는 회귀 테스트를 추가했다.
- 일반 statement 실패와 cleanup/recovery 실패를 함께 주입해 두 정리 실패가 statement 예외에 `suppressed`로 남는지 고정했다.
- `EXPOSED_TEST_DB=H2 ./gradlew :bluetape4k-exposed-r2dbc-tests:test --no-daemon --console=plain`: 63 tests executed, 5 skipped, 0 failures/errors.
- `./gradlew detekt --no-daemon --console=plain` 및 `git diff --check` 통과.

## 후속 수정 지침

R2DBC 테스트 fixture의 suspend `finally` cleanup을 변경할 때는 먼저 `CancellationException` 전파와 `NonCancellable` 범위를 검증하고, 일반 예외의 원인·suppressed chain을 보존해야 한다. 새로운 `runCatching`, public API 변경, 또는 cleanup 실패의 무시 정책을 추가하지 말고, 실제 coroutine cancellation을 사용한 회귀 테스트를 함께 갱신한다.
