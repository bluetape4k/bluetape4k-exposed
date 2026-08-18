# Issue #685 R2DBC `saveAll` 재시도 정책 설계

## 문서 상태

- 대상 이슈: [#685](https://github.com/bluetape4k/bluetape4k-exposed/issues/685)
- Epic/stack: [#658](https://github.com/bluetape4k/bluetape4k-exposed/issues/658) Slot 5
- 대상 모듈: `:bluetape4k-exposed-spring-boot-r2dbc`
- 대상 릴리스: `1.13.0` 개발선
- 선행 조건: #684 / PR #686 merge 완료 (`91012e75b8d6c80dac7d8264e651dc5ece98db68`)
- 설계 결정: repository-owned top-level `saveAll`만 retry를 끄고, active outer
  transaction의 caller-owned retry 정책과 public ABI는 유지한다.

## 문제 정의

Exposed 1.4.0의 top-level R2DBC transaction은 `R2dbcException`을 받으면
transaction block을 다시 실행할 수 있고 기본 `maxAttempts`는 3이다. 현재
`SimpleExposedR2dbcRepository.saveAll(Flow)`와 `saveAll(Iterable)`은 입력 수집을
transaction block 안에서 수행한다. 따라서 commit 단계의 일시 오류가 발생하면
one-shot/hot 입력은 두 번째 시도에서 이미 소비된 값을 잃고, side-effectful
입력은 수집 부수효과를 반복한다.

#684는 commit fault와 attempt-local 결과 목록을 결정론적으로 검증했지만,
입력 재수집 자체를 방지하는 production 정책은 아직 없다. replayable 입력을
요구하는 문서만으로는 Spring Data의 일반적인 `saveAll(Flow)` 호출자에게
재시도 경계를 안전하게 제공하지 못한다.

## 채택한 정책

두 `saveAll` overload가 호출하는 private helper를 추가한다. helper 진입 전에
현재 R2DBC transaction이 있는지 확인해 top-level에서만 retry를 제한한다.

```kotlin
private suspend inline fun <T> inTransactionWithoutRetry(
    crossinline block: suspend R2dbcTransaction.() -> T,
): T {
    val hasOuterTransaction = TransactionManager.currentOrNull() != null
    return suspendTransaction {
        if (!hasOuterTransaction) maxAttempts = 1
        block()
    }
}
```

- repository가 transaction을 소유하는 top-level 호출에서는 Exposed retry를
  명시적으로 한 번으로 제한한다. commit fault는 호출자에게 전파되고 입력은
  정확히 한 번 수집된다.
- 이미 active outer transaction이 있으면 Exposed 기본 설정에서 nested
  `suspendTransaction`이 outer 객체를 재사용할 수 있다. helper는
  `TransactionManager.currentOrNull()`로 이 경계를 감지해 `maxAttempts`를
  변경하지 않는다. outer block이 재시도되면 caller가 선택한
  replayable·side-effect-free 입력이 다시 수집될 수 있다.
- transaction 내부의 attempt-local `buildList`와 commit-before-emission 계약은
  #650/#684와 동일하게 유지한다. 실패 attempt의 row, generated ID, 결과는
  rollback 뒤 관찰되지 않으며 성공 결과만 원래 순서로 한 번 방출한다.
- 다른 CRUD 메서드는 기존 `inTransaction` 경로와 기본 retry 정책을 유지한다.
  streaming query의 기존 `maxAttempts = 1` 처리도 변경하지 않는다.

## 계약 매트릭스

| 경계 | 입력 수집 | retry 소유자 | 기대 결과 |
| --- | --- | --- | --- |
| top-level `saveAll(Flow)` | repository transaction 안에서 1회 | repository (`maxAttempts=1`) | fault 전파, 결과/row 0 또는 성공 attempt만 방출 |
| top-level `saveAll(Iterable)` | repository transaction 안에서 1회 | repository (`maxAttempts=1`) | iterator 1회, 실패 provisional 결과 없음 |
| active outer transaction | outer block 안에서 수행 | caller | caller `maxAttempts`만큼 replay 가능, 최종 commit/rollback은 caller 책임 |
| downstream cancellation/failure | commit 이후 결과 수집 중 | 이미 종료된 transaction | 저장 결과는 유지되고 남은 방출만 중단 |

## 검증 설계

- #684의 test-only `OneShotR2dbcFaultFactory`를 재사용해 첫 commit에서
  `R2dbcTransientResourceException`을 한 번 주입한다.
- top-level Flow 테스트는 replayable cold, one-shot/hot, side-effectful 입력의
  수집/부수효과 횟수를 확인하고 fault가 retry되지 않아 예외가 한 번 전파되는지
  검증한다. 실패 attempt의 row와 반환 결과는 없어야 한다.
- top-level Iterable 테스트는 iterator 호출을 계수해 한 번만 순회되는지와
  rollback 뒤 row/ID가 남지 않는지를 확인한다.
- outer transaction 테스트는 caller `maxAttempts = 2`를 지정하고 fault 뒤
  outer block이 두 번 실행되며 replayable 입력의 두 번째 성공 결과만 남는지
  확인한다. 반환 결과는 outer commit 전에 방출될 수 있으므로 emission 시점
  commit count가 `[0, 0, 1, 1]`로 관찰되는지와 최종 반환 ID가 저장 row와
  일치하는지도 함께 확인한다. 이 테스트는 helper가 outer retry를 숨기지
  않음을 증명한다.
- H2와 PostgreSQL을 순차 실행하고 detekt, ABI, README parity, `git diff --check`를
  fresh evidence로 기록한다. FluentQuery marker 바깥의 saveAll section은 별도
  bounded token parity command로 EN/KO 계약을 대조한다.

## 비목표와 경계

- `docs/manual/**`의 안정 manual은 현재 1.12.1이므로 변경하지 않는다. 1.13.0
  manual 승격은 릴리스 gate인 #651에서 수행한다.
- Exposed upstream retry 알고리즘, driver timeout(#674), ToxiProxy 기반 네트워크
  의미론, bounded/chunked write API(#644)는 다루지 않는다.
- public configuration, repository signature, ABI resource, Gradle dependency를
  추가하지 않는다.

## 승인 기준

1. top-level Flow/Iterable이 commit fault에서 재수집되지 않고 입력·side effect·
   provisional 결과가 한 번만 관찰된다.
2. active outer transaction의 caller retry 설정과 commit/rollback ownership이
   유지된다.
3. production 변경은 두 `saveAll` overload와 private helper로 한정되고 public
   API/ABI가 변하지 않는다.
4. KDoc, EN/KO module README, 설계·계획·lesson이 같은 retry/재수집 계약을
   설명한다.
5. H2/PostgreSQL 순차 테스트, detekt, ABI, parity, diff check가 통과한다.

## Writer DoD (SPW-01~05)

- [x] SPW-01 — Issue/Epic, 현재 `develop` source, Exposed 1.4.0 retry 근거,
  명시적 N/A 범위를 고정했다.
- [x] SPW-02 — 문제, 결정, 계약 매트릭스, 검증, 비목표, 승인 기준을 포함했다.
- [x] SPW-03 — 한국어 technical register와 transaction/retry 용어를 일관되게
  사용하고 code token은 보존했다.
- [x] SPW-04 — #684 fault factory, 구현 경로, outer transaction semantics, ABI와
  README downstream 문서를 대조했다.
- [x] SPW-05 — Markdown read-back으로 code fence, 표, 링크, acceptance 범위를
  확인했다.
