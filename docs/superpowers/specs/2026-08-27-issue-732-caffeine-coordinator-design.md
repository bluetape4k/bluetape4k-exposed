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
  유지해 기존 retry 경로를 보존한다. `CancellationException`은 삼키지 않고
  flush/worker 경계에서 재전파해 terminal failure로 만든다.
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
internal enum class WriteBehindFailureKind {
    FLUSH,
    WORKER,
    CLOSE_TIMEOUT,
    CLOSE_INTERRUPTED,
}

internal enum class WriteBehindWorkerCompletion {
    DRAINED,
    CANCELLED,
    FAILED,
}

internal data class CoordinatorSnapshot(
    val mode: CacheWriteMode,
    val queueDepth: Int,
    val workerState: CacheWorkerState,
    val failureKind: WriteBehindFailureKind?,
)

internal class WriteBehindCoordinator(
    mode: CacheWriteMode,
) {
    fun reserveAdmission(): AdmissionToken
    fun settleEnqueue(token: AdmissionToken, accepted: Boolean)
    fun onFlushSucceeded(count: Int)
    fun onFlushFailed()
    fun onCloseFailed(kind: WriteBehindFailureKind)
    fun onWorkerCompleted(completion: WriteBehindWorkerCompletion)
    fun beginClose(): CloseLease
    fun publishCloseCompletion(owner: CloseLease.Owner, completion: CloseCompletion)
    fun snapshot(): CoordinatorSnapshot
}

internal sealed interface CloseLease {
    class Owner private constructor(internal val token: Any) : CloseLease {
        internal companion object {
            fun mint(token: Any): Owner = Owner(token)
        }
    }
    data object Follower : CloseLease
}

internal enum class CloseCompletionKind {
    COMPLETED,
    TIMEOUT,
    INTERRUPTED,
    FAILED,
}

internal data class CloseCompletion(
    val kind: CloseCompletionKind,
    val workerState: CacheWorkerState,
    val queueDepth: Int,
)
```

실제 이름과 private helper는 구현 중 Kotlin visibility에 맞춰 조정할 수
있지만 coordinator는 logical admission/state만 소유한다. `Channel`,
`CoroutineScope`, database transaction, flush wait, cache key/entity와
`Throwable` 저장·로그는 coordinator 경계 밖이다. `CloseLease.Owner` capability를
인자로 가진 호출만 `publishCloseCompletion(owner, completion)`을 실행할 수
있고, follower lease는 컴파일 시 이 API에 전달할 수 없다. `Owner`는
coordinator의 각 close마다 새 인스턴스로 mint하며 singleton이 아니다. companion의
`mint` 자체는 capability가 아니고, coordinator가 보관한 정확한 owner 인스턴스와
referential identity(`owner === activeOwner`) 및 unpublished→published CAS를
함께 검증해야 한다. 따라서 임의로 만든 동일 타입 owner, 두 번째 publish와
owner가 아닌 lease의 호출은 모두 실패한다.
`CloseLease.Follower`는 adapter가 제공하는 동일 completion signal을 기다린
뒤 immutable completion을 관찰하며 재-arbitration하지 않는다. close deadline,
monotonic clock, wait primitive와 interrupt 복원은 adapter 책임이다. 다음
invariant는 변경하지 않는다.

`onCloseFailed`는 `CLOSE_TIMEOUT`, `CLOSE_INTERRUPTED`, `WORKER` 중 close
경로에서 발생한 kind만 허용하고, `onFlushFailed`는 항상 `FLUSH`를 기록한다.
`onFlushSucceeded(count)`는 `0 <= count <= queueDepth`를 원자적으로 검증한다.

- `reserveAdmission`은 OPEN 상태에서만 성공하며 close 진입 이후 새 admission을
  거부한다.
- token은 `RESERVED → ENQUEUED → SETTLED`를 정확히 한 번만 거치며,
  accepted settlement가 관찰되기 전에는 worker가 해당 entry를 flush할 수
  없다. enqueue 실패/취소는 depth와 in-flight admission을 정확히 한 번만
  되돌리고 cache publication을 허용하지 않는다.
- `onFlushSucceeded(count)`는 음수 depth를 허용하지 않고 정확히 `count`를
  감소시키며 flush failure 관찰값을 `null`로 회복한다. `count`는 0 이상이고
  현재 depth 이하이어야 한다. `onFlushFailed()`는 `FLUSH`만 기록하고
  batch/depth를 감소시키지 않는다. close timeout/interruption은
  `onCloseFailed(CLOSE_TIMEOUT|CLOSE_INTERRUPTED)`로만 기록한다.
- close owner는 OPEN→DRAINING을 한 번만 수행하고 immutable completion을
  발행한다. follower는 상태를 재결정하지 않는다.
- `DRAINED` completion은 queue depth가 0일 때만 STOPPED가 되고,
  `CANCELLED`/`FAILED` 또는 residual depth가 있는 terminal completion은
  FAILED가 된다. close timeout/interruption은 각각 `CLOSE_TIMEOUT`/
  `CLOSE_INTERRUPTED`로 기록한다.
- coordinator snapshot은 `CoordinatorSnapshot`과 유한 `failureKind`만
  반환한다. `ID`, entity, cache key, SQL, URL, exception message와 raw
  `Throwable`은 저장·로그·metric tag로 만들지 않는다. 기존 public
  `CacheHealthReport.lastFlushError`가 필요한 adapter만 기존 legacy report
  경계에서 raw error를 자체 보관하고, coordinator snapshot과 새
  observation/metric에는 stable state/depth/count/kind만 내보낸다.

`exposed/cache`와 JDBC/R2DBC adapter는 서로 다른 Gradle module이므로 Kotlin
`internal`은 module 경계를 자동으로 넘지 않는다. `exposed/jdbc-caffeine`의
JDBC 및 suspended JDBC main compile task와 `exposed/r2dbc-caffeine` main compile
task 각각에 `exposed/cache` main output을 `-Xfriend-paths`로 연결하고, published
consumer compile에는 coordinator가 노출되지 않는지 `checkProductionAbi`와
clean external consumer fixture로 검증한다. friend-path 설정이 깨지면 build를
실패시키며 public `@Internal` facade나 coordinator signature의 ABI 노출로
대체하지 않는다.

### Adapter 책임

각 Caffeine adapter는 중복된 lifecycle field를 coordinator 호출로 바꾸되
다음 책임은 유지한다.

- `CoroutineScope`, `Channel`, `writeBehindQueueCapacity`, batch 수집과
  worker launch/cancellation, 그리고 adapter-owned bounded admission pool.
  pool은 raw `Semaphore.acquire()`처럼 무제한 waiter를 만들지 않고
  원자적 `tryReserve`로 `queued entry + blocked sender + in-flight entry`를
  하나의 pending permit으로 제한한다. permit이 없으면 새 호출은 즉시
  queue-full 결과를 받고 waiter로 등록되지 않는다. suspending `send`를
  사용하는 adapter도 먼저 permit을 예약한 호출만 channel send에서 대기할
  수 있으므로 blocked sender 수를 포함한 pending 수가
  `writeBehindQueueCapacity`를 넘지 않는다. 예약 token은 queue handoff,
  rejection/cancellation 또는 flush terminal settlement 중 정확히 한 경로에서
  반환되며 handoff 시 조기 반환하지 않는다.
- 모든 coordinator callback은 단일 선형화 지점에서 terminal state와 token
  상태를 먼저 확인한다. `STOPPED`/`FAILED` 이후의 late
  `onFlushSucceeded`·`onFlushFailed`·`onWorkerCompleted`는 report/depth/
  failure를 되돌리지 않는 no-op이고, 이미 settled 또는 close가 거부한 token의
  late `settleEnqueue`도 cache publication 없이 no-op/실패한다. accepted
  handoff가 선형화된 뒤 caller cancellation은 해당 token을 rollback하지
  않으며, adapter는 entry를 exactly-once settlement 대상으로 유지한다.
- JDBC `transaction`, R2DBC `suspendTransaction`, suspended JDBC
  `suspendedTransactionAsync`
- cache put/invalidate와 key별 publication guard. accepted handoff는
  `settleEnqueue(token, true)`만 호출하고 끝내지 않는다. adapter는 먼저
  `PublicationLease`를 원자적으로 획득한 뒤에만 accepted settlement를
  선형화하고, 그 lease를 실제 `cache.put`의 결과(성공·일반 예외·취소)가
  관찰될 때까지 유지한다. 따라서 worker의 flush가 permit을 정산했더라도
  in-flight publication lease가 남아 있으면 close가 cache invalidate를
  먼저 실행할 수 없다. lease와 coordinator token은 각각 한 번만 release하며,
  accepted handoff 뒤 caller cancellation도 publication lease를 rollback하지
  않는다.
- publication lease는 adapter-owned terminal gate와 함께 동작한다. owner가
  close를 시작하면 먼저 새 publication lease를 차단하고, 이미 획득한 lease의
  drain barrier를 기다린 뒤에만 invalidate 단계로 넘어간다. 실제 `cache.put`은
  lease의 `beforeCommit` 확인과 non-suspending atomic put 구간 안에서만
  호출하며, close의 invalidate도 같은 commit lock을 사용한다. 이 commit
  lock은 `beforeCommit` 확인과 synchronous `cache.put` 원자 구간에만 짧게
  잡고 publication lease drain 동안에는 절대 보유하지 않는다. queue send가
  성공한 뒤 `PublicationLease` 획득과 `settleEnqueue(token, true)`의
  선형화는 terminal gate와 같은 lock/원자 경계에서 수행한다. close가 그
  경계를 먼저 차지하면 token은 accepted가 되지 않고 put도 호출하지 않는다.
  반대로 accepted 경계가 먼저 끝나면 lease가 close admission에 포함되어
  실제 `cache.put` 완료(성공·예외·취소)까지 drain 대상이 된다. close가
  `beforeCommit` 전에 guard를 terminal로 바꾸면 put을 건너뛰고 key를
  invalidate 대상으로 남긴다. 이 two-phase guard와 commit lock 때문에
  close가 invalidate를 끝낸 뒤 지연된 caller가 다시 dirty entry를 게시할
  수 없다. guard 판정과 put 호출 사이를 임의의 `suspend`/dispatcher 전환으로
  벌리지 않으며, adapter가 제공하지 않는 non-cooperative cache backend는 이
  계약의 대상이 아니다.
- existing adapter-specific `afterPersisted` hook (현재 JDBC adapter만 제공),
  raw `lastFlushError`를 포함한 기존 public report와 backend별 transaction
  retry
- `closeWaitDuration`(기본 30초)의 owner/follower wait, interrupt 복원,
  cache invalidate와 scope 종료. adapter 생성 시 duration은 finite positive로
  검증하고 monotonic deadline을 계산한다. follower는 owner 결과를 기다리기만
  하며 재-arbitration하지 않는다.
- adapter별 bounded final flush와 cleanup 순서. owner는 매 flush/backoff 전에
  deadline의 remaining을 계산해 `withTimeoutOrNull(remaining)`으로 감싸고,
  JDBC는 interruptible/query-timeout 경계를, R2DBC는 cancellable suspend
  경계를 사용한다. 기존 cancellation 회귀가 요구하는 final flush는
  `withContext(NonCancellable) { withTimeoutOrNull(remaining) { ... } }`로
  감쌀 수 있지만 remaining을 초과하거나 무제한 wait를 허용하지 않는다.
  owner finalizer의 순서는 (1) terminal gate를 먼저 설치해 새 admission과
  상태 변경을 막고, (2) worker를 cancel/interrupt한 뒤 remaining 안에서
  bounded join을 시도하며, join이 끝나지 않으면 post-terminal side-effect
  guard를 원자적으로 설치해 late worker callback이 cache/state/metric을
  바꾸지 못하게 하고, (3) cache invalidate와 scope cancellation을 bounded
  cleanup으로 수행한 다음, (4) cleanup이 끝난 뒤에만 coordinator에 immutable
  close completion을 publish해 follower signal을 마지막에 연다. 어느 경로도
  cleanup 전에 completion을 공개할 수 없다. deadline에 도달하면 stable
  failure를 publish하고 close를 반환하며 coordinator는 `runBlocking`을 호출하지
  않는다. library가 만든 worker는 join 또는 guard가 확인된 뒤 남지 않아야 하며,
  non-interruptible 외부 driver가 만든 thread의 종료 보장은 caller 계약으로
  남긴다.

실패한 flush batch는 adapter가 `retryBatch`로 별도 보관하며 크기를
`writeBehindBatchSize`로 제한한다. retry 중인 batch에 새 entry를 append하지
않고 retry batch를 먼저 처리한다. retry backoff는 내부 capped exponential
정책(10ms 시작, 최대 1초)으로 제한하고
`MAX_FLUSH_RETRY_ATTEMPTS = 8`을 고정해 public 설정이나 unbounded busy loop를
추가하지 않는다. 최초 flush를 포함한 시도가 이 상한에 도달하면 해당
`retryBatch`/depth/error를 보존한 채 worker를 `FAILED`로 종료하고 더 이상
재시도하지 않는다. 재시도 성공 시 attempt와 failure/error observation을
초기화한다. retry log sampler는 adapter/operation별로 최초 실패 1회, failure
kind 상태 전환 1회와 동일 상태에서 1초 간격의 sample만 허용해 bounded
volume을 보장한다.

JDBC의 non-suspending `trySend`와 R2DBC/suspended JDBC의 cancellable
`send`는 adapter가 선택한다. `exposed/cache/src/main/kotlin/io/bluetape4k/exposed/cache/internal/WriteBehindCoordinator.kt`
에 canonical internal/private 상수
`MAX_WRITE_BEHIND_QUEUE_CAPACITY = 100_000`을 한 번만 정의하고, 세 adapter와
config validation은 이 상수만 참조한다. 이는 현재 public `LocalCacheConfig` API가
아니다. 해당 상수의 유한 상한과 `batchSize` 이상 조건을 검증하며
`writeBehindBatchSize`와
`writeBehindQueueCapacity` 모두 `1..MAX_WRITE_BEHIND_QUEUE_CAPACITY` 범위여야
한다. `Int.MAX_VALUE`, `MAX+1`과 overflow를 허용하지 않는다. coordinator는
send를 blocking하거나 `runBlocking`을 호출하지
않는다. suspended JDBC의 기존 public interface에 `validateConsistency`를
추가하지 않는다.

## 상태 전이와 데이터 흐름

```text
put(write-behind)
  -> coordinator.reserveAdmission()
  -> adapter queue trySend/send
       실패·취소 -> coordinator settle(false) -> cache 미게시
       성공     -> adapter PublicationLease 획득
                -> coordinator settle(true)
                -> publication guard beforeCommit + cache.put
                      -> 성공/실패/취소 관찰 후 lease release
                      -> cache.put 실패 -> key invalidate
  -> worker batch flush
       성공     -> depth 감소 + persisted hook
       일반 오류 -> failureKind=FLUSH + lastFlushError 기록, batch 유지, retry
       취소/미처리 예외 -> failureKind=WORKER, worker FAILED, queue 종료

close()
  -> coordinator.beginClose()
       owner    -> terminal gate, DRAINING, adapter queue close, bounded wait
       follower -> owner completion 대기
  -> worker cancel/interrupt + bounded join 또는 post-terminal side-effect guard
  -> publication lease drain/terminal guard (close readiness에 포함)
  -> adapter cache invalidate + scope cancel (bounded cleanup)
  -> cleanup 완료 후 owner가 immutable completion publish (follower signal last)
       drain 완료 + depth=0 -> STOPPED/COMPLETED
       timeout/interruption/terminal residual -> FAILED + immutable outcome
```

retry batch는 새 queue entry와 합쳐지지 않으며 capped backoff 동안 worker가
retry batch를 먼저 처리한다. `close()`가 retry backoff 또는 bounded final
flush를 기다리는 중 deadline에 도달하면 owner는 짧은 commit lock 구간에서
terminal gate와 publication terminal guard를 먼저 설치하고 즉시 lock을
해제한다. worker 취소/interrupt와 bounded join 또는 post-terminal side-effect
guard를 적용한 뒤, close 시작 시 계산한 하나의 absolute deadline의
`remaining`을 그대로 publication lease drain에도 사용한다. drain 중에는
commit lock을 잡지 않으며, `beforeCommit` barrier에서 대기하던 caller가
guard를 관찰하고 lease를 해제할 수 있어 self-deadlock이 없다. drain이 끝난
뒤에만 같은 commit lock을 짧게 다시 획득해 invalidate를 선형화하고 곧바로
해제한다. 이미 획득된 publication lease가 `beforeCommit`에서 취소를 관찰하면
underlying `cache.put`을 호출하지 않고 해제한다. lease가 실제 non-suspending
put 구간에 들어간 경우에는 그 구간이 끝날 때까지 invalidate와 직렬화되어야
하며, close completion signal은 모든 publication lease가 성공·실패·취소로
terminal settlement 된 뒤에만 열린다. non-cooperative 외부 backend가 이
구간을 반환하지 않으면 해당 close는 `COMPLETED`가 될 수 없고 `FAILED`와
caller-contract 위반으로 기록한다. 이후 cache invalidate와 scope cancellation을 수행한 뒤에만
`CLOSE_TIMEOUT`/`CLOSE_INTERRUPTED` completion을 publish한다. follower는 이
마지막 signal 전까지 반환하지 않는다. `NonCancellable` final flush를
사용하는 adapter는 반드시 동일한 remaining timeout 안에서만 실행하고, close
반환을 연장하는 무제한 flush를 허용하지 않는다. timeout 뒤의 cleanup도
짧고 bounded하게 유지한다.
`MAX_FLUSH_RETRY_ATTEMPTS`에 도달한 영구 flush 실패도
동일하게 worker `FAILED`와 immutable close failure 관찰값을 남기며, retry
backoff를 계속 예약하지 않는다.

`CacheHealthReport`의 mode, queueDepth, workerState와 lastFlushError 값은
기존 observer가 읽던 시점 의미를 유지한다. close 이후 반복해서 보고서를
읽어도 상태가 되돌아가지 않는다. `CacheHealthReport`는 adapter가
`CoordinatorSnapshot`을 기존 public 형태로 변환하며, raw `Throwable`과
cache 식별자는 공통 snapshot·metric·로그 경계를 통과하지 않는다.
기존 `lastFlushError: Throwable?` 필드와 serial UID는 ABI 때문에 유지하되,
legacy report serialization에서만 호환 필드로 직렬화하고, 새 coordinator
API·snapshot·metrics·logs·serialization DTO에는 raw error를 절대 복사하지
않는다. legacy raw field와 redacted observation은 별도 경계로 검증한다.
`CancellationException`은 `lastFlushError`에 기록하지 않고 failure kind와
worker state로만 관찰한다.

호환성 검증은 `CacheHealthReport`, `LocalCacheConfig`, JDBC/R2DBC/Suspended
JDBC public interface, `AbstractJdbcCaffeineRepository`,
`AbstractSuspendedJdbcCaffeineRepository`, `AbstractR2dbcCaffeineRepository`,
`CachePersistedWrite`의 기존 API dump를
각각 baseline 파일로 고정한다. 구현 후 `checkProductionAbi`가 성공하는 것과
별도로 세 adapter의 baseline 파일이 byte-for-byte 변경되지 않아야 한다.
`CacheHealthReport`의 기존 serial UID
`-1428853048381429257L`와 `LocalCacheConfig`의 `1L`을
`ObjectStreamClass.lookup(...).serialVersionUID`로 검증하고, 기존 serialized
fixture를 역직렬화해 wire-format 호환성을 확인한다. `internal` coordinator가
JVM classfile에 존재한다는 사실은 공개로 간주하지 않으며, 공개 Kotlin
signature/API dump와 clean Kotlin consumer compile 결과에 coordinator FQCN이
없다는 것을 경계의 정의로 삼는다.

## 실패 모드와 대응

1. **admission accounting underflow 또는 double settlement**  
   token을 `RESERVED → ENQUEUED → SETTLED`로 단 한 번만 settle할 수 있게
   만들고, fake adapter에서 성공·실패·cancellation을 섞은 동시 admission을
   반복한다. accepted settlement 전 worker flush가 관찰되거나 depth가
   음수가 되거나 close가 조기에 완료되면 테스트를 실패시킨다. bounded
   admission pool은 capacity 초과 호출을 즉시 거부하고 waiter를 추가하지
   않으며, 예약·handoff·settlement가 각 token의 permit을 정확히 한 번만
   반환하는지 확인한다.
2. **close와 in-flight send의 순서 역전**  
   close owner가 DRAINING을 publish하기 전에는 admission을 허용하고,
   이후에는 즉시 거부한다. blocked send가 cancellation되면 cache가 publish되지
   않고 owner가 해당 admission을 기다린 뒤 같은 close 결과를 publish하는지
   latch/barrier 테스트로 고정한다. accepted handoff 뒤 cancellation과 close
   이후 late settlement가 depth/failure를 되돌리지 않는지 확인한다.
3. **flush failure 뒤 데이터/health 상태 유실**  
   일반 예외는 `failureKind=FLUSH`와 adapter-owned `lastFlushError`만
   갱신하고 batch/depth를 보존한다. 한 번 실패한 fake flush가 재시도에
   성공하면 depth가 정확히 0이 되고 failure/error가 `null`로 회복되는지
   확인한다. `MAX_FLUSH_RETRY_ATTEMPTS`를 초과하는 permanent failure는
   retry를 중단하고 batch/depth/error를 보존한 `FAILED` terminal로 남기며,
   반복 retry/log가 무한히 계속되지 않는지 확인한다.
4. **cancellation을 일반 오류로 삼켜 worker가 계속 실행됨**  
   `CancellationException`은 adapter flush와 cancellable send에서 항상
   재전파한다. send cancellation은 rollback/no-publication, flush
   cancellation은 worker `FAILED`/queue 종료로 기록한다. close의
   adapter-specific bounded `NonCancellable` final flush는 remaining timeout
   안에서만 허용하고, 취소된 caller를 무기한 붙잡지 않는지 검증한다.
5. **close timeout/interruption에서 follower가 서로 다른 결과를 봄**  
   owner/follower 동시 close와 interrupt를 실행해 하나의 immutable
   `TIMEOUT` 또는 `INTERRUPTED` 결과와 `FAILED` report만 관찰되는지 검증한다.
  `CloseLease.Follower`가 `publishCloseCompletion`에 전달되지 않고, fresh
  per-close owner의 정확한 인스턴스만 한 번 publish할 수 있는지도
  compile/runtime 테스트한다. 동일 타입의 forged `Owner`와 이전 close의
  owner를 전달하면 identity 검증에서 거부되어야 한다.
6. **민감한 key/exception이 공통 계층에 유입됨**  
   coordinator API에 key/entity/message 인자를 두지 않고, 모든 adapter
   flush/worker/retry/close log·metric·hook은 stable
   `component`, `operation`, `failureKind`만 label/field로 남긴다.
   `queueDepth`는 non-negative gauge 값으로만 기록하고 label로 사용하지
   않는다. retry failure log는 batch/adapter별 최초 실패, failure kind 상태
   전환, 1초 이상 간격의 주기 sample만 허용한다. log/metric/serialization
   assertions에서 cache key·entity·cacheName·SQL·URL·credential/raw cause가
   나오지 않는지 확인한다.
7. **accepted queue 뒤 cache publication 실패**
   queue admission이 accepted 된 뒤에는 publication lease가 실제 cache put
   완료까지 유지되어 close invalidate와 경합하지 않게 한다. cache put이
   실패하면 key를 invalidate하고 lease/guard를 정확히 한 번 해제한다.
   cancellation은 원본을 재전파하고 일반 failure도 기존 caller 전파 계약을
   유지하되 coordinator snapshot, `CacheHealthReport`, log, metric, 새
   serialization DTO에는 raw Throwable을 넣지 않는다. queue/worker state와
   public consistency report가 오염되지 않는지 adapter contract test로
   고정한다. blocked put barrier에서 close가 terminal guard를 설치한 뒤에는
   지연된 caller가 underlying `cache.put`을 실행할 수 없고, invalidate와
   completion signal이 put 완료/거부보다 먼저 관찰되지 않는지 함께 검증한다.
8. **무한 queue capacity 또는 ABI drift**
   `LocalCacheConfig`의 capacity가 `batchSize..MAX_WRITE_BEHIND_QUEUE_CAPACITY`
   범위인지, `Int.MAX_VALUE`와 overflow가 거부되는지 검증한다. suspended
   JDBC public interface의 method-set/descriptor는 변경하지 않는다.

9. **모듈 경계·운영 증거가 구현과 어긋남**
   `-Xfriend-paths`가 `exposed/jdbc-caffeine`의 JDBC 및 suspended JDBC main
   compile task와 `exposed/r2dbc-caffeine` main compile task 모두에 적용되는지, published
   artifact의 public API에 coordinator가 나타나지 않는지 확인한다. DB matrix는
   다음과 같이 순차 실행하고 결과를 분리한다.

   | Adapter | Required DB evidence | 환경 판정 |
   |---|---|---|
   | JDBC Caffeine | H2 → PostgreSQL → MySQL_V8 | 실행 실패는 FAIL, container unavailable은 PENDING |
   | suspended JDBC Caffeine | H2 → PostgreSQL → MySQL_V8 | 실행 실패는 FAIL, container unavailable은 PENDING |
   | R2DBC Caffeine | H2; PostgreSQL/MySQL fixture가 존재할 때만 각 순서로 실행 | fixture 부재는 명시적 N/A이며 PASS로 세지 않음 |

   이 matrix는 기존 병렬 nightly matrix의 요약값으로 대체하지 않는다. 별도의
   단일 `verify-write-behind-db-matrix` job(또는 동일 의미의 Gradle
   verification task)이 adapter별로 H2 → PostgreSQL → MySQL_V8 순서를
   직렬로 실행하고, 각 단계의 `status`, `reason`, `startedAt`, `finishedAt`,
   `adapter`, `database`를 담은 versioned manifest를
   `build/verification/write-behind-db-matrix.json`에 생성한다. job의 마지막
   aggregator는 manifest의 각 required 행을 읽어 `PASS`만 green으로 집계하며,
   `FAIL`, `PENDING`, `N/A`, `SKIPPED`를 하나라도 required 행에서 발견하면
   non-green으로 종료한다. 기존 nightly 병렬 matrix는 빠른 피드백용으로
   유지할 수 있지만 이 manifest/aggregator의 대체 증거가 될 수 없다. R2DBC
   PostgreSQL/MySQL fixture가 없으면 해당 행만 `N/A`로 쓰고, 컨테이너를
   시작할 수 없는 환경은 `PENDING`으로 쓰며, 테스트 코드의 조건부 skip은
   `SKIPPED`로 명시한다. 어떤 상태도 PASS로 위장하거나 조용히 누락하지
   않는다. CI에서는 manifest가 없거나 JSON schema/version이 맞지 않아도
   non-green으로 종료한다.

   `PENDING`, `N/A`, `SKIPPED`는 green으로 합산하지 않으며, 최종 DoD에는
   해당 상태와 원인을 기록한다. timeout/interruption log와 hook schema는
   `component ∈ {jdbc,suspended-jdbc,r2dbc}`, `operation ∈ {flush,close}`와
   `failureKind ∈ {flush,worker,close_timeout,close_interrupted}` 및
   non-negative `queueDepth`만 허용한다. suspended JDBC는 public health
   report/hook이 없으므로 close timeout/interruption을 stable log로만
   관찰한다.

   새 metric inventory는 `build/verification/write-behind-metrics.json`에
   이름, type, unit, labels, owner를 기록한다. flush/worker/retry/close의
   stable metric은 기존 adapter별 이름과 tag set을 유지하며, 공통
   coordinator metric을 추가하지 않는다. 각 logical event가 정확히 한 번만
   기록되고 `queueDepth`는 gauge 값일 뿐 label이 아님을 inventory와 capture
   테스트로 검증한다. inventory가 없거나 baseline과 다르면 non-green이다.

## Conformance suite

DB 없는 fake adapter contract는 다음 경로에 둔다.

`exposed/cache/src/test/kotlin/io/bluetape4k/exposed/cache/internal/WriteBehindCoordinatorTest.kt`

필수 시나리오는 정상 admission/flush, double close, close 전후 admission,
queue full 즉시 거부, JDBC `trySend`와 R2DBC/suspended JDBC cancellable `send`의
서로 다른 backpressure/호출자 결과, blocked send cancellation, in-flight flush
cancellation, timeout, interruption, retryable flush failure와 성공 후 error
reset, `MAX_FLUSH_RETRY_ATTEMPTS`에 따른 permanent failure terminal 전환과
worker completion, immediate-drain happens-before,
accepted queue 뒤 publication lease의 cache.put 완료/거부, blocked
cache.put과 close의 ordering, cache.put 실패, bounded retry batch/backoff와 지속 장애,
state/depth invariant와 sanitized observation/log/serialization이다. 시간은
주입 가능한 deterministic clock/barrier와 fake backoff로 제어해 sleep 기반
flaky test를 피한다. admission token의 중복 settlement와 accepted settlement
전 flush 금지도 테스트한다. 동시 producer stress는 pending admission이
capacity를 넘지 않고, capacity 초과 호출이 무제한 waiter를 만들지 않으며,
permit이 handoff/settlement에서 정확히 한 번 반환되고 `Int` overflow가
발생하지 않음을 검증한다. close fake flush는 deadline 안에 반환되고
  `NonCancellable` cleanup 이후 library-owned post-close worker가 남지 않는지,
  completion signal이 invalidate/scope cancellation보다 먼저 공개되지 않는지
  barrier로 확인한다. publication lease를 획득한 뒤 `cache.put` 직전 barrier에서
  close를 시작하는 시나리오도 포함한다. close가 terminal guard를 선형화한
  뒤에는 blocked caller가 underlying put을 실행하지 않고 lease를 terminal
  settlement하며, close의 lease drain이 commit lock을 보유하지 않아 caller가
  barrier를 해제할 수 있다는 사실을 검사한다. 이미 commit lock에 들어간 put은
  완료된 뒤에만 invalidate가 실행되고 owner/follower signal이 열린다는
  happens-before를 검사한다. drain과 invalidate에 동일한 absolute deadline을
  사용하고, deadline 만료 시에도 모든 lease가 terminal settlement되기 전에는
  `COMPLETED` signal을 열지 않는지 검증한다.
  non-interruptible 외부 driver thread의 종료는 caller contract로 별도
  기록한다.

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
- suspended JDBC public interface의 method-set/descriptor가 늘지 않는지와
  기존 reflection/ABI fixture가 계속 통과하는지
- `CloseLease` owner/follower, duplicate publish, idle close, timeout/interruption
  결과가 동일 immutable completion을 관찰하는지, owner capability가 아닌
  lease와 duplicate publish가 거부되는지, terminal 이후 late callback과
  accepted-handoff 뒤 cancellation이 FAILED/STOPPED 상태를 덮어쓰지 않는지
  확인한다.

기존 JDBC 405개(22 skipped), R2DBC 120개(1 skipped) baseline 테스트는
삭제하지 않는다. 새 targeted suite 후 H2, PostgreSQL, MySQL 순서로
Testcontainers 검증을 adapter별로 순차 실행한다. unsupported fixture는
`N/A`, 컨테이너를 시작할 수 없는 환경은 `PENDING`, 실제 assertion 실패는
`FAIL`로 기록하며 skip을 성공으로 세지 않는다.

## 성능·안정성 기준

- write-behind hot path에 DB transaction, blocking wait, `runBlocking`을
  coordinator가 추가하지 않는다.
- admission/close 상태는 기존 lock/atomic 수준의 bounded contention으로
  처리하고, worker 수와 queue capacity를 늘리지 않는다.
- token과 callback allocation을 batch가 아닌 entry당 필요한 최소 수준으로
  유지하며, stress test에서 queue depth가 정확히 0으로 수렴해야 한다.
- coordinator는 queue/channel/callback 대기와 worker allocation을 맡지
  않으며, queue capacity와 worker 수는 adapter 설정을 그대로 사용한다.
- pending permit은 queue handoff 뒤에도 flush terminal settlement까지
  유지하므로 resident entry의 최대치는 `writeBehindQueueCapacity`로
  bounded된다. queue depth는 metric label이 아닌 gauge 값으로만 노출하고,
  retry failure log는 최초 실패·상태 전환·주기 샘플만 기록해 cardinality와
  log volume을 제한한다.
- close wait budget은 기존 설정값(기본 30초)을 그대로 사용하며, timeout 뒤
  scope cancellation과 cache invalidate의 순서는 기존 adapter 계약을 지킨다.
  `closeWaitDuration`은 adapter 생성 시 finite positive로 검증한다. final
  flush는 deadline remaining을 전달받는 cancellable/interruptible 경계에서
  실행하고, `NonCancellable`은 deadline 이후의 짧은 cleanup에만 적용하며,
  deadline 이후에는 worker 취소와 stable failure log를 남긴다.

## 문서와 호환성

public cache interface와 published artifact 이름은 바꾸지 않는다. KDoc은
coordinator가 내부 상태·admission만 단일화하지만 Channel, close wait, DB
flush/transaction, raw report/error와 cleanup은 adapter가 소유한다는 경계를
설명한다. JDBC/R2DBC/suspended JDBC cache manual의 write-behind lifecycle
절을 EN/KO로 맞추고, 현재 README의 close·cancellation 예제를 새
conformance contract와 일치시킨다. suspended JDBC에는 public health report가
없으며 bounded close 실패는 adapter log/hook 관찰만 가능하다는 점도 명시한다.
영구 실패는 capped backoff로 재시도되며 durable dead-letter/outbox는
라이브러리가 제공하지 않고 application-owned outbox 범위로 명시한다.
EN/KO KDoc·README·manual에는 `writeBehindQueueCapacity`와
`writeBehindBatchSize` 모두 `1..100_000`, queue capacity는 batch size 이상이라는
정확한 범위와 기존 `>100_000` 설정의 startup validation failure를 기록한다.
다음 실행 가능한 rollout/rollback runbook도 같은 문서와 구현 계획에
고정한다.

1. 배포 전 owner(`debop`)가 `./gradlew validateWriteBehindConfiguration`
   (또는 구현 시 동일 의미의 adapter validation task)를 실행하고,
   `build/verification/write-behind-config.json`에서 모든 adapter의
   capacity/batch/close duration 검증과 ABI/friend-path 검증이 PASS인지
   확인한다. 하나라도 `FAIL`, `PENDING`, `N/A`, `SKIPPED`이면 rollout을
   중단한다.
2. canary에서 adapter별 matrix manifest와 `workerState`, `queueDepth`,
   `failureKind`를 관찰한다. 영구 flush failure로 `FAILED`가 발생하거나
   bounded retry cap에 도달하면 rollout trigger를 즉시 발동하고 owner는
   새 write-behind traffic을 차단한 뒤 해당 인스턴스를 이전 정상 artifact와
   설정으로 rollback한다. 이미 admission된 `retryBatch`와 residual depth는
   메모리 상태로 보존되며, coordinator가 폐기하거나 자동으로 재전송하지
   않는다.
3. rollback 뒤 owner는 기존 adapter의 health/report와 manifest를 보존하고,
   애플리케이션 소유 durable outbox 또는 수동 replay 도구로 보존된 batch의
   재처리 여부를 결정한다. 라이브러리는 durable dead-letter/outbox, 자동
   중복 제거, 외부 queue publish API를 제공하지 않는다. replay가 필요하면
   애플리케이션이 원래 순서와 idempotency 정책을 책임진다.
4. rollback 성공 조건은 새 `FAILED`/close timeout이 없고, required matrix가
   다시 PASS하며, residual depth와 retryBatch의 처리 결정을 운영 기록에
   남기는 것이다. 조건을 충족하지 못하면 rollout은 `PENDING`으로 남기고
   재시도하지 않는다.

`putAll`은 entry별 admission 결과를 순서대로 적용하며 앞선 entry가 accepted
된 뒤 다음 entry가 full/cancelled 되면 앞선 publication은 유지되고 실패
지점의 예외만 caller에게 반환하는 부분 성공 의미를 명시한다.
새로운 public dependency나 개별 BOM 좌표는 추가하지 않는다.

## 수용 기준

1. `exposed/cache`에 backend-neutral internal coordinator와 deterministic
   conformance suite가 존재하고, JDBC/R2DBC adapter compile task에는
   `-Xfriend-paths`가 연결되며 published ABI에는 coordinator가 노출되지
   않는다.
2. JDBC, R2DBC, suspended JDBC adapter가 coordinator로 admission/depth/
   worker/close arbitration을 공유하되 Channel, capacity, close wait, DB
   transaction, raw report/error와 cleanup은 각 adapter가 소유한다.
3. 정상·double close, close 중 admission, queue full/backpressure,
   timeout/interruption, cancellation, failed flush/retry, bounded retry batch와
   cache publication failure, redaction/serialization 테스트가 PASS한다.
4. 기존 DB transaction 경계, retry, cache publication/invalidation, public
   ABI/method-set와 `CacheHealthReport` 의미가 유지된다.
5. adapter별 H2 및 해당되는 PostgreSQL/MySQL 검증이 `FAIL`/`PENDING`/`N/A`를
   구분해 기록되고, required matrix가 PASS하며 `git diff --check`,
   detekt/Kover 영향 검사가 PASS한다.
6. P0/P1 review finding이 없고, Kotlin coroutine/exposed pattern과 Korean
   KDoc/manual writer gate를 통과한다.
7. 이 worktree의 변경만 구현하며 PR·merge·release는 수행하지 않는다.

## 리뷰 보완 결정

6개 관점 독립 리뷰에서 확인된 P1을 다음과 같이 해소한다.

| 리뷰 항목 | 결정 | 검증 근거 |
|---|---|---|
| coordinator 경계 | coordinator는 logical admission/state와 유한 failure kind만 소유하고 Channel, scope, DB flush/transaction, close wait, raw report/error는 adapter가 소유한다. 모듈 간 접근은 `-Xfriend-paths`로만 허용하고 published ABI에는 노출하지 않는다. | friend-path compile 및 ABI boundary test |
| cancellation | send/flush의 `CancellationException`은 항상 재전파한다. send는 rollback/no-publication, flush는 worker `FAILED`로 처리하며 close의 adapter-specific `NonCancellable` 경계는 보존한다. | blocked-send·in-flight-flush·close cancellation 테스트 |
| admission happens-before | token은 `RESERVED → ENQUEUED → SETTLED` 단 한 번만 전이하며 accepted settlement 전에 worker가 flush하지 못한다. accepted queue 뒤 cache.put 실패는 invalidate/guard release한다. | barrier, double-settlement, publication-failure contract 테스트 |
| failure 회복 | 실패 batch는 `retryBatch`로 bounded 보존하고 새 입력과 합치지 않으며 10ms 시작·1초 상한 backoff를 사용한다. retry 성공은 queue depth와 failure kind 및 adapter-owned `lastFlushError`를 함께 회복한다. | 지속 장애 stress 및 retry/permanent-failure 테스트 |
| capacity와 close | adapter/config validation 경계의 internal `MAX_WRITE_BEHIND_QUEUE_CAPACITY = 100_000`과 batch size의 finite range를 검증하고 adapter permit으로 in-flight admission을 제한한다. fresh per-close owner의 referential identity/CAS가 deadline·interrupt arbitration을 보호한다. owner는 terminal gate, cancel/interrupt, bounded join 또는 side-effect guard, invalidate/scope cleanup 순으로 수행한 뒤 completion을 마지막에 publish하며 follower는 동일 immutable completion만 관찰한다. | boundary, permit stress, timeout/interruption, forged-owner/double-close 및 cleanup-order barrier 테스트 |
| ABI·관찰성 | 기존 `CacheHealthReport.lastFlushError`/serial UID와 suspended JDBC method-set은 유지한다. legacy report serialization의 raw field는 호환을 위해 유지하되, coordinator snapshot·새 metric/log/serialization DTO에는 raw Throwable, key/entity/cacheName, SQL·URL·credential을 추가하지 않는다. timeout/interruption schema는 stable fields로만 기록한다. | `Class.forName`/reflection/legacy serialization·redaction/log capture 테스트 |

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
