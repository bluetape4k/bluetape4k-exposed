# tenant JDBC registry는 수명 소유권과 전달 증거를 함께 고정한다

## 맥락

`exposed-workshop`의 두 예제가 tenant별 `DataSource`와 Exposed `Database`를
같은 방식으로 만들고 조회하고 종료했다. 공통 구현을 라이브러리로 옮길 때는 API
모양보다 resource 수명, 실패 우선순위, downstream 이전 순서를 먼저 확정해야 했다.

## 결정

- caller가 tenant 목록과 `DataSource` factory/disposer를 제공하고, registry는 자신이
  생성한 resource의 종료만 책임진다.
- registry는 생성 시 변경할 수 없는 tenant-resource map을 만들고 unknown tenant를 즉시 거부한다.
- 종료는 Exposed 등록 해제 후 `DataSource` dispose를 생성 역순으로 실행한다. 여러
  실패는 primary/suppressed 규칙으로 합치고, fatal 객체 자체는 장기 상태에 보관하지 않는다.
- provider artifact와 consumer 이전을 분리한다. 이번 PR은 `bluetape4k-exposed`의
  공용 모듈을 제공하고, workshop 이전은 `bluetape4k/exposed-workshop#269`에서 진행한다.

## 결과와 검증

- 신규 모듈 27개 테스트가 실패·오류·제외 없이 통과했다.
- 기존 JDBC 603개 테스트, 전체 `detekt`, ABI 45/45, publication metadata/POM 감사,
  CI/Nightly matrix 계약을 통과했다.
- 128 tenant와 256 cleanup failure 회귀 테스트로 종료 실패 집계가 입력 규모에 따라
  선형으로 증가하는지 확인했다.
- 여섯 관점 구현 리뷰와 최종 inline exact-diff fallback에서 P0/P1/P2/P3가 모두 0이었다.

## 놓친 점과 재발 방지

최초 PR에는 Type A 필수 lesson 파일과 이슈 메타데이터 복제가 빠졌다. merge-ready
재조회에서 PR diff, assignee, labels, milestone을 이슈 #816과 대조해 누락을 발견했다.
lesson 추가로 head가 바뀌므로 이전 CI 결과를 재사용하지 않고 새 exact head CI를 다시 확인한다.

또한 새 모듈과 BOM 변경이라는 이유만으로 수동 Full Nightly를 merge 필수 조건으로
확대했다. CG-14가 요구하는 것은 exact-head required CI와 리뷰·thread 검증이고,
PR CI의 H2 shard가 이미 tenant test·Kover·ABI를 실행한다. Nightly의 PostgreSQL/MySQL
job에는 tenant module을 추가로 실행하는 경로가 없어 수동 dispatch는 #816만의 고유한
수용 기준을 더 검증하지 않는다. 따라서 Nightly workflow 등록 검증과 수동 Full Nightly
dispatch 필요성을 분리한다. 사용자가 명시적으로 요청하거나 미검증 backend 위험이
확인되지 않는 한 수동 Full Nightly를 merge gate로 추가하지 않는다.

앞으로 새 모듈 PR을 만들기 전에는 다음 항목을 한 번에 대조한다.

1. `docs/lessons/` 파일이 현재 branch에 추적·커밋되었는지 확인한다.
2. issue와 PR의 assignee, labels, milestone, `Closes` 연결을 live 조회한다.
3. PR body가 마지막 `## DoD Status`에서 현재 head의 미완료 gate를 정확히 표시하는지 확인한다.
4. 추가 workflow dispatch를 merge gate로 만들기 전에 기존 required CI와 다른 고유 검증 경로, 명시적 정책 또는 사용자 요구가 있는지 확인한다.
