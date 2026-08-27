# Issue #732 Caffeine lifecycle coordinator 실행 계획

## 문서 상태

- 이슈: [#732](https://github.com/bluetape4k/bluetape4k-exposed/issues/732)
- 저장소: `bluetape4k/bluetape4k-exposed`
- 기준 ref: `origin/develop@c5e9d499d9c1baeb6f92a531345d184c16febc27`
- workflow: Type A `bluetape-full-feature`
- 선행 설계: `docs/superpowers/specs/2026-08-27-issue-732-caffeine-coordinator-design.md`
- 작업 worktree: `.worktrees/refactor/issue-732-caffeine-coordinator`
- 실행 경계: 구현·검증까지만 수행하고 PR·merge·release는 수행하지 않는다.

승인된 설계를 coordinator의 logical state와 adapter-owned I/O로 분리하고,
DB 없는 conformance RED→GREEN을 먼저 만든다. 기존 public API, report,
serialization, metric 이름과 adapter별 transaction 의미는 보존한다.

## 완료·중단 조건

완료는 다음 증거를 모두 확보한 경우에만 선언한다.

1. internal coordinator가 `exposed/cache`에만 존재하고 세 adapter가 friend-path로
   컴파일되며 published API/clean consumer에 coordinator가 나타나지 않는다.
2. admission token, bounded pending capacity, retry cap, close owner/follower,
   publication lease와 cleanup-before-signal의 conformance 테스트가 통과한다.
3. JDBC, suspended JDBC, R2DBC adapter가 기존 CacheHealthReport/public interface/
   serial UID/metric/afterPersisted 의미와 ABI를 유지한다.
4. H2를 먼저 실행하고 JDBC/suspended JDBC는 PostgreSQL→MySQL_V8를 순차 실행하며,
   R2DBC는 fixture 존재 여부에 따라 같은 순서를 적용한다. `PASS`만 green이고
   `FAIL`, `PENDING`, `N/A`, `SKIPPED`는 manifest에 보존한다.
5. `build/verification/write-behind-db-matrix.json`과
   `build/verification/write-behind-metrics.json`이 schema/version/required row를
   만족하고 CI aggregator가 fail-closed로 동작한다. 문서, detekt, Kover, ABI
   검사가 통과한다.

다음은 즉시 해당 단계에서 중단할 조건이다.

- coordinator가 public signature, `@Internal` facade 또는 published artifact에
  노출되거나 friend-path 누락을 조용히 허용하는 경우
- close가 publication lease 완료/거부 전에 invalidate 또는 completion signal을
  공개하거나, commit lock을 lease drain 동안 보유해 self-deadlock을 만드는 경우
- queue admission/permit/retry가 capacity를 넘거나 cancellation을 삼키는 경우
- 기존 ABI/serial UID/metric tag가 drift하거나 raw key/entity/Throwable가
  공통 observation/log/serialization으로 유입되는 경우
- DB assertion 실패/fixture 부재/컨테이너 unavailable을 PASS로 위장하는 경우

## 단계별 실행

### T0. 기준선과 workflow receipt

- 두 worktree와 canonical checkout의 branch/HEAD를 확인하고 canonical의
  `.issue721-workflow/`, `.issue721-worktree/` untracked 파일은 건드리지 않는다.
- run `20260827T071058Z-6fa1ef92`의 expected head/owner receipt와 changed paths를
  확인하고 단계별 `mutation-check`를 실행한다.
- 다음 baseline을 저장한다.

```bash
EXPOSED_TEST_DB=H2 ./gradlew \
  :bluetape4k-exposed-jdbc-caffeine:test \
  :bluetape4k-exposed-r2dbc-caffeine:test \
  --no-configuration-cache --no-daemon --console=plain
./gradlew checkProductionAbi --no-configuration-cache --no-daemon --console=plain
git diff --check
```

### T1. RED conformance와 ABI fixture 선행

source를 바꾸기 전에 다음 테스트/fixture를 추가해 의도한 실패를 관찰한다.

- `WriteBehindCoordinatorTest`에 token lifecycle, queue-full 즉시 거부, double
  settlement, `onFlushSucceeded` depth invariant, terminal late callback, close
  owner identity/CAS와 forged/old/follower lease 거부를 고정한다.
- bounded admission stress에서 queued + blocked sender + in-flight가 capacity를
  넘지 않고 permit이 정확히 한 번 반환되는지, Int overflow/무제한 waiter가
  없는지 검증한다.
- retry batch 상한, retry-first, 10ms→1s capped backoff, 최초 포함 8회 cap과
  permanent `FAILED` 전환을 deterministic clock으로 고정한다.
- publication lease를 accepted settlement와 원자적으로 묶고 실제 cache.put
  완료·예외·취소까지 유지하는지 검증한다. blocked put barrier에서 close는
  짧은 terminal gate/commit lock만 잡고 lock을 풀어 lease drain을 수행하며,
  guard가 put을 거부한 뒤 invalidate와 signal이 순서대로 관찰되는지 고정한다.
- `CacheHealthReport`, `LocalCacheConfig`, 세 public repository/interface,
  `CachePersistedWrite`의 기존 `.api`, descriptor, serial UID와 serialized
  fixture baseline을 저장한다.
- `build/verification/write-behind-db-matrix.json`의 schema `1`과
  `required`/`applicable` boolean, `status` enum, `reason`, timestamps,
  adapter/database 필드를 검증하는 fail-closed parser를 RED로 만든다.

### T2. coordinator와 canonical capacity 구현

- `exposed/cache/src/main/kotlin/io/bluetape4k/exposed/cache/internal/WriteBehindCoordinator.kt`
  에 logical state, admission token, close owner/follower, fresh owner identity와
  unpublished→published CAS, `CoordinatorSnapshot`을 구현한다.
- `MAX_WRITE_BEHIND_QUEUE_CAPACITY = 100_000`을 이 파일에 한 번만 정의하고
  config/세 adapter가 이를 참조한다. coordinator에는 Channel, scope, DB, key,
  Throwable, blocking call, `runBlocking`을 넣지 않는다.
- 모든 callback은 한 linearization point에서 terminal/settled token을 먼저
  확인하고 late callback을 no-op으로 만든다. `onFlushSucceeded`의 count/depth,
  worker completion, close residual 상태를 invariant로 강제한다.
- coordinator fake 테스트를 GREEN으로 만든 뒤 public API dump에 internal 타입과
  coordinator signature가 없음을 확인한다.

### T3. friend-path와 adapter-owned admission/close primitive

- `exposed/jdbc-caffeine`의 JDBC 및 suspended JDBC main compile task와
  `exposed/r2dbc-caffeine` main compile task에 `exposed/cache` main output을
  `-Xfriend-paths`로 연결한다. friend output가 없으면 task가 fail하고 public
  facade로 우회하지 않는다.
- 각 adapter에 bounded atomic admission pool, queue handoff token settlement,
  retry batch/attempt/backoff, worker cancel/interrupt/join/guard를 구현한다.
- adapter-owned `PublicationLease`와 commit lock을 추가한다. queue send 성공 후
  lease 획득과 `settleEnqueue(true)`를 terminal gate와 같은 짧은 원자 경계에서
  처리하고, lease drain 중 lock을 잡지 않는다. close는 absolute deadline의
  remaining을 drain에 재사용하고, drain 후 lock을 재획득해 invalidate한다.
- close owner가 cleanup(lease drain, worker join/guard, invalidate, scope cancel)을
  마친 뒤에만 coordinator completion/follower signal을 publish한다.

### T4. JDBC/R2DBC/suspended JDBC 통합

- `AbstractJdbcCaffeineRepository`는 `transaction`과 existing `afterPersisted`,
  raw `lastFlushError` legacy report를 유지하면서 coordinator callback과
  publication lease를 연결한다.
- `AbstractSuspendedJdbcCaffeineRepository`는 `suspendedTransactionAsync`와
  public method-set을 변경하지 않고 동일한 coordinator/friend-path 계약을
  연결한다.
- `AbstractR2dbcCaffeineRepository`는 cancellable `send`/`suspendTransaction`,
  기존 close arbitration와 report 의미를 coordinator에 연결한다.
- flush 일반 예외는 adapter-owned legacy field에서만 보관하고, cancellation은
  재전파해 worker `FAILED`로 만든다. 모든 adapter의 late callback/cache put을
  terminal guard가 막는지 확인한다.

### T5. ABI·metric·관측성 및 verification 산출물

- 기존 metric 이름/tag/type을 유지하고 common coordinator metric을 추가하지
  않는다. logical event당 exactly-once, queueDepth는 gauge 값이고 label이
  아님을 capture fixture로 검증한다.
- `build/verification/write-behind-metrics.json` schema `1`에 name/type/unit/
  labels/owner와 baseline checksum을 기록하고 누락·추가·tag drift를 실패시킨다.
- `build/verification/write-behind-db-matrix.json`은 각 adapter/database row를
  `required`와 `applicable`로 명시한다. JDBC/suspended JDBC H2/PG/MySQL는
  required=true, R2DBC H2는 required=true, PG/MySQL는 fixture가 있을 때만
  applicable=true(required=true), 없으면 required=false/status=N/A로 기록한다.
  aggregator는 required && applicable 행의 `PASS`만 green으로 집계하고 나머지
  상태를 숨기지 않는다.
- `.api`, JVM descriptor, serial UID, clean external consumer를 실행해
  coordinator leakage와 public drift를 fail-closed로 검증한다.

### T6. 문서·CI·회귀 테스트

- queue/batch 범위, putAll partial semantics, retry/failure, application-owned
  outbox/dead-letter와 close publication ordering을 EN/KO README/manual에
  반영한다.
- CI에 `verify-write-behind-db-matrix`와 metric/ABI inventory artifact를
  추가하고 기존 nightly 병렬 matrix를 대체 증거로 취급하지 않는다.
- H2/fixture contract, cancellation, close timeout/interruption, blocked put,
  serial/consumer fixture와 기존 adapter 테스트를 모두 GREEN으로 만든다.

### T7. 순차 검증 명령

```bash
set -euo pipefail
EXPOSED_TEST_DB=H2 ./gradlew \
  :bluetape4k-exposed-cache:test \
  :bluetape4k-exposed-jdbc-caffeine:test \
  :bluetape4k-exposed-r2dbc-caffeine:test \
  --no-configuration-cache --no-daemon --console=plain

EXPOSED_TEST_DB=POSTGRESQL ./gradlew \
  :bluetape4k-exposed-jdbc-caffeine:test \
  --no-configuration-cache --no-daemon --console=plain

EXPOSED_TEST_DB=MYSQL_V8 ./gradlew \
  :bluetape4k-exposed-jdbc-caffeine:test \
  --no-configuration-cache --no-daemon --console=plain

./gradlew checkProductionAbi detekt \
  --no-configuration-cache --no-daemon --console=plain
git diff --check
```

Docker 검증 전 `colima status`, `docker context show`, `docker info`를 읽는다.
R2DBC PG/MySQL fixture 부재는 manifest의 명시적 N/A이며 green이 아니다.

### T8. 최종 review·receipt·handoff

- changed paths와 source/API/POM/metadata/manifest/metric inventory를 다시 읽고,
  Type A 최종 6관점 code review에서 P0/P1 0을 확인한다.
- `check-result`, `component-evidence`, `completion-check`, `verify`를 실행한다.
  implementation, tests, matrix/metric evidence가 모두 있을 때만 workflow
  completion을 기록한다.
- local branch/worktree와 canonical unrelated untracked 파일을 보존한다.
  PR·merge·push·release·cleanup은 이 작업에서 수행하지 않는다.

## 결과 기록

- 변경 파일: coordinator, 세 adapter, Gradle friend-path/config, tests, ABI/metric/
  DB manifest, docs/CI.
- 검증: conformance, H2→PostgreSQL→MySQL sequential matrix, ABI/consumer,
  detekt/Kover와 각 receipt.
- 남은 위험: non-interruptible 외부 JDBC/R2DBC driver thread는 caller contract이며,
  unavailable container/fixture는 PASS로 승격하지 않는다.
