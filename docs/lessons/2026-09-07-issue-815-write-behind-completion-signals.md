# #815 write-behind 테스트의 완료 신호 경계

## 상황

[PR #830](https://github.com/bluetape4k/bluetape4k-exposed/pull/830)의 exact-head
[CI run `34042513330`](https://github.com/bluetape4k/bluetape4k-exposed/actions/runs/34042513330)에서
`jdbc-caffeine/POSTGRESQL` 행이 두 번 실패했다. 첫 실행
[job `101512644013`](https://github.com/bluetape4k/bluetape4k-exposed/actions/runs/34042513330/job/101512644013)은
대량 insert 테스트가 DB 건수 `1000`에서 깨어난 뒤 `1000`보다 커야 한다는
assertion에 실패했다. failed-only 재실행
[job `101518420288`](https://github.com/bluetape4k/bluetape4k-exposed/actions/runs/34042513330/job/101518420288)은
retry 테스트가 첫 번째 `afterPersisted` 기록만 관찰한 상태에서 두 건을 기대해
실패했다.

두 실패 모두 production write-behind 정산보다 테스트의 완료 조건이 먼저
충족되는 문제였다. 로컬 단일 실행이 통과하더라도 hosted 부하에서는 조기 관찰
구간이 드러날 수 있다.

## 결정

- 대량 insert 시나리오는 실행 전 DB 건수와 `entityMap`의 실제 고유 ID 수를
  합산한다. 이 기대 건수에 도달할 때까지 기다린 뒤 정확히 같은지 검증한다.
- JDBC, suspended JDBC, R2DBC 공통 시나리오에 같은 기준을 적용한다.
- retry hook 테스트는 DB update 완료나 queue depth를 hook 완료의 대체 신호로
  쓰지 않는다. `afterPersisted`가 전체 batch를 기록한 뒤 latch를 해제한다.
- production 코드와 공개 API는 변경하지 않는다.

## 검증과 결과

- 두 hosted failure 대상은 PostgreSQL에서 수정 후 총 4회 연속 통과했다.
- `jdbc-caffeine` 전체는 PostgreSQL과 H2에서 각각 `181 tests / 2 skipped`로
  성공했다.
- `r2dbc-caffeine` 전체 H2는 `121 tests / 1 skipped`로 성공했다.
- canonical `./gradlew detekt`는 성공했다. 직접 실행한 `detektTestFixtures`와
  `detektTest`는 각각 기존 baseline과 같은 `50`, `22`개 진단으로 실패했다.
  이 source-set task는 현재 clean gate가 아니며, 신규 진단 통과로 기록하지 않는다.

## 다음 변경에서 지킬 규칙

- 비동기 저장 건수는 입력 크기만 비교하지 말고 `초기 상태 + 실제 고유 입력`을
  기대값으로 만든다.
- queue 정산, DB statement 실행, callback 완료는 서로 다른 관찰 경계다. 테스트가
  주장하는 마지막 side effect에서 완료 신호를 보낸다.
- 재실행 통과만으로 race를 닫지 않는다. hosted failure의 조기 관찰 구간을 설명하고
  조건 기반 대기로 고정한 뒤 반복 실행한다.

## 문서 검증 DoD

- [x] SPW-01: 한국어 개발자용 lesson이며 PR #830의 exact-head hosted failure와
  변경 파일, 로컬 검증을 원본으로 고정했다.
- [x] SPW-02: 상황, 결정, 결과, 놓친 경계, 다음 검증 규칙을 포함했다.
- [x] SPW-03: KO-01–KO-07 검토에서 식별자, 수치, callback 경계를 보존했다.
- [x] SPW-04: hosted assertion, 현재 source, fresh PostgreSQL/H2 결과를 대조했다.
- [x] SPW-05: 최종 Markdown read-back과 terminology audit 결과를 기록한다.
