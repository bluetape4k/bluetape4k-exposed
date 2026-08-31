# Issue #768 batch 실행 lease 갱신과 lease-loss fencing 설계

## 문서 상태

- 대상 이슈: [#768](https://github.com/bluetape4k/bluetape4k-exposed/issues/768)
- 기준 저장소: `bluetape4k/bluetape4k-exposed`
- 기준 ref: `origin/develop@f2f8d9e6bd5e08bce9c0496067f7fed1dc1483a6`
- 작업 branch: `fix/issue-768-batch-lease-renewal`
- workflow: Type A `bluetape-full-feature`
- 승인된 방향: owner+version 기반 갱신, 내부 lease guard, bounded heartbeat,
  lease-loss 시 fail-fast와 외부 writer의 idempotency/fencing 책임 문서화
- 구현 상태: 설계·검토 단계

이 명세는 Job/Step 실행 소유권의 수명과 runner가 외부 write를 시작할 수 있는
조건을 고정한다. 공개 writer API에 fencing token을 추가하지 않으며, 이 문서에
없는 분산 lock·새 dependency·backend별 정책은 별도 이슈로 분리한다.

## 문제와 목표

현재 `BatchJob`은 Job을 claim할 때 `executionLease`를 한 번 계산한다.
`BatchStepRunner`는 그 만료 시각을 Step claim에도 재사용하지만, reader·processor·
writer 실행 중 lease를 갱신하지 않는다. 긴 chunk가 lease를 넘으면 다른 runner가
만료된 실행을 인수할 수 있다. 기존 owner의 늦은 checkpoint와 completion은
owner+version CAS로 거부되지만, 이미 발생한 외부 write는 되돌릴 수 없다.

이번 변경의 목표는 다음과 같다.

1. JDBC/R2DBC/InMemory repository에 Job과 Step의 owner+version lease renewal
   계약을 추가한다.
2. runner가 현재 lease의 안전 구간 안에서만 다음 외부 write를 시작하도록 한다.
3. heartbeat를 bounded lifecycle로 실행하고 runner 종료 시 누수 없이 정리한다.
4. renewal 실패나 lease 상실을 정상 경쟁 결과로 분류하고 실행을 즉시 중단한다.
5. stale owner의 checkpoint·completion 거부와 새 owner의 단독 진행을 회귀
   테스트로 고정한다.
6. library fencing의 한계와 외부 writer의 idempotency·fencing 책임을 repository
   README와 운영 artifact에 명시하고, 중앙 사용자 manual에는 별도 downstream
   handoff로 전달한다. 이 repository에는 `docs/manual/` tree를 만들지 않는다.

## 현재 근거

| 근거 | 현재 사실 | 설계 영향 |
|---|---|---|
| `BatchJob.run()` | claim 시 로컬 `Instant.now().plus(executionLease)`를 한 번 계산 | runner가 DB authoritative claim과 monotonic guard를 사용하도록 바꿔야 한다. |
| `BatchStepRunner.run()` | Step claim이 Job의 기존 `leaseUntil`을 그대로 사용 | Step은 갱신된 Job lease와 같은 owner를 사용해야 한다. |
| `BatchJobRepository` | claim과 owner-aware checkpoint만 있고 renewal API가 없다 | Job/Step renewal을 공개 repository 계약으로 추가한다. |
| JDBC/R2DBC repository | claim·checkpoint·completion이 owner+version CAS를 사용 | renewal도 동일한 fail-closed 조건과 version 증가를 사용한다. |
| completion | terminal 전이 시 owner/lease를 null로 지우고 version을 증가 | heartbeat가 terminal 행을 되살리지 못하게 status를 함께 검사한다. |
| writer API | 외부 시스템의 fencing token을 받지 않는다 | public writer ABI를 확장하지 않고 책임과 한계를 문서화한다. |

## 선택한 계약

### Repository renewal

`BatchJobRepository`에 다음 이름과 시그니처의 suspend API를 추가한다.

```kotlin
data class BatchExecutionLeaseSnapshot(
    val jobExecution: JobExecution,
    val stepExecution: StepExecution?,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

suspend fun renewExecutionLeases(
    jobExecution: JobExecution,
    stepExecution: StepExecution?,
    leaseDuration: Duration,
): BatchExecutionLeaseSnapshot?

suspend fun claimJobExecution(
    execution: JobExecution,
    ownerId: String,
    leaseDuration: Duration,
): JobExecution?

suspend fun claimStepExecution(
    execution: StepExecution,
    ownerId: String,
    leaseDuration: Duration,
): StepExecution?
```

여기서 `Duration`은 `java.time.Duration`이다. direct `BatchJob` constructor의 기존
`executionLease: Duration = Duration.ofMinutes(15)`을 public DSL과 같은 validator에
연결한다. duration 범위 오류는 민감 정보 없는 고정 prefix의 `IllegalArgumentException`,
unsupported repository default는 `UnsupportedOperationException`, ownership 경쟁은
`null`로 구분한다.

`stepExecution=null`은 Step 사이의 Job-only 구간을 뜻하고, non-null이면 Job과 Step을
**한 repository transaction에서 원자적으로 함께** 갱신한다. 성공 조건은 대상 행마다
다음을 모두 만족하는 것이다.

- `id`가 같다.
- 상태가 `RUNNING`이다.
- 저장된 `ownerId`가 전달된 execution의 non-blank owner와 같다.
- 저장된 `version`이 전달된 execution의 version과 같다.
- 저장된 `leaseUntil`이 backend authoritative time 기준으로 아직 만료되지 않았다.
- `leaseDuration`이 30초 이상 24시간 이하이고 overflow 없이 계산 가능하다.
- repository가 계산한 새 `leaseUntil = authoritativeNow + leaseDuration`이 기존 값보다 뒤다.

성공 시 모든 대상의 `leaseUntil`을 같은 authoritative time 기준으로 갱신하고 각각의
`version`을 1 증가시킨 최신 snapshot을 반환한다. 어느 한 행이라도 경쟁 패배, terminal
전이, owner 변경, stale version이면 transaction 전체를 rollback하고 `null`을 반환해
Job만 또는 Step만 장시간 연장되는 partial renewal을 만들지 않는다. 잘못된 owner와
범위를 벗어난 duration은 argument/domain validation으로 거부하고, 계산된 새 lease가
어느 기존 값보다 뒤지 않으면 mutation 없이 `null`을 반환한다. runner는 절대 시각을
만들지 않고 검증된 `Duration`만 전달한다.

기존 `Instant leaseUntil` claim overload는 source/binary compatibility를 위해 유지하지만
deprecated compatibility 경계로 표시하고 기존 custom repository에만 위임한다. built-in
runner와 built-in repository는 새 `Duration` overload만 사용한다. 새 overload의 기본
구현은 기존 absolute-time overload로 fallback하지 않고 정확히
`UnsupportedOperationException`을 던진다. `supportsLeaseRenewal=true`인 repository는
authoritative claim overload도 함께 구현해야 한다.

initial claim은 renewal과 별도 predicate를 갖는다. 전달된 execution의 `ownerId`는 비어
있어도 되며 별도 인자의 non-blank `ownerId`를 새 owner로 저장한다. `id`와 version은
전달값과 같아야 하고, 다음 상태만 claim할 수 있다.

- `STARTING`: owner/lease가 null인 최초 claim
- `FAILED` 또는 `STOPPED`: restart claim
- `RUNNING`: owner/lease가 null이거나 lock 이후 DB authoritative time 기준 만료된 takeover

`COMPLETED`와 `COMPLETED_WITH_SKIPS`, 유효 lease의 `RUNNING`, stale version은 `null`이다.
claim 성공은 status `RUNNING`, 새 owner, `authoritativeNow + leaseDuration`, version + 1,
`endTime=null`을 같은 mutation에 저장하고 readback한 execution을 반환한다. renewal은
반대로 전달 execution과 저장 행의 동일한 non-blank owner, `RUNNING`, 같은 version,
미만료 lease를 요구한다. 두 계약의 predicate와 테스트를 공유 helper 이름으로 섞지 않는다.

JDBC/R2DBC renewal은 같은 transaction에서 Job→Step 고정 순서로 대상 행을 모두
`SELECT ... FOR UPDATE`한 뒤 DB server current timestamp를 한 번 읽는다. 모든 lock 획득
후 읽은 `authoritativeNow`로 각 status·owner·version·`leaseUntil > authoritativeNow`를
재검증하고, 전부 유효할 때만 update/readback한다. 하나라도 실패하면 전체 rollback한다.
따라서 timestamp를 읽은 뒤 row lock을 기다리는 동안 lease가 만료되는 stale-time
resurrection과 half-renewed Job/Step이 없다. initial claim도 같은 순서와 DB server time으로
`leaseUntil = authoritativeNow + leaseDuration`을 계산한다. InMemory는 repository lock을
획득한 뒤 주입된 clock을 읽어 같은 경계를 제공한다. 기본 interface renewal 구현은 정확히
`UnsupportedOperationException`을 던져 낙관적 성공으로 우회하지 않는다. public
property `supportsLeaseRenewal: Boolean`의 기본값은 `false`다. built-in 구현만 `true`와
실제 renewal을 함께 제공한다. 현재 build의 `-jvm-default=enable` 계약 아래 default
body를 유지하고, 기존 구현 binary fixture와 Kotlin/Java source consumer,
`apiCheck`/API dump로 ABI·source compatibility를 검증한다.

InMemory 구현은 기존 serialization 경계 안에서 동일한 owner·version·status·만료
검사를 수행한다. 이 repository API는 trusted runner 내부 전용 소유권 primitive다.
외부 요청의 `JobExecution`, `ownerId`, version을 그대로 받아 호출하는 endpoint가
아니며, HTTP/message/multi-tenant adapter는 인증·인가와 tenant namespace 검증 뒤
runner가 claim한 객체만 전달해야 한다. 이 trust boundary를 넘는 직접 노출은
지원하지 않고 foreign owner와 cross-namespace 입력은 mutation 전에 거부한다.

### Lease guard와 heartbeat

core에는 runner 내부 전용 combined lease guard를 둔다. guard는 Job과 현재 Step의
최신 execution 기준 데이터를 하나의 `Mutex`로 소유하고 repository의 atomic combined
renewal을 직렬화한다. guard 자체는 공개 API나 ABI로 노출하지 않으며 새 dependency를
추가하지 않는다.

guard의 불변 조건은 다음과 같다.

1. 한 execution에 대해 renewal 호출은 동시에 두 개 실행되지 않는다.
2. heartbeat와 checkpoint가 모두 version을 증가시키므로 최신 execution 기준 데이터 교체를
   하나의 `Mutex` 임계구역에서 직렬화한다. 별도 Job/Step lock은 만들지 않는다.
3. 다음 외부 write 직전에 `checkBothAndMaybeRenew`가 Job과 Step lease를 같은
   임계구역에서 확인한다.
4. guard는 claim/renewal repository 호출 직전의 `TimeSource.Monotonic` mark를 잡고,
   성공 시 `callStartedMark + executionLease`를 해당 execution의 보수적 local deadline으로
   저장한다. DB mutation은 call 시작 뒤 발생하므로 이 deadline은 실제 DB expiry보다 늦을
   수 없고, 응답 지연은 usable lease를 줄이는 방향으로만 작용한다. DB의 절대 `leaseUntil`과
   node wall clock을 직접 비교하지 않는다. 모든 scheduling 값은 millisecond precision으로
   내림한다. `leaseMillis = executionLease.toMillis()`를 overflow 검사 후 구하고,
   `repositoryTimeoutMillis = min(leaseMillis / 6, 30_000)`,
   `safeMarginMillis = max(leaseMillis / 3, multiplyExact(repositoryTimeoutMillis, 2))`로
   고정한다. public 최소 lease에서는 각각 5,000ms와 10,000ms다. monotonic deadline의
   잔여 구간이 `safeMarginMillis` 이하이면 synchronous renewal을 먼저 수행한다.
5. renewal이 `null`이거나 예외로 확정 실패하면 원인을 포함한 단일 lease-loss
   상태를 한 번만 기록하고 internal `LeaseLostException`을 structured scope에
   전달해 sibling 작업을 취소한다. 모든 write·checkpoint·completion gate가 이 상태를
   먼저 확인한다.
6. heartbeat job은 caller scope의 일반 child이며 `supervisorScope`로 분리하지
   않는다. 정상 종료와 lease-loss도
   `withContext(NonCancellable) { withTimeout(repositoryTimeoutMillis) { heartbeat.cancelAndJoin() } }`의
   cleanup gate를 사용한다. supported adapter에서는 statement 취소, rollback, pool 반환과
   child join이 timeout 안에 끝나야 한다. timeout은 child를 강제 종료했다는 뜻이 아니며
   invariant breach로 분류해 정상 completion을 금지하고 process health를 degraded로 바꾸고
   새 scheduling을 pause한 뒤 supervisor termination을 요구한다. 정상 실행·lease-loss 경로의 repository I/O에는
   `NonCancellable`을 사용하지 않으며, 아래 외부 caller cancellation의 bounded terminal
   compensation만 유일한 예외다.

initial claim도 repository에 `Duration`을 전달하고 성공한 호출 시작 mark로 Job/Step
deadline을 각각 초기화한다. checkpoint readback은 version 기준 데이터만 교체하며
deadline을 새로 계산하지 않는다. node wall-clock이 앞서거나 뒤진 환경에서도 write
허용 여부는 오직 이 보수적 monotonic deadline과 repository authoritative 결과로 결정한다.

heartbeat 간격은 execution lease의 1/3로 계산한다. public `executionLease`는
30초 이상 24시간 이하이어야 하며 기본값은 기존과 같은 15분이다. duration 계산은
overflow를 검사한다. 간격당 atomic combined renewal을 1회만 수행하고 실패 retry는 하지
않는다. Step이 없으면 Job row만, 활성 Step이 있으면 Job과 Step row를 한 transaction에서
갱신한다. combined persistent renewal은 timeout prior-value read/설정/restore 최대 3,
Job/Step row-lock read 각 1, authoritative-time read 1, Job/Step update와 readback 각 1로
최대 10 SQL statement다. PostgreSQL은 transaction-local 설정과 자동 restore로 최대 8,
MySQL/H2는 최대 10이며 capacity gate는 공통 최댓값 10을 사용한다. initial Job/Step claim은
기존 entity별 최대 7을 유지한다. 따라서 public 최소 lease의 active Step runner는 10초마다
combined renewal 1회, 최대 10 SQL statement다. 테스트를 위해 clock/delay 경계는
internal로 주입할 수 있지만 production public constructor에는 scheduler를 노출하지
않는다.

repository I/O는 cancellation-aware하며 위에서 계산한 정확한
`withTimeoutOrNull(repositoryTimeoutMillis)`를 적용한다. 이 timeout의 `null`만
lease-loss로 바꾸고 caller scope의 외부
`CancellationException`은 재전파한다. 비취소 구간은 성공한 readback을 guard 기준 데이터로 교체하는 짧은
메모리 연산과 child cleanup에만 사용한다. transaction/readback 전체를
`NonCancellable`로 감싸지 않는다. 단, 이미 취소된 caller context에서 STOPPED를
best-effort로 남기는 아래 bounded terminal compensation은 이 일반 renewal 규칙의
예외다. timeout, cancellation 이외의 예외, `null`
결과는 모두 no-write lease-loss로 전환한다.
renewal latency histogram은 성공·실패 응답뿐 아니라 repository timeout도
`repositoryTimeoutMillis`로 clamp한 timeout 표본으로 포함한다. alert threshold는 같은
calculator에서 `min(executionLease / 12, repositoryTimeoutMillis * 4 / 5)`로 계산해
항상 timeout보다 낮게 유지하며 millisecond precision으로 내림한다.

coroutine timeout만으로 blocking driver 종료를 주장하지 않는다. persistent adapter는
각 renewal/claim transaction 시작 전에 다음 server/driver timeout을
`repositoryTimeoutMillis` 이하로 설정한다. PostgreSQL transaction-local 값은 commit/rollback이
자동 복원하고, MySQL/H2 session 값은 prior-value read와 `finally` restore를 수행한다. broken
connection은 restore 대신 폐기해 pool에 반환하지 않는다.

- PostgreSQL: transaction-local `lock_timeout`과 `statement_timeout`, JDBC network/query
  timeout, R2DBC statement timeout
- MySQL 8/InnoDB: 초 단위 올림 `innodb_lock_wait_timeout`, JDBC network/query timeout,
  R2DBC statement timeout. 올림값이 repository timeout을 넘는 잔여 구간은 driver/network
  timeout이 상한을 소유한다.
- H2: session `LOCK_TIMEOUT`, JDBC network/query timeout

JDBC timeout/cancellation은 running statement를 cancel/interrupt하고 transaction rollback과
connection pool 반환이 끝날 때까지 virtual-thread child를 structured하게 join한 뒤 guard
`Mutex`를 해제한다. R2DBC도 subscription cancellation 뒤 rollback과 connection close/return
signal을 await한다. server/driver timeout이나 cancellation을 지원하지 않아 orphan transaction
종료와 pool 반환을 증명할 수 없는 custom adapter는 `supportsLeaseRenewal=false`다. timeout
뒤 같은 connection에서 transaction이 계속 실행되거나 guard가 다음 mutation을 허용하는
detached cleanup은 금지한다.

`BatchJobBuilder`에는 `executionLease(Duration)` DSL을 추가하고 같은 validation을
적용한다. builder가 설정한 값은 Job guard와 각 Step guard에 동일하게 전달한다.
따라서 direct `BatchJob` 생성과 DSL 생성이 다른 lease 정책을 사용하지 않는다.
기존 DSL을 변경하지 않은 소비자는 15분 기본값을 그대로 사용한다.

`BatchJobRepository`에는 additive capability `supportsLeaseRenewal`을 두고 기본값을
`false`, InMemory/JDBC/R2DBC 구현을 `true`로 한다. heartbeat를 사용하는 runner는
claim 전에 capability를 검사해 unsupported custom repository를 실행 중간이 아니라
시작 전에 fail-closed 한다. custom repository upgrade 순서는 Duration 기반
`claimJobExecution`, `claimStepExecution`, atomic `renewExecutionLeases` 세 API를 구현하고
동일한 owner/version/authoritative-time 및 all-or-nothing test를 통과한 뒤 마지막에
`supportsLeaseRenewal=true`를 선언하는 것이다. 세 API 중 하나라도 없거나 capability가
false이면 시작 전에 거부한다. scheduling pause가 유일한
kill switch이며 heartbeat만 끄고 multi-runner write를
계속하는 unsafe fallback은 제공하지 않는다.

combined guard는 전체 Job 생명주기를 감싼다. 각 Step은 Job owner를 공유하면서
Step 실행 기준 데이터도 같은 guard에서 갱신한다. Step write 전에는 Job과 Step
lease를 모두 확인해야 한다. 한쪽이라도 상실되면 writer를 호출하지 않는다.
checkpoint 저장과 heartbeat는 같은 Step version stream을 사용하므로 guard가
최신 StepExecution을 받아 다음 연산에 전달한다.

성공·업무 실패·외부 cancellation의 모든 Step completion은 combined guard의 Step gate
안에서 heartbeat/checkpoint와 직렬화하고 `latestStepExecution()` snapshot을 CAS 입력으로
사용한다. Job의 `COMPLETED`, `FAILED`, `STOPPED` completion도 새 heartbeat 진입을 닫고
child를 `cancelAndJoin`한 뒤 guard의 `latestJobExecution()` snapshot을 가져와 CAS 입력으로
사용한다. 초기 `claimedJobExecution`을 어느 completion 경로에서도 재사용하지 않는다.
단, owner를 잃은 `LeaseLostException` 경로는 snapshot을 읽더라도 completion mutation을
호출하지 않는다. 정상 heartbeat가 한 번 이상 version을 올린 뒤 성공·업무 실패·외부
cancellation이 각각 최신 version으로 terminal 전이하는 경로를 회귀 테스트로 고정한다.
성공·업무 실패로 반환되는 `BatchReport.jobExecution`은 초기 claim이 아니라 completion
CAS에 사용한 최신 guard snapshot에서 terminal status와 owner/lease 해제를 반영해 만든다.
report의 version은 post-update DB readback이 아니라 CAS 입력 version임을 public KDoc에
명시하고, heartbeat가 올린 version보다 과거 값을 반환해서는 안 된다.

internal `withWritePermit`은 combined guard 안에서 `checkBothAndMaybeRenew`와 one-shot
permit 소비를 수행한 뒤 같은 call stack에서 전달된 suspend block의 첫 문장인
`writer.write()`를 호출한다. caller가 permit 객체를 보관하거나 permit 발급 뒤 별도
suspend 함수를 호출할 수 있게 raw permit을 노출하지 않는다. 이 경계는 이미 관찰된
lease-loss 뒤 write가 시작되는 것을 막지만, OS scheduler pause나 process stop으로
permit 발급 뒤 lease가 원격에서 만료되는 간극을 제거하지는 못한다. repository와
외부 시스템을 하나의 원자적 transaction으로 묶지 않는 한 그 간극은 library
내부 token으로 해결할 수 없으며, 아래 외부 writer fencing 계약이 최종 방어다.

### Lease-loss 의미

lease-loss는 일반적인 업무 오류가 아니라 실행 소유권 상실이다. core 내부의
`LeaseLostException : RuntimeException`으로 즉시 중단한다. heartbeat child가 이를
throw하면 structured scope가 sibling을 취소하고 scope boundary가 원래
`LeaseLostException`을 다시 던진다. `BatchJob.run()`은 이 예외를 외부
`CancellationException`보다 별도 catch하여 completion 저장 없이
`BatchReport.Failure`로 변환한다. 최종 Failure에는 allowlist 기반 category와 sanitized
domain message만 보존한다. `Failure.error`는 cause·suppressed exception이 없는 stable
domain exception으로 새로 만들고 raw JDBC/R2DBC exception, SQL, 접속 URL, credential,
ownerId, raw params를 public report/message에 포함하지 않는다.
`BatchExecutionAlreadyClaimedException`을 포함한 public claim-conflict message도 ownerId를
출력하지 않는다. 운영 로그도 같은 redaction을 적용하고 backend 원인은 public 객체에
포함되지 않는 내부 진단 category와 correlation id로 연결한다.

lease-loss, Job/Step claim-conflict와 repository/cleanup infrastructure 실패를 public
`BatchReport.Failure`로 반환하는 경로는 다음 additive public type을 `Failure.error`로
사용한다. 기존 reader/processor/writer 업무 실패의 원본 `Throwable` 보존 계약은 바꾸지
않는다.

```kotlin
class BatchInfrastructureFailureException(
    val category: String,
    val correlationId: String,
) : RuntimeException("$category; correlationId=$correlationId") {
    companion object {
        const val LEASE_LOST: String = "BATCH_LEASE_LOST"
        const val EXECUTION_ALREADY_CLAIMED: String = "BATCH_EXECUTION_ALREADY_CLAIMED"
        const val REPOSITORY_FAILURE: String = "BATCH_REPOSITORY_FAILURE"
    }
}
```

public constructor는 category가 위 allowlist constant 중 하나이고 correlationId가 canonical
UUID인지 검증하며 raw cause 인자를 열지 않는다. exception의 cause·suppressed 목록은 비어
있어야 한다. Kotlin `internal` constructor가 JVM/Java에서 public으로 노출되는 방식에는
의존하지 않는다. Kotlin/Java source, API dump, serialization과 invalid-constructor 및
exhaustive category fixture로 class, constructor, public property, constant와 stable message
format을 고정한다.

`Failure.jobExecution`은 초기 claim이 아니라 guard가 마지막으로 성공한 claim/renewal
readback에서 보유한 최신 local snapshot에서 만든 **sanitized projection**이다. id, jobName,
status, version, 생성·시작·종료 시각처럼 복구 판정에 쓰지 않는 allowlist metadata만
보존하고 `params=emptyMap()`, `ownerId=null`, `leaseUntil=null`로 강제한다. 포함되는
`StepReport`도 stepName, status, read/write/skip count, duration 같은 allowlist만 보존하고
checkpoint는 null, error는 cause 없는 stable category exception으로 치환한다. 이 값은
소유권을 마지막으로 확인한 시점의 진단 snapshot일 뿐 현재 DB state나 현재 owner를
나타내는 권위 있는 recovery snapshot이 아니다. public KDoc은 `LEASE_LOST` category에서
이 값을 재시작·completion 판정에 사용하지 말고 correlation id로 repository를 read-only
재조회하도록 명시한다.
post-loss read가 실패해도 report 생성을 막지 않으며 새 owner 정보를 Failure에 복제하지
않는다.

초기 Job claim-conflict에는 성공한 guard snapshot이 없으므로 예외적으로
`findOrCreateJobExecution`이 반환한 pre-claim candidate를 같은 sanitized projection으로
변환한다. Step claim-conflict는 성공한 Job guard snapshot과 해당 Step의 pre-claim
candidate를 각각 sanitize한다. find/create 자체가 candidate를 만들기 전에 실패한
infrastructure 예외는 구성할 `JobExecution`이 없으므로 `BatchReport.Failure`로 위조하지
않고 sanitized public exception으로 전파한다. 각 경로의 snapshot source를 테스트 이름과
KDoc에 고정한다.
이미 owner를 잃었으므로 오래된 execution 기준 데이터로 `STOPPED`나 `FAILED` completion을
재시도해 현재 owner의 행을 변경하지 않는다. repository CAS가 이를 거부하는 것은
정상 방어이며 원래 lease-loss보다 앞선 원인으로 보고하지 않는다.

coroutine의 외부 `CancellationException`은 기존 계약대로 STOPPED completion을 시도한
뒤 재던진다. 이 보정 경로 전체를
`withContext(NonCancellable) { withTimeout(repositoryTimeoutMillis) { ... } }`의 단일
bounded cleanup envelope로 감싼다. envelope 안에서 heartbeat 신규 진입을 먼저 닫고
`cancelAndJoin`한 뒤 최신 Job/Step snapshot을 잡아 STOPPED terminal repository 호출을
시도한다. supported adapter에서는 join, snapshot 조회, terminal 호출의 합계가 같은
timeout 안에 끝나야 한다. cancellation에 응답하지 않는 child를 coroutine timeout이 강제
종료한다고 주장하지 않는다. timeout은 sanitized
`batch.lease.cancellation_completion_failed` event와 원래 cancellation에 suppressed
진단 category를 남기고 process health를 degraded로 바꾸며 scheduling을 pause한다. child가
실제로 종료된 경우에만 원래 cancellation을 재던지고, invariant breach로 child가 남으면
supervisor termination 외 정상 복귀를 주장하지 않는다. 정상 completion,
renewal, checkpoint, lease-loss에는 이 `NonCancellable` 예외를 확장하지 않는다.
`LeaseLostException`으로 인해 sibling에 전달된 내부 cancellation은 외부
caller cancellation으로 분류하지 않으며 STOPPED/FAILED completion을 시도하지 않는다.
heartbeat 자체의 정상 cleanup cancellation도 외부 취소로 오인하지 않는다. renewal의
일시적 backend 예외를 lease 만료 이후까지 무한 재시도하지 않으며, 다음 write
전 안전을 증명하지 못하면 fail-closed 한다.

### 외부 writer fencing 경계

이 변경은 같은 repository를 사용하는 runner가 lease-loss를 인지한 뒤 새로운
외부 write를 시작하지 못하게 한다. 이미 시작된 네트워크 호출, 외부 transaction,
process pause 이후 재개된 호출까지 library가 취소하거나 원격 시스템에서 fence할
수는 없다.

따라서 외부 side effect가 있는 운영 writer는 다음 중 하나 이상을 **필수 전제**로
적용해야 한다. 이 전제를 만족하지 않는 non-idempotent writer는 lease 기반 다중 runner
운영에서 unsupported이며 fail-closed 배포 검증으로 거부한다.

- item/chunk의 idempotency key와 upsert
- 외부 시스템이 지원하는 조건부 version/fencing
- 중복 소비를 안전하게 만드는 transactional outbox 또는 deduplication

public `BatchWriter`에 fencing token을 추가하는 선택은 기존 구현 전체의 ABI와
외부 시스템 계약을 바꾸므로 이번 범위에서 거부한다. library가 writer capability를
runtime introspection하지 않는다. 대신 repository가 제공하는 다음 실행 artifact를
application release owner가 채우고 CI/release gate가 검증한다.

- `utils/batch/operations/batch-writer-safety-checklist.md`: 작성 지침과 recovery 절차
- `utils/batch/operations/batch-writer-safety.schema.json`: machine-readable schema
- `utils/batch/operations/batch-writer-safety.example.yaml`: 최소 통과 예시
- `scripts/batch/validate_batch_writer_safety.rb`: dependency 없는 validator

application receipt는 YAML top-level `schemaVersion`, `application`, lowercase 40-hex
`releaseHead`, `releaseOwner`, `generatedAt`, `writers`를 갖는다. schema는
`writers.minItems=1`, unique writer id,
각 `sideEffect.minLength=1`을 강제한다. 별도 application-owned
`batch-writer-inventory.yaml`도 non-empty configured writer id 목록과 같은 `releaseHead`,
config checksum을 기록하며 validator는 두 파일의 release head와 writer id set이 정확히
같은지 비교한다. receipt나
inventory 한쪽에만 있는 writer, 빈 inventory, duplicate id는 모두 실패다. 각 writer는 stable `id`, `sideEffect`, 그리고
`idempotencyKey`, `remoteFencing`, `transactionalOutbox` 중 하나 이상의 non-empty
evidence URI/checksum, `recoveryReceipt`, `reviewedAt`을 포함한다. credential·token·raw
payload는 금지한다. evidence와 recovery URI는 opaque restricted-store reference만
허용하며 URI userinfo, query, fragment, credential-bearing path 및 secret-like token
pattern을 schema와 validator가 거부한다. 검증 명령은
`ruby scripts/batch/validate_batch_writer_safety.rb <application-receipt.yaml> <batch-writer-inventory.yaml> --expected-release-head <40hex>`로 고정하며
통과는 exit 0, schema/evidence/head/owner mismatch는 exit 1, 사용법·파일 I/O 오류는 exit 2다. CI fixture는
example 통과, 빈 `writers`, 빈 inventory, inventory/receipt set mismatch, 빈 `sideEffect`,
세 방어가 모두 빈 writer의 실패를 검증한다. 이는 library runtime
acceptance가 아니라 운영 deployment prerequisite다.

### 관측성·운영 복구 계약

새 dependency 없이 기존 logging surface에 stable structured event를 남긴다.
event name은 `batch.lease.renewal`, `batch.lease.loss`, `batch.lease.guard.blocked`,
`batch.lease.capability.rejected`, `batch.lease.cancellation_completion_failed`로 고정하고
backend, execution kind, result category, 0 이상 `repositoryTimeoutMillis` 이하로
clamp한 정수 `latencyMillis`, expiry-margin bucket, correlation id만 기록한다. ownerId, raw params,
SQL/URL/credential은 기록하지 않는다. 운영 metrics는 이 event에서
`renewal_attempt_total`, `renewal_failure_total`, `lease_loss_total`, renewal latency,
expiry margin을 집계한다. latency p95는 bucket 문자열이 아니라 이 millisecond 표본으로
계산하고 timeout은 `repositoryTimeoutMillis` 표본으로 포함한다.

alert 계약은 `utils/batch/operations/batch-alerts.yaml`과
`utils/batch/operations/batch-alerts.schema.json`에 source-neutral하게 기록하고
`ruby scripts/batch/validate_batch_alerts.rb utils/batch/operations/batch-alerts.yaml`로
검증한다. exit contract는 writer validator와 같은 0/1/2다. 각 alert는 stable `id`,
`severity`, `owner`, `route`, `window`, `minimumSamples`, `condition`, `action`, `clearCondition`,
`resumeAuthority`를 포함한다. 필수 alert는 다음과 같다.

- `batch-lease-loss-critical`: severity `critical`, owner `batch-platform`, route
  `batch-oncall`, 1분 window·최소 1 sample에서 lease-loss 1건 이상이면 새 scheduling 중단,
  10분 연속 0건과 operator receipt 후 release owner가 재개한다.
- `batch-renewal-failure-high`: severity `high`, 같은 owner/route, 5분 window·최소 20
  attempts에서 failure ratio가 1% 초과이면 중단, 15분 연속 1% 이하와 DB health receipt
  후 release owner가 재개한다.
- `batch-renewal-latency-high`: severity `high`, 같은 owner/route, 5분 window·최소 20
  samples에서 timeout 표본을 포함한 p95가
  `min(executionLease / 12, repositoryTimeoutMillis * 4 / 5)` 초과이면 중단,
  15분 연속 임계 이하와 capacity receipt 후 release owner가 재개한다.
- `batch-cancellation-completion-failed-critical`: severity `critical`, 같은 owner/route,
  1분 window·최소 1 sample에서 cancellation STOPPED completion timeout/실패 1건 이상이면
  해당 logical key의 새 scheduling을 중단하고 terminal state를 reconciliation한다.
  10분 연속 0건과 reconciliation receipt 후 release owner가 재개한다.

모든 alert resume 증적은
`utils/batch/operations/batch-alert-resume-receipt.schema.json`과
`utils/batch/operations/batch-alert-resume-receipt.example.yaml`을 사용한다. receipt는
`schemaVersion`, application, exact `releaseHead`, `releaseOwner`, alert id, alert owner/route,
triggeredAt, clear-window 시작·종료와 sample count/result, resume authority/role, approvedAt,
expiresAt, protected approval-store reference와 signature/checksum, required evidence 배열을
기록한다. evidence 각 항목은 `operator|db-health|capacity|reconciliation` kind, credential-free
opaque URI와 lowercase SHA-256을 갖고 alert 정의가 요구한 kind와 정확히 일치해야 한다.
검증 명령은
`ruby scripts/batch/validate_batch_alert_resume.rb <receipt.yaml> --alerts utils/batch/operations/batch-alerts.yaml --expected-release-head <40hex>`다.
validator는 exact head/owner, alert id/route, clear condition/window/sample, required evidence
checksum, authority/signature/expiry를 검증한다. exit 0은 resume 가능, exit 1은 모든
schema/head/owner/alert/window/evidence/authority mismatch, exit 2는 사용법·파일 I/O다.
URI userinfo/query/fragment/credential-bearing path와 secret-like token은 거부한다.

heartbeat에는 owner별 결정적 0~10% jitter를 적용해 동시 runner burst를 분산한다. 배포 전
capacity receipt는 `maxConcurrentRunners`, one-second token bucket의
`maxJobStartsPerSecond`/`maxJobStartBurst`,
`maxStepStartsPerSecond`/`maxStepStartBurst`, 승인 DB statement/s와 burst budget을 고정한다.
public 최소 lease의 steady-state 상한
`1.0 × maxConcurrentRunners + 7 × maxJobStartsPerSecond + 7 × maxStepStartsPerSecond`와
one-second burst 상한
`10 × maxConcurrentRunners + 7 × (maxJobStartBurst + maxStepStartBurst + maxJobStartsPerSecond + maxStepStartsPerSecond)`가
모두 승인 budget 안이어야 canary를 시작한다. burst 식은 atomic combined heartbeat renewal의 1초 집중과
full token bucket의 초기 burst 및 1초 refill을 함께 포함한다. 실제 qps/latency는 각 단계에서
receipt와 대조한다.

capacity artifact는 `utils/batch/operations/batch-capacity-receipt.schema.json`,
`utils/batch/operations/batch-capacity-receipt.example.yaml`로 고정하고 application-owned
receipt는 `schemaVersion`, `application`, lowercase 40-hex `releaseHead`, `releaseOwner`,
`generatedAt`, 위 입력·승인 budget과 계산된 steady/burst 상한을 모두 기록한다. 검증 명령은
`ruby scripts/batch/validate_batch_capacity_receipt.rb <receipt.yaml> --expected-release-head <40hex>`다.
validator는 receipt SHA-256과 검증 시각을 출력하고 formula 재계산, exact release head,
non-blank owner, 모든 budget 충족을 확인한다. exit 0은 통과, exit 1은 schema/head/owner/
formula/budget mismatch, exit 2는 사용법·파일 I/O 오류다. CI fixture는 example 통과와 stale
head, 빈 owner, 변조된 계산값, steady 또는 burst budget 초과를 각각 실패로 고정한다.
repository-owned README en/ko와 이 machine artifact가 같은 명령·필드를 설명하며, 중앙
사용자 manual 갱신은 repository 구현과 분리된 downstream handoff다.

incident runbook은 새 scheduling 중단 → correlation id로 마지막 성공 renewal과
blocked mutation 확인 → 현재 DB owner/version/lease read-only 조회 → 외부
idempotency/outbox receipt reconciliation → 새 owner 재실행 여부 결정 순서다. lease-loss
runner를 같은 execution 객체로 retry하지 않는다.

### 단계적 rollout과 rollback

1. 모든 `BatchJobRepository` 구현과 외부 side-effect writer inventory를 만들고 renewal
   capability 및 idempotency/fencing 증거가 없는 소비자를 release gate에서 차단한다.
2. built-in repository를 사용하는 비운영/shadow workload에서 capability preflight,
   structured event, lease-loss runbook을 검증한다.
3. 단일 canary scheduler에서 시작해 1% → 10% → 50% → 100%로 job dispatch를 확대하고
   각 단계마다 `max(5분, 적격 표본 20개, 두 heartbeat window)` 동안 failure/latency/DB qps를
   관찰한다. critical 1-sample alert는 표본 수와 무관하게 즉시 중단한다.
4. 위 alert threshold, guard-blocked 증가, 외부 receipt mismatch가 발생하면 새 scheduling을
   즉시 중단하고 진행 중 runner를 drain하거나 fail-closed 종료한다.
5. code rollback은 새 dispatch를 중단하고 active owner/lease를 read-only inventory한 뒤,
   관찰된 마지막 active lease가 만료되고 외부 receipt reconciliation이 끝난 경우에만
   이전 version을 시작한다. 새·구 runner의 동시 write는 허용하지 않는다.

각 rollout 단계와 rollback은 prose만으로 승인하지 않는다. repository는 다음 durable
artifact를 소유한다.

- `utils/batch/operations/batch-rollout-observation.schema.json`과 통과 example
- `utils/batch/operations/batch-rollback-approval.schema.json`과 통과 example
- `utils/batch/operations/batch-rollback-receipt.schema.json`과 통과 example
- `scripts/batch/validate_batch_rollout.rb`
- `scripts/batch/validate_batch_lease_rollback.rb`

rollout observation은 `schemaVersion`, `application`, exact `releaseHead`, `releaseOwner`,
stage(`shadow|1|10|50|100`), 시작·종료 시각, eligible sample 수, 관찰 heartbeat window 수,
failure/latency/DB qps와 alert result, writer/capacity/alert artifact path와 SHA-256, result를
기록한다. 검증 명령은
`ruby scripts/batch/validate_batch_rollout.rb <observation.yaml> --writer-receipt <path> --capacity-receipt <path> --alerts <path> --alert-resume-dir <path> --expected-release-head <40hex>`다.
validator는 exact head/owner/checksum, `max(5분, 20 samples, 두 heartbeat window)`, critical
alert 0건과 승인 budget을 검증한다. stage 중 발동한 alert가 있으면 해당 alert의 valid
resume receipt path/SHA-256가 observation에 없거나 validator exit 0이 아니면 promotion을
차단한다.

rollback approval은 application, environment, exact release head, rollback target head,
approver, protected authority/role, approvedAt, expiresAt, protected approval-store reference와
signature/checksum을 기록한다. approval reference에는 credential을 넣지 않고 URI userinfo,
query, fragment, credential-bearing path와 secret-like token을 거부한다. validator는 현재
시각·application/environment·head·target·authority/signature를 이전 version 기동 전에
검증하며 expired, wrong-environment, unauthorized approval은 mutation 0건으로 차단한다.

rollback receipt는 exact release head/owner, rollback target head, approval path/checksum,
scheduling stop 시각,
active owner/lease inventory path와 SHA-256, 마지막 active lease, writer reconciliation
receipt path/checksum, drain/result 시각, old/new simultaneous writer 0건과 final result를
기록한다. 검증 명령은
`ruby scripts/batch/validate_batch_lease_rollback.rb <receipt.yaml> --approval <approval.yaml> --expected-application <id> --expected-environment <name> --expected-release-head <40hex>`다.
현재 시각이 마지막 active lease 이후이고 reconciliation checksum이 일치하며 result가
`ready`이고 approval authority/signature/expiry가 유효할 때만 exit 0,
schema/head/owner/environment/approval/window/alert/reconciliation mismatch는 exit 1,
사용법·파일 I/O는 exit 2다. 두 validator 모두 artifact SHA-256과 검증 시각을 출력하며
모든 non-zero exit는 다음 stage 또는 이전 version 기동을 차단한다.

## 선택지와 거부 이유

| 선택지 | 결정 | 이유 |
|---|---|---|
| 실행 시작 시 긴 lease 하나만 설정 | 거부 | 실행 시간을 예측할 수 없고 장애 감지 시간이 과도해진다. |
| heartbeat만 추가하고 write 전 검증 생략 | 거부 | scheduler 지연과 갱신 경쟁 사이에서 stale writer가 다음 write를 시작할 수 있다. |
| adapter마다 heartbeat 구현 | 거부 | JDBC/R2DBC 사이의 timing·취소 의미가 분기된다. |
| public writer fencing token 추가 | 거부 | 현재 writer ABI와 모든 consumer를 확대하며 원격 시스템 지원도 보장할 수 없다. |
| owner+version renewal + 내부 guard | 채택 | 기존 CAS 모델을 재사용하고 backend-neutral lifecycle을 core에 둔다. |

## 실패 모드와 대응

| 실패 모드 | 방지·검증 |
|---|---|
| heartbeat와 checkpoint가 같은 version으로 경쟁 | combined guard의 단일 `Mutex`와 repository atomic combined renewal로 기준 데이터와 mutation을 직렬화한다. |
| Job renewal 뒤 Step renewal이 실패해 Job만 장시간 연장 | Job→Step row lock과 all-or-nothing transaction으로 partial renewal을 만들지 않는다. |
| heartbeat가 terminal 또는 만료 행을 갱신 | repository 조건에 `RUNNING`, owner, version, `leaseUntil > authoritativeNow`를 모두 포함한다. |
| timestamp 조회 뒤 row lock 대기 중 lease 만료 | row를 먼저 잠그고 DB server time을 읽어 상태와 만료를 재검증한다. |
| node wall-clock skew로 만료 뒤 write 허용 | initial claim도 DB authoritative duration API를 사용하고 guard는 보수적 monotonic deadline만 비교한다. |
| scheduler 지연으로 lease 직전 write 시작 | write 직전 안전 구간 검사와 synchronous renewal을 수행한다. |
| renewal 후 응답 전에 caller 취소 | repository I/O는 취소·timeout 가능하게 두고 성공 readback의 짧은 기준 데이터 교체만 비취소 구간에서 수행한다. |
| 새 owner가 인수한 뒤 stale owner가 checkpoint/complete | 기존 owner+version CAS가 거부하고 lease-loss 원인을 유지한다. |
| heartbeat child가 runner 종료 뒤 남음 | structured child lifecycle과 cancel-and-join 회귀 테스트를 둔다. |
| repository 장애가 계속되는데 write를 진행 | renewal은 1회와 정해진 timeout으로 제한하고 안전성을 증명하지 못하면 write를 금지한다. |
| 이미 시작된 외부 호출이 lease 뒤 완료 | idempotency/remote fencing 없는 외부 side-effect writer를 unsupported로 두고 배포 검증에서 거부한다. |
| permit 발급 뒤 process pause 중 lease 만료 | local guard가 제거할 수 없는 간극으로 명시하고 외부 idempotency/fencing을 최종 방어로 요구한다. |
| custom repository가 renewal을 구현하지 않음 | claim 전 capability preflight로 거부하고 canary 대상에서 제외한다. |
| 다수 runner heartbeat가 같은 시각에 집중 | owner별 0~10% jitter, aggregate DB budget, renewal latency alert로 제한한다. |

## 테스트 전략

RED 테스트를 먼저 추가하고 의도한 failure message를 확인한다.

1. core fake repository로 heartbeat가 lease 만료 전에 Job/Step을 갱신하는지,
   version이 직렬로 증가하는지 검증한다.
2. renewal `null` 후 다음 chunk writer가 호출되지 않고 lease-loss failure가
   반환되는지 검증한다.
3. heartbeat·checkpoint 동시 timing과 caller cancellation에서 child leak,
   swallowed cancellation, stale version이 없는지 검증한다.
4. JDBC/R2DBC repository에서 wrong owner, stale version, terminal status,
   이미 만료된 저장 lease, non-increasing lease, 24시간 초과·overflow lease를 거부하고
   성공 시 최신 execution을 반환하는지 검증한다. 만료 뒤 takeover 전 stale renewal도
   반드시 `null`을 반환해야 한다.
5. JDBC/R2DBC 통합 barrier test에서 runner A의 lease가 상실되고 runner B가
   인수한 뒤 A의 다음 write·checkpoint·completion이 진행되지 않는지 검증한다.
6. heartbeat 예외·timeout이 원인을 보존한 lease-loss로 parent를 취소하고 모든
   mutation gate를 닫는지 검증한다.
7. permit 발급 전 barrier에서 새 owner가 인수하면 writer가 0회인지 검증하고,
   permit 발급 뒤 process pause 간극은 외부 fencing 없이는 보장하지 않음을 문서와
   test name에 고정한다.
8. supported Testcontainers backend는 repository guard에 따라 순차 실행한다.
9. README 영어·한국어 계약, repository operations artifact와 API KDoc을 함께 검증하고,
   중앙 사용자 manual downstream handoff 항목을 확인한다.
10. direct constructor와 DSL이 30초 미만·24시간 초과·overflow lease를 거부하고 기본
    15분 및 설정값을 Job/Step guard에 동일하게 전달하는지 검증한다.
11. fake repository로 간격당 atomic combined renewal 최대 1회, timeout 설정/복원 최대 3,
    Job/Step row-lock·update·readback 각 1회와 authoritative-time read 1회, 총 최대 10 SQL,
    retry 0회와 timeout no-write를 검증한다. PostgreSQL 최대 8, MySQL/H2 최대 10의
    adapter별 budget과 어느 predicate 실패에서도 두 version/lease가 모두 불변임을 고정한다.
12. public report와 운영 로그에 SQL, 접속 URL, credential, ownerId, raw params가
    노출되지 않고 foreign owner·cross-namespace 입력이 거부되는지 검증한다. lease-loss,
    claim-conflict, infrastructure failure의 `Failure.error`, `Failure.jobExecution`,
    `StepReport`, `toString()`과 JSON serialization을 각각 검사해 params는 empty, owner/lease와
    checkpoint는 null, error는 cause 없는 allowlist category임을 고정한다.
    초기 Job claim-conflict는 pre-claim candidate, Step claim-conflict는 최신 Job guard와
    pre-claim Step candidate를 사용하며 find/create pre-candidate 실패는 report 없이
    sanitized exception을 전파하는지 분리해 검증한다.
13. writer safety checklist fixture의 idempotency/fencing/outbox 및 owner/receipt 항목이
    비어 있으면 release 문서 검증이 실패하는지 검증한다.
14. structured event redaction, alert 집계 필드, capability preflight, deterministic jitter를
    검증하고 canary/rollback runbook을 README en/ko 및 repository operations artifact와 parity
    검사한다. `docs/manual/` tree는 생성하지 않는다.
15. `withTimeoutOrNull` 자체 timeout, 외부 cancellation, 정상 child cleanup을 분리하고
    `withWritePermit` 밖으로 raw permit을 꺼낼 수 없는 compile/runtime fixture를 검증한다.
16. JDBC/R2DBC barrier에서 timestamp를 읽기 전에 row lock을 기다리게 하고, 대기 중
    lease가 만료되면 lock 이후 authoritative time 재검증으로 renewal이 `null`인지 확인한다.
17. DB clock보다 앞선 node와 뒤진 node를 각각 주입해 initial Duration claim과 monotonic
    write guard가 DB 만료 이후 write를 허용하지 않고, 응답 지연만 usable lease를
    보수적으로 줄이는지 검증한다.
18. 정상 heartbeat가 Job/Step version을 한 번 이상 증가시킨 뒤 성공, 업무 실패의 FAILED,
    외부 cancellation의 STOPPED completion이 모두 guard의 최신 snapshot을 사용하고 초기
    claimed execution의 stale version을 쓰지 않는지 검증한다. LeaseLostException은
    completion을 0회 호출하고 Failure에는 마지막으로 성공한 guard readback snapshot을
    진단용으로 반환해야 한다. 초기 claim이나 새 owner의 post-loss state를 반환해서는 안
    되며 KDoc의 read-only reload 지침을 fixture로 고정한다.
19. heartbeat `LeaseLostException`은 sibling을 중단하고 sanitized `BatchReport.Failure`를
    반환하며 completion을 호출하지 않는 반면, 외부 `CancellationException`은 STOPPED
    completion 시도 후 원래 cancellation을 재던지는지 분리해 검증한다.
20. 30초, 31초, 15분, 24시간 lease에서 millisecond 내림, timeout, safe margin,
    `multiplyExact` overflow 규칙이 같은 calculator를 사용하고 boundary 값과 일치하는지
    검증한다.
21. 기존 custom repository binary/source fixture가 capability false로 시작 전 거부되고,
    두 Duration claim API, atomic combined renewal API와 capability를 모두 구현한 fixture만
    실행되는지 검증한다.
22. 30초·15분·24시간 lease 모두에서 latency alert threshold가 repository timeout보다
    작고, timeout 표본이 p95에 포함돼 sustained timeout이 실제 alert를 발동하는지 검증한다.
23. 실제로 취소된 parent context에서 외부 cancellation의 Job/Step STOPPED completion만
    heartbeat gate 종료, `cancelAndJoin`, 최신 snapshot 조회, terminal 호출 전체를 감싼
    단일 `NonCancellable` bounded timeout 안에서 시도되고 원래 cancellation을 재던지는지
    검증한다. join이 정지하거나 completion timeout/실패가 발생하면 event와 suppressed category를 남기고,
    lease-loss와 정상 renewal에는 `NonCancellable` repository I/O가 없어야 한다.
24. 성공·업무 실패 `BatchReport.jobExecution`이 초기 claimed version이 아니라 completion
    CAS에 사용한 최신 guard version과 terminal status/owner 해제를 반영하며 post-update
    readback version으로 오해할 수 없도록 KDoc과 fixture를 검증한다.
25. JDBC/R2DBC에서 별도 connection이 row lock을 repository timeout보다 길게 보유하고,
    driver read/network stall을 주입해 server/driver timeout 또는 cancellation이 statement를
    종료하는지 검증한다. rollback과 pool connection 반환을 barrier로 관찰한 뒤에만 guard
    Mutex가 해제되고 lease-loss가 반환되며 orphan transaction과 후속 write는 0건이어야 한다.
26. PostgreSQL/MySQL/H2 timeout 설정이 repository timeout 이하이고 transaction 뒤 원래
    session 값으로 복원되는지, timeout/cancellation 미지원 custom adapter가 capability
    preflight에서 거부되는지 검증한다.
27. 최소 lease에서 heartbeat 최대 `1.0 × runners` SQL/s와 Job/Step claim rate의
    `7 × (jobStarts/s + stepStarts/s)`를 합산하고, one-second start burst까지 승인 DB
    statement budget을 넘는 설정이 canary preflight에서 거부되는지 검증한다. one-second
    fixture는 `10 × runners + 7 × (jobBurst + stepBurst + jobRate + stepRate)`를 사용한다.
28. cancellation completion failure event가 stable allowlist와 critical alert fixture에서
    동일 id로 검증되고, renewal event의 `latencyMillis`가 millisecond p95와 timeout 표본을
    재현하며 허용 범위 밖 값은 clamp되는지 검증한다.
29. supported JDBC/R2DBC adapter의 정상 종료와 lease-loss에서 statement 취소, rollback,
    pool 반환과 heartbeat join이 `repositoryTimeoutMillis` 안에 끝나는지 검증한다.
    cancellation 미지원 custom adapter는 capability preflight에서 거부하고, post-start fault
    injection으로 cleanup timeout이 발생하면 child 종료를 주장하지 않은 채 completion 0회,
    degraded health, scheduling pause와 supervisor-termination event를 검증한다.
30. writer, rollout, rollback receipt의 stale head, 빈 owner, artifact checksum mismatch,
    부족한 observation window/sample/heartbeat window와 active lease 이전 rollback이 각각
    validator exit 1로 다음 stage/이전 version 기동을 차단하는지 검증한다. rollback approval의
    unauthorized approver, wrong application/environment/head/target, expired approval, 변조된
    signature/checksum과 credential-bearing reference도 mutation 0건으로 실패해야 한다.
31. 네 alert 각각의 required evidence kind, clear window/sample, exact head/owner와 protected
    resume authority/signature를 통과한 receipt만 resume validator exit 0인지 검증한다.
    missing/extra/wrong-kind evidence, checksum mismatch, expired approval 또는 triggered alert의
    resume receipt를 rollout observation에서 누락하면 promotion 0회여야 한다.

## 수용 기준 매핑

| Issue 수용 기준 | 구현·증거 |
|---|---|
| owner+version lease renewal | core API, InMemory, JDBC, R2DBC renewal contract와 adapter tests |
| bounded heartbeat와 lease-loss 감지 | internal guard, write-before-check, lifecycle/cancellation tests |
| 두 runner overlap 통합 증거 | JDBC/R2DBC barrier tests와 stale mutation assertions |
| DSL·운영 문서 일관성 | `executionLease(Duration)` DSL/KDoc, README en/ko, operations schema/example/validator, 중앙 manual downstream handoff |

## 완료 조건

- 설계·구현 계획과 6개 독립 관점 검토에서 P0/P1이 0건이다.
- core/JDBC/R2DBC RED→GREEN 증거와 순차 Testcontainers 결과가 남는다.
- `detekt`, 관련 module build, 문서 parity 검사가 통과한다.
- public ABI 변경은 renewal API와 additive capability/DSL에 한정되고 그 변경이
  `BatchInfrastructureFailureException` public class/property/constants를 포함하며 compatibility
  및 rollout 절차와 함께 문서화된다.
- exact head 독립 코드 리뷰와 PR CI가 통과한다.
- 기존 repository binary fixture, Kotlin/Java source consumer, `apiCheck`가 통과한다.
- PR 본문은 한국어이며 마지막에 `## DoD Status`를 포함한다.
- merge는 별도 승인 전 수행하지 않는다.
