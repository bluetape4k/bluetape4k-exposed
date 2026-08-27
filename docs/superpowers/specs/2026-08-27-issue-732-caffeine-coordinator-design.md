# Issue #732 Caffeine write-behind lifecycle coordinator 설계

## 상태와 범위

- 대상 이슈: [#732](https://github.com/bluetape4k/bluetape4k-exposed/issues/732)
- 기준 저장소: `bluetape4k/bluetape4k-exposed`
- 기준 ref: `origin/develop` (`c5e9d499d9c1baeb6f92a531345d184c16febc27`)
- 작업 branch: `refactor/issue-732-caffeine-coordinator`
- 작업 worktree: `.worktrees/refactor/issue-732-caffeine-coordinator`
- 승인 상태: 아키텍처와 공개 계약을 사용자에게 승인받음
- 이 명세의 범위: `exposed/cache` 내부 write-behind lifecycle coordinator, JDBC/R2DBC/suspended JDBC adapter, conformance suite와 문서
- 제외 범위: public cache interface/ABI 확장, cache backend 교체, DB transaction 의미 변경, PR·merge·release

## 문제

JDBC Caffeine, R2DBC Caffeine, suspended JDBC Caffeine repository가 각각
write-behind queue admission, worker state, queue depth, close owner/follower
arbitration, completion publication과 실패 상태를 구현한다. 세 구현은 같은
lifecycle 규칙을 복제하면서도 queue send 방식과 transaction/flush 경계가
다르다. 한 adapter의 수정이 다른 adapter의 cancellation, close timeout,
dirty cache publication을 놓칠 위험이 있다.

목표는 backend-neutral coordinator와 먼저 검증하는 conformance suite를
도입해 lifecycle 규칙을 단일화하는 것이다. coordinator는 queue/lifecycle
상태와 arbitration만 소유하고, 실제 `Channel`, `CoroutineScope`, cache key,
database transaction과 flush는 각 adapter가 계속 소유한다.

## 현재 근거

| 근거 | 현재 사실 |
|---|---|
| `exposed/jdbc-caffeine/.../AbstractJdbcCaffeineRepository.kt` | `Channel<Pair<ID,E>>`, admission counter, worker state, completion/close condition, cache publication guard와 JDBC `transaction` flush를 함께 구현한다. |
| `exposed/r2dbc-caffeine/.../AbstractR2dbcCaffeineRepository.kt` | `WriteBehindEntry`의 suspend `send`, admission rollback, R2DBC `suspendTransaction`, 동일한 close arbitration을 구현한다. |
| `exposed/jdbc-caffeine/.../AbstractSuspendedJdbcCaffeineRepository.kt` | suspend JDBC queue/worker와 `suspendedTransactionAsync` flush를 구현하지만 public consistency report는 제공하지 않는다. |
| `exposed/cache/.../CacheHealthReport.kt` | `CacheWorkerState`와 `queueDepth`/`lastFlushError` report가 이미 backend-neutral public contract로 존재한다. |
| `exposed/cache/src/testFixtures` | JDBC/R2DBC/suspended write-behind 시나리오 fixture가 다른 모듈의 통합 테스트를 지원한다. |
| existing tests | JDBC Caffeine 405개(22 skipped), R2DBC Caffeine 120개(1 skipped)가 H2 baseline에서 PASS했다. |

현재 동작에서 보존해야 하는 세부 의미는 다음과 같다.

- write-behind가 아닌 mode는 `NOT_APPLICABLE`이며 coordinator를 시작하지 않는다.
- queue depth는 접수됐지만 flush 완료가 관찰되지 않은 entry 수다.
- queue full 또는 cancelled send는 cache를 먼저 publish하지 않고 admission을
  되돌린다.
- ordinary flush exception은 `lastFlushError`에 기록하고 batch/depth를
  유지해 기존 retry 경로를 보존한다. `CancellationException`은 삼킨다.
- close는 최초 호출자가 공통 결과를 결정하고, 동시/반복 호출자는 같은
  결과를 관찰한다. timeout/interruption은 close failure와 `FAILED` 상태를
  남긴다.
- successful drain 뒤에는 `STOPPED`, worker cancellation/uncaught failure
  또는 남은 depth가 있는 terminal completion 뒤에는 `FAILED`를 보고한다.

## 선택한 구조

### 내부 coordinator

새 production 파일은 다음 경로에 둔다.

`exposed/cache/src/main/kotlin/io/bluetape4k/exposed/cache/internal/WriteBehindCoordinator.kt`

coordinator는 `internal`이며 public interface나 published signature에
노출하지 않는다. 내부 계약은 다음 책임을 갖는다.

```kotlin
internal class WriteBehindCoordinator(
    mode: CacheWriteMode,
    queueCapacity: Int,
    closeWaitDuration: Duration,
    clock: () -> Long = System::nanoTime,
) {
    fun reserveAdmission(): AdmissionToken
    fun settleEnqueue(token: AdmissionToken, accepted: Boolean)
    fun onFlushSucceeded(count: Int)
    fun onFlushFailed(error: Throwable)
    fun onWorkerCompleted(cause: Throwable?)
    fun beginClose(): CloseLease
    fun publishCloseCompletion(completion: CloseCompletion)
    fun snapshot(): CacheHealthReport
}
```

실제 이름과 private helper는 구현 중 Kotlin visibility에 맞춰 조정할 수
있지만 다음 invariant는 변경하지 않는다.

- `reserveAdmission`은 OPEN 상태에서만 성공하며 close 진입 이후 새 admission을
  거부한다.
- enqueue가 성공한 token만 cache publication을 허용한다. 실패/취소 token은
  depth와 in-flight admission을 정확히 한 번만 감소시킨다.
- `onFlushSucceeded(count)`는 음수 depth를 허용하지 않고 정확히 `count`를
  감소시킨다. 실패 callback은 depth를 감소시키지 않는다.
- close owner는 OPEN→DRAINING을 한 번만 수행하고 immutable completion을
  발행한다. follower는 상태를 재결정하지 않는다.
- coordinator는 `ID`, entity, cache key, SQL, URL, exception message를
  저장하거나 로그/metric tag로 만들지 않는다. raw `Throwable`은 기존
  `CacheHealthReport.lastFlushError`와 adapter logging 계약을 위해서만
  보관하며 외부 response에 노출하지 않는다.

### Adapter 책임

각 Caffeine adapter는 중복된 lifecycle field를 coordinator 호출로 바꾸되
다음 책임은 유지한다.

- `CoroutineScope`, `Channel`, batch 수집과 worker launch/cancellation
- JDBC `transaction`, R2DBC `suspendTransaction`, suspended JDBC
  `suspendedTransactionAsync`
- cache put/invalidate와 key별 publication guard
- `afterPersisted` hook, backend log와 backend별 transaction retry
- `close()`의 cache invalidate와 scope 종료

JDBC의 non-suspending `trySend`와 R2DBC/suspended JDBC의 cancellable
`send`는 adapter가 선택한다. coordinator는 send를 blocking하거나
`runBlocking`을 호출하지 않는다. suspended JDBC의 기존 public interface에
`validateConsistency`를 추가하지 않는다.

## 상태 전이와 데이터 흐름

```text
put(write-behind)
  -> coordinator.reserveAdmission()
  -> adapter queue trySend/send
       실패·취소 -> coordinator settle(false) -> cache 미게시
       성공     -> coordinator settle(true)  -> cache publish
  -> worker batch flush
       성공     -> depth 감소 + persisted hook
       일반 오류 -> lastFlushError 기록, batch 유지, 기존 retry
       취소/미처리 예외 -> worker FAILED, queue 종료

close()
  -> coordinator.beginClose()
       owner    -> DRAINING, adapter queue close, bounded wait
       follower -> owner completion 대기
  -> drain 완료 + depth=0 -> STOPPED/COMPLETED
  -> timeout/interruption/terminal residual -> FAILED + immutable outcome
  -> adapter cache invalidate + scope cancel
```

`CacheHealthReport`의 mode, queueDepth, workerState와 lastFlushError 값은
기존 observer가 읽던 시점 의미를 유지한다. close 이후 반복해서 보고서를
읽어도 상태가 되돌아가지 않는다.

## 실패 모드와 대응

1. **admission accounting underflow 또는 double settlement**  
   token을 단 한 번만 settle할 수 있게 만들고, fake adapter에서 성공·실패·
   cancellation을 섞은 동시 admission을 반복한다. depth가 음수가 되거나
   close가 조기에 완료되면 테스트를 실패시킨다.
2. **close와 in-flight send의 순서 역전**  
   close owner가 DRAINING을 publish하기 전에는 admission을 허용하고,
   이후에는 즉시 거부한다. blocked send가 cancellation되면 cache가 publish되지
   않고 owner가 해당 admission을 기다린 뒤 같은 close 결과를 publish하는지
   latch/barrier 테스트로 고정한다.
3. **flush failure 뒤 데이터/health 상태 유실**  
   일반 예외는 `lastFlushError`만 갱신하고 batch/depth를 보존한다. 한 번
   실패한 fake flush가 재시도에 성공하면 depth가 정확히 0이 되고 error가
   `null`로 회복되는지 확인한다. permanent failure 또는 worker completion에
   남은 depth가 있으면 `FAILED`로 남긴다.
4. **cancellation을 일반 오류로 삼켜 worker가 계속 실행됨**  
   `CancellationException`은 adapter flush와 cancellable send에서 재전파하고,
   coordinator는 worker completion을 terminal failure로 기록한다. close의
   NonCancellable final flush 같은 기존 안전 경계는 유지한다.
5. **close timeout/interruption에서 follower가 서로 다른 결과를 봄**  
   owner/follower 동시 close와 interrupt를 실행해 하나의 immutable
   `TIMEOUT` 또는 `INTERRUPTED` 결과와 `FAILED` report만 관찰되는지 검증한다.
6. **민감한 key/exception이 공통 계층에 유입됨**  
   coordinator API에 key/entity/message 인자를 두지 않고, log/metric
   assertions에서 cache key·SQL·URL·credential이 나오지 않는지 확인한다.

## Conformance suite

DB 없는 fake adapter contract는 다음 경로에 둔다.

`exposed/cache/src/test/kotlin/io/bluetape4k/exposed/cache/internal/WriteBehindCoordinatorTest.kt`

필수 시나리오는 정상 admission/flush, double close, close 전후 admission,
queue full 즉시 거부, blocked send cancellation, in-flight flush cancellation,
timeout, interruption, retryable flush failure, permanent failure, worker
completion, state/depth invariant와 sanitized error observation이다. 시간은
주입 가능한 monotonic clock 또는 deterministic barrier로 제어해 sleep 기반
flaky test를 피한다.

공통 시나리오 helper가 다른 모듈에 필요하면
`exposed/cache/src/testFixtures/kotlin/io/bluetape4k/exposed/cache/scenarios`
아래에 둔다. JDBC, suspended JDBC, R2DBC adapter는 각각 현재 Testcontainers
fixture와 H2 테스트를 사용해 다음을 재검증한다.

- 실제 DB transaction이 adapter 경계 안에서만 호출되는지
- queue admission/send cancellation이 dirty cache를 publish하지 않는지
- close가 남은 batch를 flush하고 cache invalidate/scope cancel을 수행하는지
- `validateConsistency`를 제공하는 JDBC/R2DBC report가 fake contract와
  동일한 state/depth/error 의미를 보이는지
- suspended JDBC의 public ABI와 key별 load mutex 동작이 유지되는지

기존 JDBC 405개(22 skipped), R2DBC 120개(1 skipped) baseline 테스트는
삭제하지 않는다. 새 targeted suite 후 H2, PostgreSQL, MySQL 순서로
Testcontainers 검증을 순차 실행한다.

## 성능·안정성 기준

- write-behind hot path에 DB transaction, blocking wait, `runBlocking`을
  coordinator가 추가하지 않는다.
- admission/close 상태는 기존 lock/atomic 수준의 bounded contention으로
  처리하고, worker 수와 queue capacity를 늘리지 않는다.
- token과 callback allocation을 batch가 아닌 entry당 필요한 최소 수준으로
  유지하며, stress test에서 queue depth가 정확히 0으로 수렴해야 한다.
- close wait budget은 기존 설정값(기본 30초)을 그대로 사용하며, timeout 뒤
  scope cancellation과 cache invalidate의 순서는 기존 adapter 계약을 지킨다.

## 문서와 호환성

public cache interface와 published artifact 이름은 바꾸지 않는다. KDoc은
coordinator가 내부 상태를 단일화하지만 DB flush/transaction은 adapter가
소유한다는 경계를 설명한다. JDBC/R2DBC/suspended JDBC cache manual의
write-behind lifecycle 절을 EN/KO로 맞추고, 현재 README의 close·cancellation
예제를 새 conformance contract와 일치시킨다. 새로운 public dependency나
개별 BOM 좌표는 추가하지 않는다.

## 수용 기준

1. `exposed/cache`에 backend-neutral internal coordinator와 deterministic
   conformance suite가 존재한다.
2. JDBC, R2DBC, suspended JDBC adapter가 coordinator로 admission/depth/
   worker/close arbitration을 공유한다.
3. 정상·double close, close 중 admission, queue full, timeout/interruption,
   cancellation, failed flush/retry와 redaction 테스트가 PASS한다.
4. 기존 DB transaction 경계, retry, cache publication/invalidation, public
   ABI와 `CacheHealthReport` 의미가 유지된다.
5. H2 및 해당되는 PostgreSQL/MySQL 검증, `git diff --check`, detekt/Kover
   영향 검사가 PASS한다.
6. P0/P1 review finding이 없고, Kotlin coroutine/exposed pattern과 Korean
   KDoc/manual writer gate를 통과한다.
7. 이 worktree의 변경만 구현하며 PR·merge·release는 수행하지 않는다.

## DoD

- [ ] coordinator invariant와 adapter callback 계약이 코드와 테스트에
  반영되었다.
- [ ] fake conformance suite가 deterministic하게 통과한다.
- [ ] JDBC/R2DBC/suspended JDBC의 targeted 및 기존 regression tests가
  PASS한다.
- [ ] H2 및 해당되는 PostgreSQL/MySQL Testcontainers 검증이 순차 PASS한다.
- [ ] public ABI, transaction/cancellation/close semantics와 redacted
  observability가 유지된다.
- [ ] EN/KO KDoc/README/manual과 test fixture 설명이 parity를 이룬다.
- [ ] P0/P1이 0이고 `git diff --check`, detekt/Kover 관련 검사가 PASS한다.
- [ ] 이슈·명세·계획·테스트·lesson 근거가 서로 추적 가능하다.

## 작성 게이트 (SPW)

| 항목 | 결과 | 근거 |
|---|---|---|
| SPW-01 | PASS | 이슈 URL, 기준 ref, worktree, 대상 독자(캐시 adapter 유지보수자·호출자), source ledger와 제외 범위를 명시했다. |
| SPW-02 | PASS | 문제, 구조, 내부 계약, 상태 흐름, 실패 모드, conformance suite, 성능, 호환성, 수용 기준과 DoD를 포함했다. |
| SPW-03 | PASS | Korean technical register와 `admission`, `flush`, `close`, `cancellation`, `lifecycle`, `adapter` 용어를 일관되게 사용했다. |
| SPW-04 | PASS | 세 Abstract repository와 `CacheHealthReport`, fixture, baseline test 결과를 대조해 기존 의미와 변경 경계를 고정했다. |
| SPW-05 | PASS | Markdown을 다시 읽어 코드 fence·표·목록·경로를 확인했으며 기술 placeholder가 없다. |
