# Issue #684 R2DBC `saveAll` 재시도 fault-injection 설계

## 문서 상태

- 대상 이슈: [#684](https://github.com/bluetape4k/bluetape4k-exposed/issues/684)
- Epic/stack: [#658](https://github.com/bluetape4k/bluetape4k-exposed/issues/658) Slot 4
- 대상 모듈: `:bluetape4k-exposed-spring-boot-r2dbc`
- 대상 릴리스: `1.13.0` 개발선
- 선행 조건: #650 / PR #683 merge 완료
- 설계 결정: production API와 transaction ownership은 유지하고 test-only R2DBC fault adapter로 Exposed 1.4.0 top-level retry를 재현한다.

## 문제 정의

Exposed 1.4.0의 `inTopLevelSuspendTransaction`은 `R2dbcException`을 받으면
transaction block 전체를 다시 실행할 수 있다. 공용 R2DBC fixture는 현재
`maxAttempts = 1`로 고정되어 있어 `saveAll(Flow)`의 입력 재수집과
`saveAll(Iterable)`의 attempt 결과 격리를 실제 retry 경계에서 증명하지 못한다.
현재 #650 merge 소스는 Flow와 Iterable 모두 결과 리스트를 transaction 반환값
내부에서 만들도록 수정되어 있다. 남은 gap은 이 구조가 실제 retry에서 실패
attempt의 provisional 결과를 버리고 성공 attempt만 방출하는지 실행해 보지
않았다는 점이다.

## 목표

1. 새 dependency나 public configuration 없이 H2와 PostgreSQL R2DBC connection을
   한 번만 `R2dbcException`으로 실패시키는 결정론적 test adapter를 만든다.
2. `maxAttempts = 2`, zero retry delay로 첫 attempt rollback과 두 번째 성공을
   재현하고 실제 connection/attempt 수를 검증한다.
3. `saveAll(Flow)`는 실패 attempt의 row/ID를 방출하지 않고 성공 attempt의
   순서대로 정확히 한 번만 방출하는지 검증한다.
4. `saveAll(Iterable)`도 같은 attempt-local 결과 계약을 갖는지 검증한다.
   production regression이 발견될 때만 최소 변경을 허용하며, 현재 계획은
   test-only 증명을 기본값으로 한다.
5. replayable·side-effect-free 입력, caller-owned outer transaction,
   cancellation/downstream failure 계약은 기존 #650 테스트와 모순 없이 유지한다.

## 채택한 fault-injection 경계

ToxiProxy는 이미 존재하지만 네트워크 단절 시점과 driver별 오류 형태가
비결정적이고 Testcontainers 자원을 요구한다. 이번 slot은 Exposed retry 자체와
repository 결과 계약만 고정하므로, `io.r2dbc.spi.ConnectionFactory`를 감싸는
test-only adapter를 사용한다.

- `ConnectionFactory.create()`가 반환한 connection만 dynamic proxy로 감싼다.
- 첫 attempt의 transaction block과 accumulator가 완료된 뒤 `commitTransaction()`을
  `R2dbcTransientResourceException`으로 한 번만 실패시킨다.
- commit 직전에 실패하므로 transaction-local 또는 외부 accumulator를 사용하는
  구현 모두에서 첫 attempt 결과가 만들어진 상태다. rollback 후 새 connection에서
  block/input을 재실행할 때 실패 attempt 결과가 누적되지 않는지를 검증한다.
- connection proxy는 statement와 driver의 fluent chain을 건드리지 않고 begin,
  commit, rollback, close publisher만 계수·위임한다.
- adapter는 `spring-boot/r2dbc/src/test` 아래에만 두며 production classpath,
  public API, Gradle dependency, stable manual은 변경하지 않는다.
- retry 테스트의 기본 `maxAttempts`는 2이며,
  `BLUETAPE_R2DBC_SAVE_ALL_MAX_ATTEMPTS` 환경변수로만 calibration RED를
  재현한다. 이 knob은 test source에만 존재하고 production 설정에는 노출되지
  않는다.
- backend의 network/timeout fault semantics는 이 설계의 보장 범위가 아니며
  별도 driver 환경 issue에서 다룬다.

## 고정 계약과 검증 매트릭스

| 영역 | 검증 | 기대 결과 |
| --- | --- | --- |
| retry boundary | 첫 commit fault 1회, `maxAttempts=2`, delay 0 | failure 1회, connection 2회, begin callback 2회 이상(진단값), commit 2회, rollback 2회, 최종 성공 |
| Flow | replayable counting Flow builder로 2 entities 재수집 | emitted 2개, 입력 순서 유지, duplicate ID 없음, DB row 2개 |
| Iterable | iterator 호출을 계수하는 replayable Iterable로 2 entities 재순회 | emitted 2개, 실패 attempt provisional 결과 미포함, DB row 2개 |
| input contract | non-replayable side effect 입력은 사용하지 않음 | KDoc/README의 replayable·side-effect-free 조건과 일치 |
| cancellation/failure | 기존 #650 top-level cancellation, upstream/downstream failure 테스트 | fault adapter 없이도 원래 예외 identity와 commit/rollback 경계 보존 |
| outer transaction | 기존 #650 caller-owned outer transaction 테스트 | fault adapter 없이도 repository가 outer `maxAttempts`/commit/close를 변경하지 않음 |
| backend | H2 targeted, PostgreSQL representative 순차 matrix | backend unavailable/skip은 PASS로 세지 않고 증거 기록 |

## 비목표

- `saveAll`을 chunked/bounded streaming으로 바꾸는 #644 범위
- Exposed retry/backoff 알고리즘 변경 또는 global `DatabaseConfig` 변경
- driver timeout/네트워크 장애의 대표성 보장(#674 후속)
- ToxiProxy dependency 추가
- `docs/manual/**` 안정 릴리스 `1.12.1` 문서 변경
- 공개 ABI 변경, 새로운 repository database 소유권, Spring transaction bridge

## 오류·수명주기 규칙

- adapter가 만든 `R2dbcTransientResourceException`은 commit publisher 구독 시
  한 번만 발생하고, Exposed가 rollback 후 retry할 수 있어야 하며, retry exhausted
  시 caller에 한 번만 전파된다.
- dynamic proxy는 begin/rollback/close와 cancellation publisher를 delegate에
  그대로 위임하고, commit fault만 한 번 주입한다. test double이 cancellation을
  삼키거나 connection을 소유하지 않는다.
- 테스트 helper는 `NonCancellable` cleanup에서 default database를 복구하고
  `closeAndUnregister` 및 table cleanup을 수행한다. cleanup 중 추가 예외가
  발생해도 원래 transaction fault/cancellation을 주 예외로 유지하고 suppressed로
  보존한다.
- Flow와 Iterable 모두 transaction block의 반환값으로 attempt 결과를
  materialize한 뒤 commit 이후에만 방출한다.
- 입력 Flow는 retry 시 재수집될 수 있으므로 replayable·side-effect-free
  입력만 허용한다는 기존 public KDoc/README 계약을 유지한다.

## 승인 기준

- calibration RED에서
  `BLUETAPE_R2DBC_SAVE_ALL_MAX_ATTEMPTS=1`로 custom retry database를 실행하면
  injected commit fault가 재시도 없이 Flow/Iterable에서 전파된다. acceptance
  GREEN은 기본값 2와 zero delay로 실행해 양쪽 모두 성공하고, attempt-local
  결과만 방출하는 것을 검증한다.
- GREEN에서 targeted H2 test와 전체 spring-boot/r2dbc module test가 통과한다.
- `git diff --check`, Kotlin test checklist, lesson gate, P0/P1 review가 모두
  수렴한다.
