# Issue #697 JDBC 병렬 key enumeration 취소·transaction lifecycle 설계

## 문서 상태

- 대상 이슈: [#697](https://github.com/bluetape4k/bluetape4k-exposed/issues/697)
- 상위 이슈: [#690](https://github.com/bluetape4k/bluetape4k-exposed/issues/690)
- Epic: [#659](https://github.com/bluetape4k/bluetape4k-exposed/issues/659)
- Stacked train slot: #697 (선행 #698 merge 후, 후속 #690 전)
- 기준 base: `develop` `5b2f0a1a4f4580d2f293d42b622080ad9e3bd8c2`
- 구현 branch: `fix/issue-697-cancellation-lifecycle`
- worktree: `.worktrees/fix-issue-697-cancellation-lifecycle`
- 분류: Type C — JDBC 병렬 취소 정리의 재현 가능한 lifecycle 결함
- 설계 상태: 사용자 승인 완료

## 문제 정의

`parallelJdbcKeyEnumeration`은 각 PK range를 독립 Exposed JDBC transaction으로 실행하고,
호출자가 선택한 `ExecutorService`에서 결과를 range 선언 순서로 materialize한다. 현재
실패 경로는 child `VirtualFuture`에 `cancel(true)`를 보낸 뒤 `future.await()`를 호출한다.
그러나 `VirtualFuture.await()`는 내부 `Future.get()`에 위임한다. Java `Future`는 취소 요청을
수락한 즉시 `get()`에서 `CancellationException`을 반환할 수 있으므로, interrupt를 무시하는
worker의 transaction과 JDBC connection이 아직 살아 있어도 parent가 실패를 반환할 수 있다.

현재 permit도 `future.toCompletableFuture().whenComplete`에서 반환한다. 취소된 wrapper의
completion은 실제 worker의 transaction close보다 먼저 관찰될 수 있어, 아직 connection을
점유한 child가 있는 동안 다음 range가 permit을 재사용할 수 있다. 이는 `maxConcurrency`가
논리적인 transaction 수를 제한한다는 의도와 실제 lease lifecycle을 분리한다.

## 근거와 영향 범위

- production 구현: `exposed/jdbc/src/main/kotlin/io/bluetape4k/exposed/jdbc/JdbcParallelKeyEnumeration.kt`
- child 생성: `virtualThreadJdbcTransactionAsync`가 `executor.submit`으로 worker를 만들고
  `transaction { statement(this) }`를 실행한다.
- 취소 결함: `cancelAndAwait`가 취소된 `Future.get()` 예외를 삼켜 실제 worker 종료를
  확인하지 않는다.
- 기존 H2 테스트는 협조적으로 `InterruptedException`을 던지는 sibling만 확인한다.
- #694/#698의 PostgreSQL·MySQL fixture는 active Hikari lease를 계측하므로 실제 connection
  반환 시점을 고정할 수 있다.
- 영향: 실패 반환 직후의 connection 고갈, 다음 호출의 pool 대기, caller interrupt 상태
  손실 가능성. 정상 결과 순서와 sequential loader는 이 결함과 독립이다.

## 목표와 불변 경계

### 목표

1. 취소 요청(`Future.cancel(true)`)과 실제 child worker·Exposed transaction·connection
   종료를 별도 completion 경계로 분리한다.
2. 실패 또는 caller interrupt를 반환하기 전에 모든 시작된 child가 transaction cleanup까지
   마쳤음을 join한다.
3. permit을 실제 transaction wrapper가 반환한 뒤에만 재사용한다.

### 불변 경계

- public `parallelJdbcKeyEnumeration` signature, `JdbcParallelKeyEnumerationOptions`,
  `VirtualFuture`, ABI surface를 변경하지 않는다.
- caller-owned `ExecutorService`를 닫지 않는다. 기본 `VirtualThreadExecutor`도 소유권을
  바꾸지 않는다.
- 정상 경로의 range ordering, duplicate 검증, database/isolation/readOnly 옵션과
  sequential cache loader를 변경하지 않는다.
- R2DBC, #690 benchmark/chart, dependency/catalog/workflow, stable
  `docs/manual/**`(현재 1.12.1)는 수정하지 않는다.

## 채택 설계

### Child lifecycle handle

각 range를 다음 두 요소를 가진 internal handle로 추적한다.

1. 결과와 취소 요청을 담당하는 기존 `VirtualFuture<List<ID>>`;
2. 실제 JDBC transaction wrapper가 반환한 뒤 완료되는 `CountDownLatch(1)`.

child 제출은 기존 public helper를 확장하지 않고, 이 파일의 private/internal 경로에서
`virtualFuture`와 `transaction`을 조합한다. worker는 다음 경계를 갖는다.

```text
virtual worker 시작
  -> transaction(db, isolation, readOnly) { rangeReader(...) }
  -> transaction commit/rollback 및 connection cleanup 완료
  -> permit.release()
  -> lifecycle.countDown()
```

`permit.release()`와 `lifecycle.countDown()`은 transaction 호출 바깥 `finally`에 둔다.
따라서 statement가 예외를 던지거나 `cancel(true)`가 interrupt를 전달해도 transaction
wrapper가 cleanup을 끝낸 뒤에만 다음 range가 시작된다.

### 실패 정리와 interrupt

실패 경로는 모든 handle에 취소를 요청한 뒤 lifecycle latch를 **uninterruptibly join**한다.
join 중 `InterruptedException`이 발생하면 모든 child가 종료된 뒤 parent thread의 interrupt
상태를 복원한다. 원래 실패 원인은 `ExecutionException` unwrap 규칙을 유지한다.

이 방식은 interrupt를 영원히 무시하고 반환하지 않는 외부 JDBC driver를 강제로 종료한다고
주장하지 않는다. 그런 child는 실제 종료까지 parent가 기다리며, 테스트는 유한한
interrupt-ignoring synthetic worker와 실제 driver/pool의 bounded cleanup을 검증한다.

## 대안과 결정

### A. transaction 바깥 completion handle — 채택

기존 public API를 보존하면서 실제 Exposed transaction cleanup 뒤의 시점을 표현한다.
permit과 join 신호가 같은 lifecycle boundary를 사용해 취소 wrapper의 조기 completion을
피한다.

### B. `VirtualFuture` public API에 join 메서드 추가 — 거부

공용 concurrent API와 ABI를 넓히고 모든 사용자의 cancellation semantics를 결정해야 한다.
이번 JDBC bug fix의 책임 경계를 넘어가며, 기존 `VirtualFuture`가 실제 worker lifecycle을
노출하지 않는 문제를 공용 계약으로 일반화한다.

### C. `Future.get()`/`CompletableFuture` 상태 polling — 거부

취소된 future는 실제 worker 완료와 다른 상태를 보고하므로 polling으로 근본 문제를
해결하지 못한다. busy-wait와 timing 의존성을 추가한다.

### D. executor shutdown 또는 JDBC `Connection.abort` 강제 — 거부

caller-owned executor와 shared executor의 소유권을 침해하고 다른 range/호출자에 영향을
준다. driver별 abort 계약은 별도 이슈가 필요하다.

## 수용 기준

- [x] H2 synthetic test에서 `3+ ranges`, `maxConcurrency=2`, 실패 range와 interrupt를
  무시하는 sibling을 재현하고, `parallelJdbcKeyEnumeration` 반환 시 active child가 0이다.
- [x] 같은 테스트가 sibling의 interrupt 관찰, 원래 실패 원인 보존, caller executor
  미종료를 확인한다.
- [x] caller interrupt 경로는 child completion을 join한 뒤 parent interrupt 상태를
  복원하고, child가 남아 있는 동안 반환하지 않는다.
- [x] PostgreSQL Testcontainers/Hikari 및 MySQL Testcontainers/Hikari의 실패·취소
  targeted test가 transaction 종료 후 `active == 0`을 확인한다.
- [x] 정상 range ordering/concurrency와 기존 H2 helper regression이 유지된다.
- [x] public API/ABI, sequential loader, R2DBC, benchmark, stable manual은 변경되지 않는다.

## 검증 경계와 미검증 항목

| 항목 | 판정 |
| --- | --- |
| H2 synthetic lifecycle | 이번 slot 필수 RED/GREEN |
| PostgreSQL Hikari active lease | 이번 slot 필수, Docker가 없으면 PASS로 대체하지 않고 PENDING |
| MySQL Hikari active lease | 이번 slot 필수, PostgreSQL과 순차 실행 |
| JVM/driver가 interrupt를 영원히 무시하는 강제 종료 | N/A — 실제 종료까지 기다리는 계약으로 명시 |
| R2DBC close provenance | N/A — #697 JDBC 범위 밖, 별도 issue 판단 |
| cross-driver benchmark/chart | N/A — #690 slot |
| stable `docs/manual/**` | N/A — release `1.12.1` source of truth |

### 실행 결과

- H2 targeted lifecycle: 10/0/0/0, H2 full JDBC module: 211/0/0/23.
- PostgreSQL targeted: 7/0/0/0, MySQL targeted: 11/0/0/0.
- 공유 JDBC test module: 72/0/0/5.
- affected JDBC detekt와 `git diff --check`: 성공.

## SPW-01~05 설계 gate

- [x] **SPW-01** — #697/#659/#690, 선행 #698 merge head, source symbols, H2/PostgreSQL/MySQL
  fixture와 public/manual 경계를 확인했다.
- [x] **SPW-02** — 문제, lifecycle contract, 선택지, acceptance, compatibility, N/A와
  rollback 경계를 포함했다.
- [x] **SPW-03** — 한국어 technical register를 적용하고 `Future.cancel(true)`,
  `CancellationException`, `CountDownLatch`, `PENDING`, API token을 보존했다.
- [x] **SPW-04** — 현재 `JdbcParallelKeyEnumeration.kt`, `VirtualFuture`, `virtualThreadJdbcTransactionAsync`,
  H2/driver fixture와 live #697 수용 기준을 대조했다.
- [x] **SPW-05** — Markdown read-back으로 lifecycle 그림, 표, acceptance와 N/A 경계를 확인했다.
