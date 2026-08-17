# Issue #650: R2DBC `saveAll(Flow)` 방출 시점

## Context

`SimpleExposedR2dbcRepository.saveAll(Flow)`가 하나의 Exposed transaction에서
엔티티를 저장하면서 `persist` 결과를 즉시 방출하고 있었다. 입력 Flow가 뒤에서
실패하거나 collector가 취소되면 transaction은 롤백되지만, 이미 관찰된 ID와
도메인 객체가 외부 side effect를 시작할 수 있다.

`saveAll(Iterable)`은 transaction block을 끝낸 뒤 결과를 방출하는 반면 Flow
오버로드만 다른 계약을 가지고 있어, 같은 이름의 API에서 commit 경계가
불일치했다.

## Decision or Finding

- Flow 오버로드도 `Iterable` 오버로드와 같은 commit-before-emission 계약을
  사용한다. 최상위 transaction에서는 commit 이후에 방출하고, 활성 outer
  transaction에서는 caller-owned 최종 commit 전에 방출될 수 있음을 구분한다.
- 입력을 transaction 안에서 순차 저장하고 결과를 임시 목록에 보관한 뒤,
  transaction block이 정상 종료된 다음 결과를 방출한다. retry attempt마다
  목록을 새로 만들어 실패 attempt의 결과가 남지 않게 한다.
- 입력 수집 block에서 예외나 cancellation이 발생하면 결과를 방출하지 않고 transaction
  rollback을 유지한다. commit 뒤 downstream collector의 예외나 cancellation은 이미
  완료된 transaction을 rollback하지 못하고 남은 결과 방출만 중단한다.
- Exposed가 최상위 R2DBC transaction을 재시도하면 입력 Flow가 다시 collect될 수
  있으므로 retry 환경에서는 replayable하고 side effect가 없는 입력을 사용한다.
- 이 slot에서는 chunked write API를 추가하지 않는다. 큰 입력을 지속적으로
  처리해야 하면 별도 API 설계가 필요하다.
- 활성 outer transaction을 Exposed가 재사용하면 최종 commit/rollback은 호출자
  책임이다. 따라서 외부 side effect는 outer scope 성공 뒤에 수행해야 한다.

## Outcome

회귀 테스트는 다음 transaction·방출 경계를 고정한다.

- upstream이 끝나지 않으면 collector가 저장 결과를 관찰하지 못하고 명시적
  cancellation 뒤 저장 상태가 비어 있어야 한다.
- upstream 예외가 발생하면 결과 목록이 비어 있고 저장 상태가 롤백되어야 한다.
- upstream 완료 플래그가 설정되기 전에 결과를 방출하면 테스트가 실패해야 한다.
- finite Flow에 `first()`를 적용해도 top-level commit 뒤 결과가 관찰되고 저장 상태가
  유지되어야 한다.
- commit 뒤 collector가 예외를 발생시켜도 예외가 전파되고 transaction에 저장된 전체
  row가 유지되어야 한다.
- outer transaction에서는 caller transaction identity를 유지하고 최종 commit/rollback을
  caller가 결정한다.

구현은 `channelFlow` 즉시 방출 경로를 제거하고 transaction attempt 내부의 목록을
반환받은 뒤 `inTransaction` 반환 이후 `emitAll`하도록 단순화했다. 따라서 retry로
transaction block이 다시 실행되어도 실패 attempt의 결과는 버려진다.

## Verification

- 변경 전 H2 targeted test: 기존 회귀군 32개 통과.
- RED 단계 H2: 강화한 세 테스트가 모두 실패해 provisional ID, upstream 완료 전
  방출, 실패 시 결과 1건 방출을 재현했다.
- 구현 후 H2 `saveAll with Flow*`: 7개 테스트 통과. `nested transactions=false`
  경로에서 caller transaction identity도 유지됨을 확인했다.
- 전체 `SimpleExposedR2dbcRepositoryTest`: H2 36개 테스트 통과.
- `EXPOSED_TEST_DB=POSTGRESQL` 순차 실행: H2 + PostgreSQL 72개 테스트 통과.
- `EXPOSED_TEST_DB=MYSQL_V8` 순차 실행: H2 + MySQL V8 70개 통과, 2개는 기존
  동시성 테스트의 조건부 skip이다.
- `saveAll(Iterable)`도 retry attempt 내부에서 결과 목록을 만들도록 맞춰 두
  overload의 provisional 결과 누적 가능성을 제거했다.
- 공통 fixture가 `maxAttempts = 1`로 고정되어 강제 retry fault injection 자체는
  별도 환경 검증 대상으로 남긴다.
- PostgreSQL·MySQL V8 및 전체 모듈 검증은 PR 전 단계에서 순차 실행했다.
- 전체 Spring Boot R2DBC 모듈 H2 test 123개와 module `check`가 성공했다.

## Future Guidance

`saveAll(Flow)`를 streaming API로 설명하지 않는다. 결과를 commit 이후에
방출해야 하므로 현재 구현은 입력을 소비하고 결과를 transaction 동안 보관한다.
최상위 transaction retry가 활성화된 환경에서는 입력 Flow가 재수집될 수 있으므로
replayable·side-effect-free 입력을 사용한다. 실제 row-by-row read streaming에는
`streamAll()`을 사용하고, chunked write가 필요하면 transaction 단위·메모리 경계·외부
side effect 순서를 별도 계약으로 정의한 뒤 추가한다.
