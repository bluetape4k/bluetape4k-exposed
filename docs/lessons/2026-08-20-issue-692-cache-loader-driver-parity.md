# Issue #692 cache loader custom ID·driver parity lesson

## Context

Issue #692는 JDBC Lettuce suspended, JDBC Redisson synchronous, R2DBC Redisson
loader가 표준 scalar PK가 아닌 실제 transformed `IdTable`에서도 같은 paging 경계를
지키는지 확인하는 Type A conformance 작업이다. 안정 배포선은 `1.12.1`이므로
`docs/manual/**`와 production algorithm/API/ABI는 변경하지 않았다.

## Decision or Finding

- 세 adapter에 test-local non-`Comparable` transformed `IdTable`을 두고 `batchSize=2`의
  `LIMIT`/`OFFSET`, ASC 순서, cardinality, duplicate 부재를 실제 SQL로 검증했다.
- READ_COMMITTED page mutation은 첫 data SELECT의 `SqlLogger` barrier와 별도 writer
  connection으로 `a03` 삭제와 `a99` 삽입을 commit해 `[a01, a02, a04, a05, a99]`라는
  weak-consistency 관찰값을 고정했다. 하나의 읽기 기준을 보장한다고 과장하지 않는다.
- JDBC Redisson synchronous는 page별 lazy transaction이 아니라 하나의 JDBC
  transaction 안에서 모든 page를 읽고 반환 전에 materialize한다. 이 경계를 KDoc,
  EN/KO README, 설계·계획에 동일하게 기록했다.
- R2DBC Redisson은 rendezvous channel back-pressure, top-level `maxAttempts=1`,
  caller가 주입한 scope의 cancellation, ambient caller-owned retry와 partial ID 재방출
  위험을 분리한다. `queryTimeout=30_000`은 Exposed 1.4.0에서 초 단위이므로 “30초”로
  주장하지 않으며 statement-timeout 값·단위·cleanup은 #699가 소유한다. #692는
  R2DBC 전체 열거 60초 timeout과 custom fixture evidence만 제공한다.
- `$bluetape-writer` audit에서 발견한 기존 README의 blanket loanword 두 건은
  domain 의미를 바꾸지 않는 `커밋 기준 데이터`/`기준 데이터 PUT`으로 정리했다. 기존
  문서의 의미를 바꾸는 광범위한 번역은 하지 않았다.

## Outcome

- H2 full: JDBC Lettuce `892/0/0/73`, JDBC Redisson `630/0/0/1`, R2DBC Redisson
  `220/0/0/2` (tests/failures/errors/skipped).
- PostgreSQL targeted custom selector: JDBC Lettuce `6/0/0/0`, JDBC Redisson
  `4/0/0/0`, R2DBC Redisson `12/0/0/1`; R2DBC 60초 timeout은 PostgreSQL에서
  실행했고 H2 timeout case는 의도적으로 skip했다.
- 세 affected module detekt와 Kotlin compile, `git diff --check`, EN/KO parity,
  terminology audit가 통과했다. public signature/ABI, catalog/BOM, workflow,
  benchmark/chart, stable manual은 변경하지 않았다.

## Verification

| 검증 | 결과 |
| --- | --- |
| H2 full, 세 module 순차 실행 | `892/0/0/73`, `630/0/0/1`, `220/0/0/2`, 모두 `BUILD SUCCESSFUL` |
| PostgreSQL targeted, 세 module 순차 실행 | `6/0/0/0`, `4/0/0/0`, `12/0/0/1`, 모두 `BUILD SUCCESSFUL` |
| detekt | affected module 3개 `BUILD SUCCESSFUL`, finding 없음 |
| 문서 | EN/KO loader contract parity 및 terminology audit finding 0 |
| 범위/공백 | `git diff --check` 성공, `docs/manual/**`·API/ABI·catalog/BOM·workflow·benchmark/chart 변경 없음 |

PostgreSQL full module nightly/hosted CI는 이 worktree의 local targeted evidence와
분리된 merge gate이며, CI 전에는 `Blocked: 1`로 보고한다. MySQL은 #698, network fault는
별도 후속 범위다.

## Future Guidance

1. 새 loader adapter를 추가할 때는 `Comparable`이 아닌 transformed `IdTable`과
   `batchSize` exact/partial page를 H2와 최소 한 non-H2 driver에서 함께 검증한다.
2. R2DBC cancellation 테스트는 독립 `SupervisorJob`을 취소하는 것으로 일반 caller
   cancellation을 주장하지 말고, caller가 소유해 주입한 `Job` 부모를 취소한 뒤
   다음 page 미실행을 확인한다. channel cause·후속 복구·직접 connection close는 이
   fixture의 증거가 아니며 별도 검증으로 분리한다. 기본 shared scope의 자동 전파는
   보장하지 않으며 별도 계약으로 문서화한다.
3. `queryTimeout` 값의 단위나 statement-timeout cleanup을 #692 fixture evidence와
   섞지 않는다. production 수정·driver별 statement timeout은 #699가 소유한다.
4. Type A 작업은 PR merge-ready 선언 전에 이 lesson과 fresh test XML, independent
   7-Tier review를 함께 남긴다. stable manual은 실제 release 승격 시점까지 유지한다.

## Writer DoD (SPW-01~05)

- [x] SPW-01 — Issue #692, Epic #659, 세 adapter, #698/#699와 후속 범위를 고정했다.
- [x] SPW-02 — custom ID, paging, mutation, cancellation, retry, timeout acceptance와
  N/A/Blocked 경계를 실제 테스트에 맞춰 기록했다.
- [x] SPW-03 — 한국어 technical register와 `OFFSET`, `AsyncIterator`, `N/A`, `Blocked`
  token을 보존하고 blanket loanword를 의미 보존형으로 정리했다.
- [x] SPW-04 — source/KDoc, EN/KO README, H2/PostgreSQL XML, detekt와 diff-check를
  대조했다.
- [x] SPW-05 — Markdown read-back으로 수치, 링크, release/manual 경계와 후속 owner를
  확인했다.
